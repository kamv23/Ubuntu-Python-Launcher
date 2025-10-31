import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * ScriptScanner
 * -------------
 * Cross‑platform watcher that:
 *  1) Monitors the scripts folder for *.py / *.pyw additions, removals, and renames
 *  2) Periodically scans running OS processes to flip toggles if scripts were
 *     started/stopped externally (outside the app)
 *
 * Ubuntu‑friendly (matches Mac “gold” behavior):
 *  • Linux: uses /proc/{pid}/cmdline and /proc/{pid}/cwd for precise argument + CWD resolution
 *  • macOS/Unix: ps -eo pid,args fallback
 *  • Windows: WMIC first, PowerShell CIM fallback
 *  • Debounced folder change notifications to avoid UI spam
 */
public class ScriptScanner {

    // -------- Listener --------
    public interface Listener {
        /** Called when the list/order of .py scripts in the folder changes. */
        void onScriptsListChanged(List<Path> scripts);
        /** Called when a script's running state changes. pid == -1 if not running. */
        void onScriptRunningState(Path script, boolean running, long pid);
    }

    // -------- Config / State --------
    private final Path scriptsDir;                 // normalized absolute path
    private final Set<String> pythonNames;         // e.g. python3, python
    private final Listener listener;               // UI callback sink
    private final Consumer<String> log;            // lightweight log sink (optional)

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            2, r -> { Thread t = new Thread(r, "script-scanner"); t.setDaemon(true); return t; });

    private volatile boolean started;
    private WatchService watchService;
    private Future<?> watchTask;
    private ScheduledFuture<?> psTask;

    // Known scripts in folder and mapping of script -> pid
    private final Set<Path> knownScripts = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Path, Long> runningPids = new ConcurrentHashMap<>();

    // Debounce for folder change bursts
    private final long debounceMs = 150L;
    private volatile long lastFolderNotify = 0L;

    // -------- Construction --------
    /**
     * @param scriptsDir directory containing .py scripts
     * @param pythonCandidates names to look for in process list (e.g., python3, python)
     * @param listener callback sink for UI updates
     * @param logSink receives lightweight log lines (optional)
     */
    public ScriptScanner(Path scriptsDir, Collection<String> pythonCandidates,
                         Listener listener, Consumer<String> logSink) {
        this.scriptsDir = Objects.requireNonNull(scriptsDir).toAbsolutePath().normalize();
        this.listener = Objects.requireNonNull(listener);
        this.pythonNames = new HashSet<>();
        if (pythonCandidates != null) this.pythonNames.addAll(pythonCandidates);
        if (this.pythonNames.isEmpty()) {
            this.pythonNames.add("python3");
            this.pythonNames.add("python");
        }
        this.log = (logSink != null) ? logSink : s -> {};
    }

    // -------- Lifecycle --------
    /** Start watching the folder and scanning OS processes. */
    public synchronized void start() throws IOException {
        if (started) return;
        started = true;

        Files.createDirectories(scriptsDir);
        snapshotScripts(true);

        // Folder watch
        watchService = FileSystems.getDefault().newWatchService();
        scriptsDir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);

        watchTask = scheduler.submit(this::watchLoop);

        // Process scan every 2 seconds
        psTask = scheduler.scheduleWithFixedDelay(this::scanProcessesSafe, 0, 2, TimeUnit.SECONDS);
        log.accept(ts() + "[scanner] started");
    }

    /** Stop all background tasks. */
    public synchronized void stop() {
        if (!started) return;
        started = false;
        if (psTask != null) psTask.cancel(true);
        if (watchTask != null) watchTask.cancel(true);
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
        scheduler.shutdownNow();
        log.accept(ts() + "[scanner] stopped");
    }

    /** Force an immediate scripts refresh and process rescan. */
    public void forceRescanNow() {
        snapshotScripts(true);
        scanProcessesSafe();
    }

    /** Immutable snapshot of known scripts. */
    public List<Path> getKnownScripts() { return List.copyOf(knownScripts); }

    // -------- Internal: folder watch --------
    private void watchLoop() {
        while (started) {
            try {
                WatchKey key = watchService.take(); // blocks
                boolean changed = false;
                for (WatchEvent<?> ev : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = ev.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                    Path rel = (Path) ev.context();
                    if (rel == null) continue;
                    String n = rel.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".py") || n.endsWith(".pyw")) changed = true;
                }
                if (changed) {
                    long now = System.currentTimeMillis();
                    if (now - lastFolderNotify > debounceMs) {
                        lastFolderNotify = now;
                        snapshotScripts(true);
                        scanProcessesSafe();
                    }
                }
                if (!key.reset()) break;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException cwse) {
                break;
            } catch (Exception ex) {
                log.accept(ts() + "[scanner] watch error: " + ex.getMessage());
            }
        }
    }

    private void snapshotScripts(boolean notify) {
        Set<Path> fresh = new HashSet<>();
        // Try glob for *.py and *.pyw; fall back to *.py if brace expansion unsupported
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(scriptsDir, "*.{py,pyw}")) {
            for (Path p : ds) if (Files.isRegularFile(p)) fresh.add(p.toAbsolutePath().normalize());
        } catch (Exception ignore) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(scriptsDir, "*.py")) {
                for (Path p : ds) if (Files.isRegularFile(p)) fresh.add(p.toAbsolutePath().normalize());
            } catch (IOException ioe2) {
                log.accept(ts() + "[scanner] list error: " + ioe2.getMessage());
            }
        }

        if (!fresh.equals(knownScripts)) {
            knownScripts.clear();
            knownScripts.addAll(fresh);
            if (notify) {
                List<Path> list = new ArrayList<>(knownScripts);
                list.sort(Comparator.comparing(a -> a.getFileName().toString().toLowerCase(Locale.ROOT)));
                listener.onScriptsListChanged(list);
            }
        }
    }

    // -------- Internal: process scan --------
    private void scanProcessesSafe() {
        try { scanProcesses(); }
        catch (Exception ex) { log.accept(ts() + "[scanner] ps error: " + ex.getMessage()); }
    }

    /** Detect which known scripts are running and notify diffs. */
    private void scanProcesses() throws IOException, InterruptedException {
        if (knownScripts.isEmpty()) return;

        final Map<String, Path> indexByAbs = new HashMap<>();
        for (Path s : knownScripts) indexByAbs.put(s.toString(), s);

        ConcurrentMap<Path, Long> foundNow;
        if (isLinux())       foundNow = scanLinuxProcfs(indexByAbs);
        else if (isMac())    foundNow = scanUnixPs(indexByAbs);
        else                 foundNow = scanWindows(indexByAbs);

        diffAndNotify(foundNow);
    }

    // ----- Linux: /proc for precise args and cwd -----
    private ConcurrentMap<Path, Long> scanLinuxProcfs(Map<String, Path> indexByAbs) {
        ConcurrentMap<Path, Long> foundNow = new ConcurrentHashMap<>();
        Path proc = Paths.get("/proc");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(proc)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (!name.chars().allMatch(Character::isDigit)) continue;
                long pid; try { pid = Long.parseLong(name); } catch (NumberFormatException ignore) { continue; }

                List<String> args = readProcCmdline(pid);
                if (args.isEmpty()) continue;
                if (!looksLikePython(args.get(0))) continue; // quick python filter

                Path cwd = readProcCwd(pid);
                Path candidate = extractScriptCandidate(args, cwd);
                if (candidate == null) continue;
                Path abs = toAbsoluteNormalized(candidate, cwd);
                Path match = indexByAbs.get(abs.toString());
                if (match != null) foundNow.put(match, pid);
            }
        } catch (IOException ignored) {
            // Fallback to ps on failure
            try { foundNow.putAll(scanUnixPs(indexByAbs)); }
            catch (Exception e) { log.accept(ts() + "[scanner] linux fallback ps failed: " + e.getMessage()); }
        }
        return foundNow;
    }

    private static List<String> readProcCmdline(long pid) {
        Path cmd = Paths.get("/proc", Long.toString(pid), "cmdline");
        try {
            byte[] bytes = Files.readAllBytes(cmd);
            if (bytes.length == 0) return List.of();
            List<String> parts = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == 0) {
                    parts.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                    start = i + 1;
                }
            }
            if (start < bytes.length) parts.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
            return parts;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Path readProcCwd(long pid) {
        try {
            Path link = Paths.get("/proc", Long.toString(pid), "cwd");
            return Files.readSymbolicLink(link);
        } catch (Exception e) {
            return null;
        }
    }

    // ----- macOS / other Unix via ps -----
    private ConcurrentMap<Path, Long> scanUnixPs(Map<String, Path> indexByAbs) throws IOException, InterruptedException {
        ConcurrentMap<Path, Long> foundNow = new ConcurrentHashMap<>();
        Process p = new ProcessBuilder("ps", "-eo", "pid,args").start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String trimmed = line.strip();
                if (trimmed.regionMatches(true, 0, "pid", 0, 3)) continue; // header

                long pid = parseLeadingPid(trimmed);
                if (pid <= 0) continue;
                String argsStr = trimmed.substring(Integer.toString((int)pid).length()).trim();

                String lower = argsStr.toLowerCase(Locale.ROOT);
                boolean containsPy = false;
                for (String n : pythonNames) { if (lower.contains(n.toLowerCase(Locale.ROOT))) { containsPy = true; break; } }
                if (!containsPy) continue;

                Path candidate = parseScriptFromArgsString(argsStr);
                if (candidate == null) continue;
                Path abs = candidate.isAbsolute() ? candidate.normalize() : scriptsDir.resolve(candidate).normalize();
                Path match = indexByAbs.get(abs.toString());
                if (match != null) foundNow.put(match, pid);
            }
        }
        p.waitFor(1, TimeUnit.SECONDS);
        return foundNow;
    }

    private static Path parseScriptFromArgsString(String argsStr) {
        String[] parts = argsStr.split("\\s+");
        boolean expectModule = false;
        for (String t : parts) {
            if (t.equals("-c")) return null;   // inline code
            if (t.equals("-m")) { expectModule = true; continue; }
            if (t.startsWith("-")) continue;   // other flags
            if (expectModule) {
                expectModule = false;
                return Paths.get(t.replace('.', '/') + ".py");
            }
            if (t.toLowerCase(Locale.ROOT).endsWith(".py")) return Paths.get(t);
        }
        return null;
    }

    // ----- Windows (WMIC -> PowerShell fallback) -----
    private ConcurrentMap<Path, Long> scanWindows(Map<String, Path> indexByAbs) throws IOException, InterruptedException {
        ConcurrentMap<Path, Long> foundNow = new ConcurrentHashMap<>();
        boolean ok = tryWmic(indexByAbs, foundNow);
        if (!ok) tryPowershell(indexByAbs, foundNow);
        return foundNow;
    }

    private boolean tryWmic(Map<String, Path> indexByAbs, ConcurrentMap<Path, Long> foundNow) {
        try {
            Process p = new ProcessBuilder("wmic", "process", "get", "ProcessId,CommandLine")
                    .redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    boolean containsPy = false; for (String n : pythonNames) { if (lower.contains(n.toLowerCase(Locale.ROOT))) { containsPy = true; break; } }
                    if (!containsPy) continue;

                    Path candidate = parseScriptFromArgsString(line);
                    if (candidate == null) continue;
                    Path abs = candidate.isAbsolute() ? candidate.normalize() : scriptsDir.resolve(candidate).normalize();
                    Path match = indexByAbs.get(abs.toString());
                    if (match != null) {
                        long pid = parseTrailingPid(line);
                        if (pid > 0) foundNow.put(match, pid);
                    }
                }
            }
            p.waitFor(1, TimeUnit.SECONDS);
            return true;
        } catch (IOException ioe) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void tryPowershell(Map<String, Path> indexByAbs, ConcurrentMap<Path, Long> foundNow) {
        try {
            String cmd = "Get-CimInstance Win32_Process | Select-Object ProcessId,CommandLine";
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", cmd)
                    .redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String lower = line.toLowerCase(Locale.ROOT);
                    boolean containsPy = false; for (String n : pythonNames) { if (lower.contains(n.toLowerCase(Locale.ROOT))) { containsPy = true; break; } }
                    if (!containsPy) continue;

                    Path candidate = parseScriptFromArgsString(line);
                    if (candidate == null) continue;
                    Path abs = candidate.isAbsolute() ? candidate.normalize() : scriptsDir.resolve(candidate).normalize();
                    Path match = indexByAbs.get(abs.toString());
                    if (match != null) {
                        long pid = parseLeadingPid(line); if (pid <= 0) pid = parseTrailingPid(line);
                        if (pid > 0) foundNow.put(match, pid);
                    }
                }
            }
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (IOException ignored) {
            // give up
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ----- Diff/Notify -----
    private void diffAndNotify(ConcurrentMap<Path, Long> foundNow) {
        // Started
        for (Path script : knownScripts) {
            Long newPid = foundNow.get(script);
            Long oldPid = runningPids.get(script);
            if (newPid != null && !Objects.equals(newPid, oldPid)) {
                runningPids.put(script, newPid);
                listener.onScriptRunningState(script, true, newPid);
            }
        }
        // Stopped
        for (Path script : new HashSet<>(runningPids.keySet())) {
            if (!foundNow.containsKey(script)) {
                runningPids.remove(script);
                listener.onScriptRunningState(script, false, -1);
            }
        }
    }

    // ----- Helpers -----
    private static long parseLeadingPid(String psLine) {
        int i = 0, n = psLine.length();
        while (i < n && Character.isWhitespace(psLine.charAt(i))) i++;
        int j = i; while (j < n && Character.isDigit(psLine.charAt(j))) j++;
        if (j > i) { try { return Long.parseLong(psLine.substring(i, j)); } catch (NumberFormatException ignored) {} }
        return -1;
    }

    private static long parseTrailingPid(String line) {
        int i = line.length() - 1;
        while (i >= 0 && Character.isWhitespace(line.charAt(i))) i--;
        int j = i; while (i >= 0 && Character.isDigit(line.charAt(i))) i--;
        if (j >= 0 && j >= i + 1) {
            try { return Long.parseLong(line.substring(i + 1, j + 1).trim()); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private boolean looksLikePython(String exeOrToken) {
        String t = exeOrToken == null ? "" : exeOrToken.toLowerCase(Locale.ROOT);
        if (t.endsWith("/python") || t.endsWith("/python3")) return true;
        for (String n : pythonNames) {
            if (t.equals(n) || t.endsWith("/" + n) || t.contains(" " + n + " ")) return true;
        }
        return t.contains("python"); // accept python3.12 etc.
    }

    private Path extractScriptCandidate(List<String> args, Path cwd) {
        boolean expectModule = false;
        for (int i = 1; i < args.size(); i++) {
            String t = args.get(i);
            if (t == null || t.isBlank()) continue;
            if (t.equals("-c")) return null; // inline code
            if (t.equals("-m")) { expectModule = true; continue; }
            if (t.startsWith("-")) continue; // other flags

            if (expectModule) {
                expectModule = false;
                Path mod = scriptsDir.resolve(t.replace('.', '/') + ".py");
                if (Files.isRegularFile(mod)) return mod;
                continue;
            }
            if (t.toLowerCase(Locale.ROOT).endsWith(".py")) {
                return toAbsoluteNormalized(Paths.get(t), cwd);
            }
        }
        return null;
    }

    private static Path toAbsoluteNormalized(Path p, Path cwd) {
        if (p == null) return null;
        Path q = p;
        if (!q.isAbsolute() && cwd != null) q = cwd.resolve(q);
        if (!q.isAbsolute()) q = q.toAbsolutePath();
        return q.normalize();
    }

    private static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("nux");
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    private static String ts() { return "[" + Instant.now() + "] "; }
}