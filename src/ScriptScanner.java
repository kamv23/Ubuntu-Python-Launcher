import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * ScriptScanner
 * -------------
 * Watches the scripts directory for file changes AND periodically scans the OS
 * process list to detect Python processes that correspond to scripts in that dir.
 *
 * Ubuntu-optimized:
 *  • Uses /proc/{pid}/cmdline + /proc/{pid}/cwd (Linux) to avoid ps truncation.
 *  • Resolves relative script tokens (e.g., "script.py") against per-process CWD.
 *  • Recognizes `python -m module` when module resides under the scripts folder.
 * Cross-platform fallbacks are retained for macOS and Windows.
 */
public class ScriptScanner {

    public interface Listener {
        /** Called whenever the set/order of .py scripts in the folder changes. */
        void onScriptsListChanged(List<Path> scripts);

        /** Called when a script's running state changes. pid == -1 if not running. */
        void onScriptRunningState(Path script, boolean running, long pid);
    }

    private final Path scriptsDir;
    private final Set<String> pythonNames;
    private final Listener listener;
    private final Consumer<String> log;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "script-scanner");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean started = false;
    private WatchService watchService;
    private Future<?> watchTask;
    private Future<?> psTask;

    // cache of known scripts + last observed running ones
    private final Set<Path> knownScripts = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Path, Long> runningPids = new ConcurrentHashMap<>();

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

    /** Start watching the folder and scanning OS processes. */
    public synchronized void start() throws IOException {
        if (started) return;
        started = true;

        // Ensure directory exists and snapshot scripts immediately
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

    /** Force an immediate scripts rescan and process rescan. */
    public void forceRescanNow() {
        snapshotScripts(true);
        scanProcessesSafe();
    }

    /** Current known script list (immutable snapshot). */
    public List<Path> getKnownScripts() {
        return List.copyOf(knownScripts);
    }

    // ---------------- internals ----------------

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
                    if (n.endsWith(".py") || n.endsWith(".pyw")) {
                        changed = true;
                    }
                }
                if (changed) {
                    snapshotScripts(true);
                    // Also rescan processes right away to reflect new/removed scripts in toggles
                    scanProcessesSafe();
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
        // Try *.{py,pyw}; if the JVM's glob doesn't support brace lists, fall back to *.py
        Set<Path> fresh = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(scriptsDir, "*.{py,pyw}")) {
            for (Path p : ds) if (Files.isRegularFile(p)) fresh.add(p.toAbsolutePath().normalize());
        } catch (Exception ignore) { // include PatternSyntaxException, IOException
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
                list.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
                listener.onScriptsListChanged(list);
            }
        }
    }

    private void scanProcessesSafe() {
        try {
            scanProcesses();
        } catch (Exception ex) {
            log.accept(ts() + "[scanner] ps error: " + ex.getMessage());
        }
    }

    /**
     * Scan the OS process list and detect which known scripts are running.
     * Linux: /proc for accurate args + cwd resolution.
     * macOS/other Unix: `ps -eo pid,args` fallback.
     * Windows: WMIC first, PowerShell CIM fallback.
     */
    private void scanProcesses() throws IOException, InterruptedException {
        if (knownScripts.isEmpty()) return;

        // Build quick index by absolute normalized string to avoid O(N^2)
        final Map<String, Path> indexByAbs = new HashMap<>();
        for (Path s : knownScripts) indexByAbs.put(s.toString(), s);

        if (isLinux()) {
            ConcurrentMap<Path, Long> foundNow = scanLinuxProcfs(indexByAbs);
            diffAndNotify(foundNow);
            return;
        }

        if (isMac()) {
            ConcurrentMap<Path, Long> foundNow = scanUnixPs(indexByAbs);
            diffAndNotify(foundNow);
            return;
        }

        // Windows (WMIC / PowerShell)
        ConcurrentMap<Path, Long> foundWin = scanWindows(indexByAbs);
        diffAndNotify(foundWin);
    }

    // ----- Linux (/proc) -----

    private ConcurrentMap<Path, Long> scanLinuxProcfs(Map<String, Path> indexByAbs) {
        ConcurrentMap<Path, Long> foundNow = new ConcurrentHashMap<>();
        Path proc = Paths.get("/proc");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(proc)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (!name.chars().allMatch(Character::isDigit)) continue;
                long pid;
                try { pid = Long.parseLong(name); } catch (NumberFormatException ignore) { continue; }

                List<String> args = readProcCmdline(pid);
                if (args.isEmpty()) continue;

                // Quick python check
                if (!looksLikePython(args.get(0))) continue;

                Path cwd = readProcCwd(pid);

                // Try to extract script path or -m module
                Path candidate = extractScriptCandidate(args, cwd);
                if (candidate == null) continue;

                // Normalize and match against known scripts
                Path abs = toAbsoluteNormalized(candidate, cwd);
                Path match = indexByAbs.get(abs.toString());
                if (match != null) {
                    foundNow.put(match, pid);
                }
            }
        } catch (IOException ignored) {
            // Fall back to ps if /proc iteration fails
            try {
                ConcurrentMap<Path, Long> viaPs = scanUnixPs(indexByAbs);
                foundNow.putAll(viaPs);
            } catch (Exception e) {
                log.accept(ts() + "[scanner] linux fallback ps failed: " + e.getMessage());
            }
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
                    if (i > start) {
                        parts.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                    } else {
                        parts.add("");
                    }
                    start = i + 1;
                }
            }
            if (start < bytes.length) {
                parts.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
            }
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
                if (trimmed.regionMatches(true, 0, "pid", 0, 3)) continue; // skip header

                int sp = firstSpace(trimmed);
                if (sp <= 0) continue;
                long pid = parseLeadingPid(trimmed);
                String argsStr = trimmed.substring(sp + 1);

                // quick python filter
                String lower = argsStr.toLowerCase(Locale.ROOT);
                if (pythonNames.stream().noneMatch(n -> lower.contains(n.toLowerCase(Locale.ROOT)))) continue;

                // Attempt to find an absolute script path token
                Path candidate = parseScriptFromArgsString(argsStr);
                if (candidate == null) continue;

                Path abs = candidate.isAbsolute() ? candidate.normalize() : scriptsDir.resolve(candidate).normalize();
                Path match = indexByAbs.get(abs.toString());
                if (match != null) {
                    foundNow.put(match, pid);
                }
            }
        }
        p.waitFor(1, TimeUnit.SECONDS);
        return foundNow;
    }

    private static int firstSpace(String s) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) return i;
        }
        return -1;
    }

    private static Path parseScriptFromArgsString(String argsStr) {
        // cheap parse: look for first token ending with .py that isn't a -flag
        // also support "-m module" by mapping to module.py
        String[] parts = argsStr.split("\\s+");
        boolean expectModule = false;
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i];
            if (t.equals("-c")) return null; // inline code; no file to match
            if (t.equals("-m")) { expectModule = true; continue; }
            if (expectModule) {
                String mod = t;
                String rel = mod.replace('.', '/') + ".py";
                return Paths.get(rel);
            }
            if (!t.startsWith("-") && t.toLowerCase(Locale.ROOT).endsWith(".py")) {
                return Paths.get(t);
            }
        }
        return null;
    }

    // ----- Windows (WMIC -> PowerShell fallback) -----

    private ConcurrentMap<Path, Long> scanWindows(Map<String, Path> indexByAbs) throws IOException, InterruptedException {
        ConcurrentMap<Path, Long> foundNow = new ConcurrentHashMap<>();

        boolean ok = tryWmic(indexByAbs, foundNow);
        if (!ok) {
            tryPowershell(indexByAbs, foundNow);
        }
        return foundNow;
    }

    private boolean tryWmic(Map<String, Path> indexByAbs, ConcurrentMap<Path, Long> foundNow) {
        try {
            Process p = new ProcessBuilder("wmic", "process", "get", "ProcessId,CommandLine")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (pythonNames.stream().noneMatch(n -> lower.contains(n.toLowerCase(Locale.ROOT)))) continue;

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
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (pythonNames.stream().noneMatch(n -> lower.contains(n.toLowerCase(Locale.ROOT)))) continue;

                    Path candidate = parseScriptFromArgsString(line);
                    if (candidate == null) continue;

                    Path abs = candidate.isAbsolute() ? candidate.normalize() : scriptsDir.resolve(candidate).normalize();
                    Path match = indexByAbs.get(abs.toString());
                    if (match != null) {
                        long pid = parseLeadingPid(line);
                        if (pid <= 0) pid = parseTrailingPid(line);
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

    // ----- Common helpers -----

    private void diffAndNotify(ConcurrentMap<Path, Long> foundNow) {
        // Newly started
        for (Path script : knownScripts) {
            Long newPid = foundNow.get(script);
            Long oldPid = runningPids.get(script);
            if (newPid != null && (oldPid == null || !oldPid.equals(newPid))) {
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

    private static long parseLeadingPid(String psLine) {
        // format: "  1234 python3 -u /path/to/script.py ..." or "1234 <cmd>" on Windows PS helper
        int i = 0;
        int n = psLine.length();
        while (i < n && Character.isWhitespace(psLine.charAt(i))) i++;
        int j = i;
        while (j < n && Character.isDigit(psLine.charAt(j))) j++;
        if (j > i) {
            try { return Long.parseLong(psLine.substring(i, j)); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static long parseTrailingPid(String wmicOrPsLine) {
        // WMIC often prints "...  CommandLine   ProcessId"
        int i = wmicOrPsLine.length() - 1;
        while (i >= 0 && Character.isWhitespace(wmicOrPsLine.charAt(i))) i--;
        int j = i;
        while (i >= 0 && Character.isDigit(wmicOrPsLine.charAt(i))) i--;
        if (j >= 0 && j >= i + 1) {
            try { return Long.parseLong(wmicOrPsLine.substring(i + 1, j + 1).trim()); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private boolean looksLikePython(String exeOrToken) {
        String t = exeOrToken == null ? "" : exeOrToken.toLowerCase(Locale.ROOT);
        if (t.endsWith("/python") || t.endsWith("/python3")) return true;
        for (String n : pythonNames) {
            if (t.equals(n) || t.endsWith("/" + n) || t.contains(" " + n + " ")) return true;
        }
        // Accept tokens like python3.12
        return t.contains("python");
    }

    private Path extractScriptCandidate(List<String> args, Path cwd) {
        boolean expectModule = false;
        for (int i = 1; i < args.size(); i++) {
            String t = args.get(i);
            if (t == null || t.isBlank()) continue;
            if (t.equals("-c")) return null; // inline code, cannot map to a file
            if (t.equals("-m")) { expectModule = true; continue; }
            if (t.startsWith("-")) continue; // skip other flags

            if (expectModule) {
                String rel = t.replace('.', '/') + ".py";
                Path modPath = scriptsDir.resolve(rel);
                if (Files.isRegularFile(modPath)) return modPath;
                // not under scripts; ignore
                expectModule = false;
                continue;
            }

            if (t.toLowerCase(Locale.ROOT).endsWith(".py")) {
                Path p = Paths.get(t);
                return toAbsoluteNormalized(p, cwd);
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

    private static String ts() {
        return "[" + Instant.now() + "] ";
    }
}