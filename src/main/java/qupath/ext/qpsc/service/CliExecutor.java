package qupath.ext.qpsc.service;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MinorFunctions;


public class CliExecutor {
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
     * Run the microscope CLI with a timeout, capture its stdout, and
     * throw if it fails or times out.
     *
     * @param timeoutSec  Number of seconds to wait before forcibly killing.
     *                    Use 0 for wait forever.
     * @param args        All arguments that go **after** your run-command

     * @return The full stdout (trimmed) if exit==0.
     * @throws IOException          on I/O error or non-zero exit.
     * @throws InterruptedException if the waiter is interrupted.
     */
    public static String execCommandAndGetOutput(int timeoutSec, String... args)
            throws IOException, InterruptedException {

        // 1) Build the full command
        List<String> cmd = new ArrayList<>();

        cmd.addAll(Arrays.asList(args));

        // 2) Start the process
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process p = pb.start();

        // 3) Wait with timeout
        boolean finished;
        if (timeoutSec > 0) {
            finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("Command timed out after " + timeoutSec + " s");
            }
        } else {
            p.waitFor();
        }

        // 4) Read stdout and stderr
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        // 5) Check exit code
        int exit = p.exitValue();
        if (exit != 0) {
            throw new IOException("Command " + cmd + " failed (exit " + exit + "):\n" + stderr.trim());
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
        List<String> cmd = new ArrayList<>();
        cmd.addAll(List.of(args));

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
