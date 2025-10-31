

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ProcessRunner
 * -------------
 * Cross‑platform (Ubuntu 24+, macOS, Windows) helper to launch and stop Python scripts.
 *
 * Mac‑parity & Ubuntu niceties:
 *  • Unbuffered output (-u) with live log streaming
 *  • Interactive REPL launcher (ties into LauncherUI input bar)
 *  • New process group on Unix via `setsid` when available (clean teardown)
 *  • Graceful stop: SIGINT → TERM (+pkill children) → KILL (+pkill -KILL children)
 *  • PYTHONPATH prepends {root}/imports so local packages are resolved first
 */
public class ProcessRunner {

    private final Path scriptPath;      // absolute path to the script (synthetic for REPL)
    private final Path baseDir;         // base project folder
    private final Path importsDir;      // <root>/imports
    private final String pythonExe;     // resolved python executable

    private volatile Process process;
    private OutputStream stdin;
    private Thread outThread;
    private Thread errThread;
    private volatile boolean running;
    private volatile boolean stopping;

    private Consumer<String> logSink = s -> {};

    public ProcessRunner(Path scriptPath, String pythonExe, Path baseDir) {
        this.scriptPath = Objects.requireNonNull(scriptPath).toAbsolutePath();
        this.pythonExe = Objects.requireNonNull(pythonExe);
        this.baseDir = Objects.requireNonNull(baseDir).toAbsolutePath();
        this.importsDir = PackageManager.getImportsDir();
    }

