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
import java.util.regex.Matcher;
import java.util.stream.Stream;

import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;

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
     * Execute a CLI command (resolved against the user's configured CLI folder),
     * wait up to the specified timeout, capture its standard output, and return it.
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

        // 3) Log exactly what we're about to run
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
     * Runs a CLI command with an inactivity timeout and optional progress monitoring.
     */
    public static ExecResult execComplexCommand(
            int inactivityTimeoutSec,
            String tilesDoneRegex,
            String... args) throws IOException, InterruptedException {

        // ---- Build command ----
        String exeName = args[0] + (MinorFunctions.isWindows() ? ".exe" : "");
        String cliFolder = QPPreferenceDialog.getCliFolder();
        Path exePath = Path.of(cliFolder, exeName);

        List<String> cmd = new ArrayList<>();
        cmd.add(exePath.toString());
        if (args.length > 1) cmd.addAll(Arrays.asList(args).subList(1, args.length));

        logger.info("→ Running external command: {} (via folder: {})", cmd, cliFolder);
        for (int i = 0; i < cmd.size(); i++) {
            logger.info("Arg {}: {}", i, cmd.get(i));
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

        Pattern tilesPat = tilesDoneRegex == null ? null : Pattern.compile(tilesDoneRegex);
        int totalTifs = 0;
        UIFunctions.ProgressHandle progressHandle = null;

        // --- Inactivity timer ---
        AtomicLong lastProgressTime = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean timedOut = new AtomicBoolean(false);

        // Set up file-based progress monitoring
        Path baseDir = null;
        Set<Path> tileDirs = new HashSet<>();

        if (args.length >= 6) {  // We have enough args to construct a path
            baseDir = Paths.get(args[2], args[3], args[4]); // projects/sample/mode
            logger.info("Base directory for monitoring: {}", baseDir);

            // Check for PPM angles in the arguments
            List<Double> angles = extractAngles(args);
            int angleCount = angles.isEmpty() ? 1 : angles.size();

            // For bounds workflow
            if ("bounds".equals(args[5])) {
                Path boundsDir = baseDir.resolve("bounds");
                tileDirs.add(boundsDir);

                // Count expected tiles from TileConfiguration
                int baseTiles = MinorFunctions.countTifEntriesInTileConfig(List.of(boundsDir.toString()));
                totalTifs = baseTiles * angleCount;

                logger.info("Bounds workflow: monitoring {} for {} base tiles × {} angles = {} total files",
                        boundsDir, baseTiles, angleCount, totalTifs);
            } else {
                // For annotation workflow, args[5] is the annotation name
                Path annotationDir = baseDir.resolve(args[5]);
                tileDirs.add(annotationDir);

                int baseTiles = MinorFunctions.countTifEntriesInTileConfig(List.of(annotationDir.toString()));
                totalTifs = baseTiles * angleCount;

                logger.info("Annotation workflow: monitoring {} for {} base tiles × {} angles = {} total files",
                        annotationDir, baseTiles, angleCount, totalTifs);
            }

            // Start progress bar if we have tiles to monitor
            if (totalTifs > 0) {
                progressHandle = UIFunctions.showProgressBarAsync(tifCounter, totalTifs, process,
                        inactivityTimeoutSec * 1000);
                logger.info("Started progress bar for {} expected tiles", totalTifs);

// Create monitoring thread
                final Set<Path> finalTileDirs = new HashSet<>(tileDirs);
                final int expectedFiles = totalTifs;

                Thread fileMonitor = new Thread(() -> {
                    Set<String> seenFiles = new HashSet<>();
                    logger.info("File monitor thread started");
                    logger.info("  - Watching directories: {}", finalTileDirs);
                    logger.info("  - Expecting {} total TIF files", expectedFiles);
                    logger.info("  - Using tifCounter with identity: {}", System.identityHashCode(tifCounter));

                    // First, verify the directories exist
                    for (Path dir : finalTileDirs) {
                        if (Files.exists(dir)) {
                            logger.info("  - Directory EXISTS: {}", dir);
                            // List immediate contents
                            try {
                                logger.info("  - Contents of {}: ", dir);
                                Files.list(dir).limit(10).forEach(p ->
                                        logger.info("    - {}", p.getFileName()));
                            } catch (IOException e) {
                                logger.error("Failed to list directory contents", e);
                            }
                        } else {
                            logger.warn("  - Directory MISSING: {}", dir);
                        }
                    }

                    int checkCount = 0;
                    while (seenFiles.size() < expectedFiles) {
                        try {
                            checkCount++;

                            // Check all monitored directories
                            for (Path monitorDir : finalTileDirs) {
                                if (Files.exists(monitorDir)) {
                                    List<Path> tifFiles = new ArrayList<>();
                                    try (Stream<Path> files = Files.walk(monitorDir)) {
                                        files.filter(p -> {
                                            String name = p.getFileName().toString().toLowerCase();
                                            return name.endsWith(".tif") || name.endsWith(".tiff");
                                        }).forEach(tifFiles::add);
                                    }

                                    // Log what we found on first check and periodically
                                    if (checkCount == 1 || checkCount % 40 == 0) {
                                        logger.info("Check #{} - Found {} TIF files in {}",
                                                checkCount, tifFiles.size(), monitorDir);
                                    }

                                    // Process new files
                                    for (Path p : tifFiles) {
                                        String filePath = p.toString();
                                        if (!seenFiles.contains(filePath)) {
                                            seenFiles.add(filePath);
                                            int oldCount = tifCounter.get();
                                            int newCount = tifCounter.incrementAndGet();
                                            lastProgressTime.set(System.currentTimeMillis());
                                            logger.info("NEW TIF DETECTED: {}", p.getFileName());
                                            logger.info("  - Counter incremented from {} to {}", oldCount, newCount);
                                            logger.info("  - Total found so far: {}/{}", newCount, expectedFiles);
                                        }
                                    }
                                } else if (checkCount == 1 || checkCount % 40 == 0) {
                                    logger.warn("Directory still missing on check #{}: {}", checkCount, monitorDir);
                                }
                            }

                            // Log periodic status
                            if (checkCount % 40 == 0) { // Every 10 seconds
                                logger.info("=== File Monitor Status ===");
                                logger.info("  Checks performed: {}", checkCount);
                                logger.info("  Files found: {}/{}", seenFiles.size(), expectedFiles);
                                logger.info("  Counter value: {}", tifCounter.get());
                                logger.info("  Process alive: {}", process.isAlive());
                                logger.info("==========================");
                            }

                            // Check if we should stop
                            if (!process.isAlive() && seenFiles.size() >= expectedFiles) {
                                logger.info("Process ended and all files found, stopping monitor");
                                break;
                            }

                            Thread.sleep(250); // Check every 250ms
                        } catch (Exception e) {
                            logger.error("File monitor error", e);
                        }
                    }

                    logger.info("File monitor thread ending - found {}/{} files",
                            seenFiles.size(), expectedFiles);
                }, "TIF-File-Monitor");
                fileMonitor.setDaemon(true);
                fileMonitor.start();
            }
        }

        // ---- Output Thread ----
        Thread tOut = new Thread(() -> {
            try {
                String line;
                while ((line = outR.readLine()) != null) {
                    out.append(line).append('\n');
                    logger.debug("CLI stdout: {}", line);

                    // Check for progress pattern in output
                    //commented out for double counting
//                    if (tilesPat != null && tilesPat.matcher(line).find()) {
//                        int count = tifCounter.incrementAndGet();
//                        lastProgressTime.set(System.currentTimeMillis());
//                        logger.debug("Progress from stdout regex: {}", count);
//                    }

                    // Reset timer on any output to prevent premature timeout
                    lastProgressTime.set(System.currentTimeMillis());
                }
            } catch (IOException ignored) { }
        });

        Thread tErr = new Thread(() -> {
            try {
                String line;
                while ((line = errR.readLine()) != null) {
                    err.append(line).append('\n');
                    logger.debug("CLI stderr: {}", line);
                }
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
                        logger.warn("Process inactive for {} seconds, killing...", idle);
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

        if (progressHandle != null) {
            logger.info("Closing progress bar handle");
            progressHandle.close();
        }

        return new ExecResult(process.exitValue(), timedOut.get(), out, err);
    }

    /**
     * Extract angle values from command arguments.
     * Looks for --angles parameter or parenthesized angle list.
     */
    private static List<Double> extractAngles(String[] args) {
        List<Double> angles = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            // Check for --angles parameter
            if ("--angles".equals(args[i]) && i + 1 < args.length) {
                // Parse space-separated angles
                String[] angleStrs = args[i + 1].split("\\s+");
                for (String angleStr : angleStrs) {
                    try {
                        angles.add(Double.parseDouble(angleStr));
                    } catch (NumberFormatException e) {
                        logger.debug("Could not parse angle: {}", angleStr);
                    }
                }
            }

            // Check for parenthesized format like "(90.0)"
            if (args[i].matches("\\(.*\\)")) {
                String angleList = args[i].replaceAll("[()]", "");
                String[] angleStrs = angleList.split("\\s+");
                for (String angleStr : angleStrs) {
                    try {
                        angles.add(Double.parseDouble(angleStr));
                    } catch (NumberFormatException e) {
                        logger.debug("Could not parse angle from parentheses: {}", angleStr);
                    }
                }
            }
        }

        if (!angles.isEmpty()) {
            logger.info("Detected {} rotation angles: {}", angles.size(), angles);
        }

        return angles;
    }
}