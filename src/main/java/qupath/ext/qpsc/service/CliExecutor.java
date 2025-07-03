package qupath.ext.qpsc.service;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MinorFunctions;


/**
 * CliExecutor
 *
 * <p>Core service for running your microscope CLI:
 *   - Builds argument lists from your run command preference + args.
 *   - Starts processes, applies timeouts, captures stdout/stderr.
 *   - Returns structured ExecResult for callers to inspect exit codes, output, or errors.
 */


public class CliExecutor {
    private static final Logger logger = LoggerFactory.getLogger(CliExecutor.class.getName());
    /** Result of a CLI call. */
    public record ExecResult(
            int            exitCode,
            boolean        timedOut,
            StringBuilder  stdout,
            StringBuilder  stderr
    ) { }

    public static int execCommandExitCode(String... args)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        return p.waitFor();
    }

    /**
     * Execute a CLI command (resolved against the user’s configured CLI folder),
     * wait up to the specified timeout, capture its standard output, and return it.
     *
     * <p>The first element of {@code args} is treated as the executable name
     * (without ".exe "), which is looked up in the folder returned by
     * {@link QPPreferenceDialog#getCliFolder()}.
     * Any remaining elements of {@code args} are passed as command-line arguments.</p>
     *
     * @param timeoutSec Number of seconds to wait before forcibly killing the process;
     *                   zero means wait indefinitely.
     * @param args       Command name (first element, no extension) followed by any arguments to pass.
     * @return the trimmed stdout of the process.
     * @throws IOException          if an I/O error occurs, the process exits non-zero,
     *                              or the process times out.
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public static String execCommandAndGetOutput(int timeoutSec, String... args)
            throws IOException, InterruptedException {

        // 1) Resolve executable path
        String exeName = args[0] + (MinorFunctions.isWindows() ? ".exe" : "");
        String cliFolder = QPPreferenceDialog.getCliFolder();
        Path exePath = Paths.get(cliFolder, exeName);

        // 2) Build command list
        List<String> cmd = new ArrayList<>();
        cmd.add(exePath.toString());
        if (args.length > 1) {
            cmd.addAll(Arrays.asList(args).subList(1, args.length));
        }

        // 3) Log exactly what we’re about to run
        logger.info("→ Running external command: {}  (resolved via {})", cmd, cliFolder);

        // 4) Start the process
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();

        // 5) Wait for completion or timeout
        if (timeoutSec > 0) {
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Command timed out after " + timeoutSec + " seconds");
            }
        } else {
            process.waitFor();
        }

        // 6) Capture stdout and stderr
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        // 7) Check exit code
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("Command " + cmd + " failed (exit " + exitCode + "):\n" + stderr.trim());
        }

        return stdout.trim();
    }

    /**
     * Runs a CLI command with an inactivity timeout: if no matching stdout line (e.g., "tiles done")
     * is seen for the specified time, the process is killed and returns timedOut=true.
     * The inactivity timer is reset every time progress is made (i.e., the regex matches a line).
     *
     * @param inactivityTimeoutSec Timeout in seconds with no progress before killing process
     * @param tilesDoneRegex   Regex to count progress (e.g., "tiles done"). Can be null.
     * @param args             Command-line args after the executable.
     * @return ExecResult containing exit code, timeout flag, and full stdout/stderr.
     * @throws IOException          On I/O error.
     * @throws InterruptedException If interrupted while waiting for the process to complete.
     */
    public static ExecResult execComplexCommand(
            int inactivityTimeoutSec,
            String tilesDoneRegex,
            String... args) throws IOException, InterruptedException {

        // ---- Build command (as before) ----
        String exeName = args[0] + (MinorFunctions.isWindows() ? ".exe" : "");
        String cliFolder = QPPreferenceDialog.getCliFolder();
        Path exePath = Path.of(cliFolder, exeName);

        List<String> cmd = new java.util.ArrayList<>();

        logger.info(String.valueOf(cmd));
        cmd.add(exePath.toString());
        if (args.length > 1) cmd.addAll(Arrays.asList(args).subList(1, args.length));
        logger.info("→ Running external command: {} (via folder: {})", cmd, cliFolder);
        logger.info("cmd: " + cmd);
        for (int i = 0; i < cmd.size(); i++) {
            logger.info("Arg " + i + ": " + cmd.get(i));
        }
        // ---- Launch process ----
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PYTHONUNBUFFERED", "1");
        Process process = pb.start();

        // ---- Output capture ----
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        BufferedReader outR = new BufferedReader(new InputStreamReader(process.getInputStream()));
        BufferedReader errR = new BufferedReader(new InputStreamReader(process.getErrorStream()));

        // ---- Progress counter and timer ----
        AtomicInteger tifCounter = new AtomicInteger();
        logger.info("Created tifCounter with identity: {}", System.identityHashCode(tifCounter));

// Around line 160, make totalTifs final
        Pattern tilesPat = tilesDoneRegex == null ? null : Pattern.compile(tilesDoneRegex);
        final int totalTifs; // Make it final
        UIFunctions.ProgressHandle progressHandle = null;
        // --- Inactivity timer ---
        AtomicLong lastProgressTime = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean timedOut = new AtomicBoolean(false);
// Set up file-based progress monitoring
        if (args.length > 5) {  // Ensure we have enough arguments for the path
            Path tileDir = Paths.get(args[2], args[3], args[4], args[5]);

            totalTifs = MinorFunctions.countTifEntriesInTileConfig(List.of(tileDir.toString()));
            logger.info("Monitoring directory for TIF files: {}", tileDir);
            logger.info("Expected TIF files: {}", totalTifs);


            if (totalTifs > 0) {
                // The existing AtomicLong lastProgressTime is already defined on line 178
                progressHandle = UIFunctions.showProgressBarAsync(tifCounter, totalTifs, process, 20000);
                logger.info("Passed tifCounter (identity: {}) to progress bar", System.identityHashCode(tifCounter));
                // Create final reference for use in lambda
                final Path monitorDir = tileDir;
                final int expectedFiles = totalTifs;

                // Start file monitoring thread
                Thread fileMonitor = new Thread(() -> {
                    Set<String> seenFiles = new HashSet<>();
                    while (process.isAlive() && seenFiles.size() < expectedFiles) {
                        try {
                            if (Files.exists(monitorDir)) {
                                try (Stream<Path> files = Files.list(monitorDir)) {
                                    files.filter(p -> p.toString().toLowerCase().endsWith(".tif"))
                                            .forEach(p -> {
                                                String fileName = p.getFileName().toString();
                                                if (!seenFiles.contains(fileName)) {
                                                    seenFiles.add(fileName);
                                                    tifCounter.incrementAndGet();
                                                    logger.info("Incremented tifCounter (identity: {}) to: {}",
                                                            System.identityHashCode(tifCounter), tifCounter.get());
                                                    lastProgressTime.set(System.currentTimeMillis());
                                                    logger.debug("New TIF detected: {}, progress: {}/{}",
                                                            fileName, tifCounter.get(), expectedFiles);
                                                }
                                            });
                                }
                            }
                            Thread.sleep(250); // Check every 250ms
                        } catch (Exception e) {
                            logger.debug("File monitor error: {}", e.getMessage());
                        }
                    }
                    logger.info("File monitor finished. Found {} TIF files", seenFiles.size());
                });
                fileMonitor.setDaemon(true);
                fileMonitor.start();
            }
        } else {
            totalTifs = 0; // Default value when not monitoring files
        }


        // ---- Output Thread ----
        Thread tOut = new Thread(() -> {
            try {
                String line;
                while ((line = outR.readLine()) != null) {
                    out.append(line).append('\n');
                    logger.debug("CLI output line: {}", line);
                    //This section was used prior to directly checking for the existence of .tif files
//                    if (tilesPat != null && tilesPat.matcher(line).find()) {
//                        tifCounter.incrementAndGet();
//                        lastProgressTime.set(System.currentTimeMillis());
//                        logger.debug("Progress incremented to: {}", tifCounter.get()); // ADD THIS
//                    }
                    // To reset timer on *any* output, uncomment the following line:
                    // else lastProgressTime.set(System.currentTimeMillis());
                }
            } catch (IOException ignored) { }
        });
        Thread tErr = new Thread(() -> {
            try {
                String line;
                while ((line = errR.readLine()) != null)
                    err.append(line).append('\n');
            } catch (IOException ignored) { }
        });
        tOut.start();
        tErr.start();

        // ---- Timeout Thread ----
        Thread timeoutThread = new Thread(() -> {
            try {
                while (process.isAlive()) {
                    long now = System.currentTimeMillis();
                    long idle = (now - lastProgressTime.get()) / 1000L;
                    if (idle > inactivityTimeoutSec) {
                        timedOut.set(true);
                        process.destroyForcibly();
                        err.append("Process killed after inactivity timeout of ")
                                .append(inactivityTimeoutSec).append(" seconds\n");
                        break;
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {}
        });
        timeoutThread.setDaemon(true);
        timeoutThread.start();

        // ---- Wait for process to exit ----
        process.waitFor();
        tOut.join();
        tErr.join();
        timeoutThread.join(100);

        if (progressHandle != null) progressHandle.close();

        return new ExecResult(process.exitValue(), timedOut.get(), out, err);
    }


}