    /**
     * Start the script if not already running.
     * @param sink receives combined stdout/stderr lines (already newline‑trimmed).
     * @throws IOException if the process fails to start.
     */
    public synchronized void start(Consumer<String> sink) throws IOException {
        if (running) return;
        this.logSink = (sink != null) ? sink : this.logSink;

        ArrayList<String> cmd = new ArrayList<>();
        if (isUnix() && hasCmd("setsid")) {
            // New session for the python process so children can be signalled/cleaned reliably.
            cmd.add("setsid");
        }
        cmd.add(pythonExe);
        cmd.add("-u"); // unbuffered stdout/stderr
        cmd.add(scriptPath.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(scriptPath.getParent().toFile());

        // --- Environment ---
        Map<String, String> env = pb.environment();
        env.putIfAbsent("PYTHONUNBUFFERED", "1");
        // prepend local imports dir to PYTHONPATH so our packages win
        String existing = env.getOrDefault("PYTHONPATH", "");
        String imports = importsDir.toString();
        if (existing.isBlank()) {
            env.put("PYTHONPATH", imports);
        } else if (!existing.contains(imports)) {
            env.put("PYTHONPATH", imports + pathSep() + existing);
        }

        // --- Start ---
        process = pb.start();
        stdin = process.getOutputStream();
        running = true;
        stopping = false;

        long pid = safePid(process);
        log("[run] " + scriptPath.getFileName() + " started (PID=" + pid + ")");

        // Stream pumps
        outThread = pumpStream(process.getInputStream(), "[out] ");
        errThread = pumpStream(process.getErrorStream(), "[err] ");

        // Watcher
        Thread watcher = new Thread(() -> {
            try {
                int code = process.waitFor();
                running = false;
                joinQuietly(outThread, 500);
                joinQuietly(errThread, 500);
                if (!stopping) log("[exit] " + scriptPath.getFileName() + " exited with code " + code);
                else log("[stop] " + scriptPath.getFileName() + " stopped (code " + code + ")");
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                Registry.remove(scriptPath);
            }
        }, "pr-watch-" + pid);
        watcher.setDaemon(true);
        watcher.start();
    }

    /** Start an interactive Python shell (REPL) in the scripts directory, with PYTHONPATH set. */
    public synchronized void startInteractiveShell(Consumer<String> sink) throws IOException {
        if (running) return;
        this.logSink = (sink != null) ? sink : this.logSink;

        ArrayList<String> cmd = new ArrayList<>();
        if (isUnix() && hasCmd("setsid")) cmd.add("setsid");
        cmd.add(pythonExe);
        cmd.add("-i"); // interactive mode

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Path scriptsDir = baseDir.resolve("scripts");
        pb.directory(scriptsDir.toFile());

        Map<String, String> env = pb.environment();
        env.putIfAbsent("PYTHONUNBUFFERED", "1");
        String existing = env.getOrDefault("PYTHONPATH", "");
        String imports = importsDir.toString();
        if (existing.isBlank()) env.put("PYTHONPATH", imports);
        else if (!existing.contains(imports)) env.put("PYTHONPATH", imports + pathSep() + existing);

        process = pb.start();
        stdin = process.getOutputStream();
        running = true;
        stopping = false;

        long pid = safePid(process);
        log("[repl] python shell started (PID=" + pid + ")");

        outThread = pumpStream(process.getInputStream(), "[out] ");
        errThread = pumpStream(process.getErrorStream(), "[err] ");

        Thread watcher = new Thread(() -> {
            try {
                int code = process.waitFor();
                running = false;
                joinQuietly(outThread, 500);
                joinQuietly(errThread, 500);
                if (!stopping) log("[exit] python shell exited with code " + code);
                else log("[stop] python shell stopped (code " + code + ")");
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                Registry.remove(scriptPath);
            }
        }, "pr-watch-repl-" + pid);
        watcher.setDaemon(true);
        watcher.start();
    }

    /** Politely send Ctrl‑C (SIGINT on Unix) to the running process. */
    public synchronized void sendCtrlC() {
        if (!isRunning()) return;
        long pid = safePid(process);
        if (isUnix()) {
            try {
                new ProcessBuilder("kill", "-INT", Long.toString(pid))
                        .start()
                        .waitFor(500, TimeUnit.MILLISECONDS);
                log("[stop] Sent SIGINT to PID " + pid);
            } catch (Exception ignored) {}
        } else {
            // On Windows, best‑effort via stdin (ETX)
            sendLine("\u0003");
        }
    }

    /**
     * Gracefully stop the process: SIGINT → TERM (+pkill children) → KILL.
     * @param graceMillis total time to allow for a polite shutdown before escalation.
     */
    public synchronized void stopGracefully(long graceMillis) {
        if (!running || process == null) return;
        stopping = true;
        long pid = safePid(process);

        // Phase 1: SIGINT (polite)
        if (isUnix()) {
            try {
                new ProcessBuilder("kill", "-INT", Long.toString(pid))
                        .start()
                        .waitFor(300, TimeUnit.MILLISECONDS);
                log("[stop] Sent SIGINT to PID " + pid);
            } catch (Exception ignored) {}
        } else {
            sendLine("\u0003"); // Ctrl‑C for Windows consoles
        }
        if (waitFor(process, Math.max(300, graceMillis / 3))) { closeStdinQuietly(); return; }

        // Phase 2: TERM parent and children
        log("[stop] Sending TERM to PID " + pid + " …");
        try { process.destroy(); } catch (Exception ignored) {}
        if (isUnix()) bestEffortKillChildren(pid, "-TERM");
        if (waitFor(process, Math.max(500, graceMillis / 3))) { closeStdinQuietly(); return; }

        // Phase 3: KILL parent and remaining children
        log("[stop] Forcing kill of PID " + pid + " …");
        try { process.destroyForcibly(); } catch (Exception ignored) {}
        if (isUnix()) bestEffortKillChildren(pid, "-KILL");
        waitFor(process, 2_000);
        closeStdinQuietly();
    }

    public boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    /** Send a single line to the running process's stdin (adds a newline and flushes). */
    public synchronized void sendLine(String text) {
        if (process == null || !isRunning()) return;
        try {
            if (stdin == null) stdin = process.getOutputStream();
            String s = (text == null ? "" : text) + System.lineSeparator();
            stdin.write(s.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException ignored) {}
    }

    public long getPid() { return safePid(process); }

    // ---------- Internals ----------

    private Thread pumpStream(InputStream in, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log(prefix + line);
                }
            } catch (IOException ioe) {
                if (running) log("[warn] stream pump error: " + ioe.getMessage());
            }
        }, "pr-pump-" + scriptPath.getFileName() + "-" + prefix.trim());
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void joinQuietly(Thread t, long millis) {
        if (t == null) return;
        try { t.join(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private boolean waitFor(Process p, long millis) {
        try { return p.waitFor(millis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    private void log(String s) { try { logSink.accept(s); } catch (Throwable ignored) {} }

    private static boolean isUnix() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("nix") || os.contains("nux") || os.contains("mac") || os.contains("darwin");
    }

    private static String pathSep() { return System.getProperty("path.separator"); }

    private static long safePid(Process p) { try { return (p == null) ? -1L : p.pid(); } catch (Throwable t) { return -1L; } }

    private static boolean hasCmd(String name) {
        try {
            Process p = new ProcessBuilder("which", name).start();
            p.waitFor(300, TimeUnit.MILLISECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void bestEffortKillChildren(long pid, String signal) {
        try {
            new ProcessBuilder("pkill", signal, "-P", Long.toString(pid))
                    .start()
                    .waitFor(500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}
    }

    private void closeStdinQuietly() {
        try {
            if (stdin != null) {
                stdin.close();
                stdin = null;
            }
        } catch (Exception ignored) {}
    }

    // ---------- Simple global registry keyed by script path ----------

    public static class Registry {
        private static final Map<Path, ProcessRunner> RUNNERS = new ConcurrentHashMap<>();

        public static synchronized ProcessRunner getOrCreate(Path scriptPath, String pythonExe, Path baseDir) {
            Path k = scriptPath.toAbsolutePath();
            return RUNNERS.computeIfAbsent(k, __ -> new ProcessRunner(k, pythonExe, baseDir));
        }

        public static synchronized ProcessRunner get(Path scriptPath) {
            return RUNNERS.get(scriptPath.toAbsolutePath());
        }

        public static synchronized void remove(Path scriptPath) {
            RUNNERS.remove(scriptPath.toAbsolutePath());
        }

        public static synchronized void stopAll(long graceMillis) {
            for (ProcessRunner pr : RUNNERS.values()) {
                try { pr.stopGracefully(graceMillis); } catch (Throwable ignored) {}
            }
            RUNNERS.clear();
        }
    }

    // ---------- CLI manual test ----------
    // Example: java ProcessRunner /path/to/script.py python3 /base/dir
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java ProcessRunner <script.py> <pythonExe> <baseDir>");
            return;
        }
        Path script = Paths.get(args[0]);
        String py = args[1];
        Path base = Paths.get(args[2]);
        ProcessRunner pr = Registry.getOrCreate(script, py, base);
        pr.start(System.out::println);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> pr.stopGracefully(3_000)));
    }
}