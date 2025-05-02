package qupath.ext.qpsc.service;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.util.regex.Pattern;

import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MinorFunctions;




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
     * (without “.exe”), which is looked up in the folder returned by
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
     * Run the microscope CLI and capture its output.
     *
     * @param timeoutSec      seconds after which the process will be destroyed (0 ⇒ wait forever)
     * @param tilesDoneRegex  if non-null, every stdout line matching this regex
     *                        increments the progress-counter.
     * @param args            everything *after* the run-command stored in prefs.
     * @return ExecResult with exit-code, stdout, stderr and timeout flag.
     */
    public static ExecResult execComplexCommand(
            int timeoutSec,
            String tilesDoneRegex,
            String... args) throws IOException, InterruptedException {

        //------------------------------------------------------------
        // 0) Build full command
        //------------------------------------------------------------
        String exeName = args[0] + (MinorFunctions.isWindows() ? ".exe" : "");
        String cliFolder = QPPreferenceDialog.getCliFolder();
        Path exePath = Paths.get(cliFolder, exeName);

        List<String> cmd = new ArrayList<>();
        cmd.add(exePath.toString());
        if (args.length > 1) {
            cmd.addAll(Arrays.asList(args).subList(1, args.length));
        }

        logger.info("→ Running external command: {}  (resolved via {})", cmd, cliFolder);

        //------------------------------------------------------------
        // 1) Launch
        //------------------------------------------------------------
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();

        //------------------------------------------------------------
        // 2) Prepare output capture
        //------------------------------------------------------------
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        BufferedReader outR = new BufferedReader(new InputStreamReader(process.getInputStream()));
        BufferedReader errR = new BufferedReader(new InputStreamReader(process.getErrorStream()));

        //------------------------------------------------------------
        // 3) Optional tiles done progress-bar ---------------------
        //------------------------------------------------------------
        AtomicInteger tifCounter = new AtomicInteger();
        UIFunctions.ProgressHandle progressHandle = null;
        Pattern   tilesPat   = tilesDoneRegex == null ? null : Pattern.compile(tilesDoneRegex);
        int       totalTifs  = 0;
        if (tilesPat != null) {
            totalTifs = MinorFunctions.countTifEntriesInTileConfig(List.of(args));
            if (totalTifs > 0)
                progressHandle = UIFunctions.showProgressBarAsync(tifCounter, totalTifs, process, 20000);
        }

        //------------------------------------------------------------
        // 4) Consume stdout / stderr on background threads
        //------------------------------------------------------------
        Thread tOut = new Thread(() -> {
            try {
                String line;
                while ((line = outR.readLine()) != null) {
                    out.append(line).append('\n');
                    if (tilesPat != null && tilesPat.matcher(line).find())
                        tifCounter.incrementAndGet();
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

        //------------------------------------------------------------
        // 5) Wait (with optional timeout)
        //------------------------------------------------------------
        boolean timedOut = false;
        if (timeoutSec > 0) {
            timedOut = !process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (timedOut) {
                process.destroyForcibly();
                err.append("Process killed after ").append(timeoutSec).append(" s timeout\n");
            }
        } else {
            process.waitFor();
        }

        tOut.join();
        tErr.join();
        if (progressHandle != null)
            progressHandle.close();          // closes bar if still visible

        return new ExecResult(process.exitValue(), timedOut, out, err);
    }


}
