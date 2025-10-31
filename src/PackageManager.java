
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * PackageManager
 * --------------
 * Minimal cross‑platform package manager that installs Python packages
 * into {baseDir}/python/imports using:
 *
 *     python -m pip install -t <importsDir> <pkgSpec>
 *
 * Ubuntu 24+ notes:
 *  • Some Ubuntu builds ship Python without pip by default. We now attempt
 *    to bootstrap pip with `python -m ensurepip --upgrade` when needed.
 *  • We set both PYTHONPATH and PIP_TARGET to <importsDir> during installs.
 *  • Uninstall is best‑effort by deleting folders/files from <importsDir>.
 */
public class PackageManager {

    public static class PkgInfo {
        public final String name;
        public final String version;

        public PkgInfo(String name, String version) {
            this.name = name;
            this.version = version;
        }

        @Override public String toString() { return name + "==" + version; }
    }

    private final Path baseDir;
    private final Path importsDir;
    private final String pythonExe;
    private final Consumer<String> log;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pkg-manager");
        t.setDaemon(true);
        return t;
    });

    public PackageManager(Path baseDir, String pythonExe, Consumer<String> logSink) {
        this.baseDir = Objects.requireNonNull(baseDir).toAbsolutePath();
        this.pythonExe = Objects.requireNonNull(pythonExe);
        this.importsDir = this.baseDir.resolve("python").resolve("imports");
        this.log = (logSink != null) ? logSink : s -> {};
    }

    /** Ensure imports directory exists. */
    public void ensureImportsDir() throws IOException {
        Files.createDirectories(importsDir);
    }

    /** Returns python --version string or "unknown". */
    public String getPythonVersion() {
        return readFirstLineSafe(new ProcessBuilder(pythonExe, "--version"));
    }

    /** Returns pip --version string or "unknown". */
    public String getPipVersion() {
        return readFirstLineSafe(new ProcessBuilder(pythonExe, "-m", "pip", "--version"));
    }

    /** Quick health check: python + pip usable (bootstraps pip if missing). */
    public boolean checkPythonAndPip() {
        String py = getPythonVersion();
        String pip = getPipVersion();
        boolean ok = (py != null && !py.isBlank()) && (pip != null && pip.toLowerCase(Locale.ROOT).contains("pip"));
        if (!ok) {
            log.accept("[pip] python/pip not healthy. python=" + py + " pip=" + pip);
            // Try to bootstrap pip using ensurepip (works when pip isn't present)
            try {
                int rc = runAndPipe(
                        new ProcessBuilder(pythonExe, "-m", "ensurepip", "--upgrade"),
                        /*expectedOk*/0
                );
                if (rc == 0) {
                    // re-check
                    pip = getPipVersion();
                    ok = (pip != null && pip.toLowerCase(Locale.ROOT).contains("pip"));
                }
            } catch (Exception e) {
                log.accept("[pip] ensurepip failed: " + e.getMessage());
            }
        }
        if (!ok) log.accept("[pip] python/pip still not healthy after ensurepip.");
        return ok;
    }

    /**
     * Install a package spec (e.g., "requests", "numpy==1.26.*", "git+https://...").
     * Runs: python -m pip install --upgrade --no-warn-script-location -t <importsDir> <spec> [extraArgs...]
     * @return Future with exit code (0 = success).
     */
    public Future<Integer> installAsync(String pkgSpec, List<String> extraArgs) {
        Objects.requireNonNull(pkgSpec);
        return exec.submit(() -> install(pkgSpec, extraArgs));
    }

    public int install(String pkgSpec, List<String> extraArgs) throws IOException, InterruptedException {
        ensureImportsDir();
        if (!checkPythonAndPip()) return -1;

        List<String> cmd = new ArrayList<>();
        cmd.add(pythonExe);
        cmd.add("-m");
        cmd.add("pip");
        cmd.add("install");
        cmd.add("--upgrade");
        cmd.add("--no-warn-script-location");
        cmd.add("-t");
        cmd.add(importsDir.toString());
        cmd.add(pkgSpec);
        if (extraArgs != null) cmd.addAll(extraArgs);

        log.accept("[pip] " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // Ensure build/import context hits our target directory
        addPythonPath(pb, importsDir);
        addPipTarget(pb, importsDir);

        return runAndPipe(pb, 0);
    }

    /** Install from a requirements.txt file. */
    public Future<Integer> installRequirementsAsync(Path requirementsFile, List<String> extraArgs) {
        Objects.requireNonNull(requirementsFile);
        return exec.submit(() -> installRequirements(requirementsFile, extraArgs));
    }

    public int installRequirements(Path requirementsFile, List<String> extraArgs) throws IOException, InterruptedException {
        ensureImportsDir();
        if (!Files.isRegularFile(requirementsFile)) {
            log.accept("[pip] requirements.txt not found: " + requirementsFile);
            return -1;
        }
        if (!checkPythonAndPip()) return -1;

        List<String> cmd = new ArrayList<>();
        cmd.add(pythonExe);
        cmd.add("-m");
        cmd.add("pip");
        cmd.add("install");
        cmd.add("--upgrade");
        cmd.add("--no-warn-script-location");
        cmd.add("-t");
        cmd.add(importsDir.toString());
        cmd.add("-r");
        cmd.add(requirementsFile.toAbsolutePath().toString());
        if (extraArgs != null) cmd.addAll(extraArgs);

        log.accept("[pip] " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        addPythonPath(pb, importsDir);
        addPipTarget(pb, importsDir);

        return runAndPipe(pb, 0);
    }

    /**
     * Best‑effort uninstall by removing top‑level package folders from importsDir.
     * It removes:
     *   - <name>/
     *   - <name>.py
     *   - <name>-*.dist-info/
     *   - <name>*.egg-info/
     */
    public Future<Boolean> uninstallAsync(String packageName) {
        Objects.requireNonNull(packageName);
        return exec.submit(() -> uninstall(packageName));
    }

    public boolean uninstall(String packageName) throws IOException {
        ensureImportsDir();
        String prefix = packageName.replace("-", "_").toLowerCase(Locale.ROOT); // normalize
        boolean changed = false;

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(importsDir)) {
            for (Path p : ds) {
                String fname = p.getFileName().toString();
                String lower = fname.toLowerCase(Locale.ROOT);
                if (Files.isDirectory(p)) {
                    if (lower.equals(prefix)
                            || lower.startsWith(prefix + "-")
                            || lower.endsWith(".dist-info")
                            || lower.endsWith(".egg-info")) {
                        changed |= deleteRecursively(p);
                    }
                } else {
                    if (lower.equals(prefix + ".py") || lower.startsWith(prefix + "-py")) {
                        changed |= deleteRecursively(p);
                    }
                }
            }
        }
        if (changed) log.accept("[pip] removed local files for " + packageName);
        return changed;
    }

    /** Remove __pycache__ and stray build artifacts under importsDir. */
    public Future<Integer> cleanAsync() {
        return exec.submit(() -> {
            ensureImportsDir();
            int count = 0;
            try {
                count += deleteByGlob("__pycache__");
                count += deleteByGlob("*.pyc");
                count += deleteByGlob("*.pyo");
            } catch (IOException e) {
                log.accept("[pip] clean error: " + e.getMessage());
            }
            log.accept("[pip] clean removed " + count + " items");
            return count;
        });
    }

    /** List packages installed into importsDir. Tries `pip list --path`, falls back to scanning. */
    public Future<List<PkgInfo>> listInstalledAsync() {
        return exec.submit(this::listInstalled);
    }

    public List<PkgInfo> listInstalled() {
        try {
            // Try pip list --path (pip 21+)
            List<PkgInfo> viaPip = listViaPip();
            if (!viaPip.isEmpty()) return viaPip;
        } catch (Exception ignored) {}

        // Fallback: derive from *.dist-info
        List<PkgInfo> fallback = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(importsDir, "*.dist-info")) {
            for (Path p : ds) {
                String name = p.getFileName().toString(); // package-1.2.3.dist-info
                int idx = name.lastIndexOf(".dist-info");
                String stem = (idx > 0) ? name.substring(0, idx) : name;
                // Split on last hyphen to separate version
                int lastDash = stem.lastIndexOf('-');
                if (lastDash > 0) {
                    fallback.add(new PkgInfo(stem.substring(0, lastDash), stem.substring(lastDash + 1)));
                } else {
                    fallback.add(new PkgInfo(stem, "?"));
                }
            }
        } catch (IOException ignored) {}
        // Sort by name
        fallback.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
        return fallback;
    }

    // ---------------- internals ----------------

    private List<PkgInfo> listViaPip() throws IOException, InterruptedException {
        List<String> cmd = List.of(pythonExe, "-m", "pip", "list", "--path", importsDir.toString(), "--format", "columns");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        addPythonPath(pb, importsDir);
        addPipTarget(pb, importsDir);
        Process p = pb.start();

        List<PkgInfo> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            boolean headerSkipped = false;
            while ((line = br.readLine()) != null) {
                if (!headerSkipped) { // skip header "Package Version"
                    headerSkipped = true;
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) out.add(new PkgInfo(parts[0], parts[1]));
            }
        }
        p.waitFor(3, TimeUnit.SECONDS);
        out.sort(Comparator.comparing(pi -> pi.name.toLowerCase(Locale.ROOT)));
        return out;
    }

    private String readFirstLineSafe(ProcessBuilder pb) {
        try {
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = br.readLine();
                p.waitFor(1, TimeUnit.SECONDS);
                return (line != null) ? line.trim() : "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void addPythonPath(ProcessBuilder pb, Path dir) {
        var env = pb.environment();
        String existing = env.getOrDefault("PYTHONPATH", "");
        if (existing.isBlank()) env.put("PYTHONPATH", dir.toString());
        else if (!existing.contains(dir.toString()))
            env.put("PYTHONPATH", dir + System.getProperty("path.separator") + existing);
    }

    private void addPipTarget(ProcessBuilder pb, Path dir) {
        var env = pb.environment();
        env.put("PIP_TARGET", dir.toString());
    }

    private int runAndPipe(ProcessBuilder pb, int expectedOk) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                log.accept("[pip] " + line);
            }
        }
        boolean ended = p.waitFor(600, TimeUnit.SECONDS);
        if (!ended) {
            p.destroyForcibly();
            log.accept("[pip] command timed out and was killed");
            return -1;
        }
        int code = p.exitValue();
        if (code == expectedOk) log.accept("[pip] done (code " + code + ")");
        else log.accept("[pip] failed (code " + code + ")");
        return code;
    }

    private int deleteByGlob(String pattern) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(importsDir, pattern)) {
            for (Path p : ds) {
                if (deleteRecursively(p)) count++;
            }
        }
        // Recurse subdirs for __pycache__
        if (pattern.equals("__pycache__")) {
            try (var walk = Files.walk(importsDir)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isDirectory(p) && p.getFileName().toString().equals("__pycache__")) {
                        if (deleteRecursively(p)) count++;
                    }
                }
            }
        }
        return count;
    }

    private boolean deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return false;
        if (Files.isDirectory(p)) {
            try (var walk = Files.walk(p)) {
                List<Path> toDelete = new ArrayList<>();
                for (Path q : (Iterable<Path>) walk::iterator) toDelete.add(q);
                Collections.reverse(toDelete); // delete children first
                boolean changed = false;
                for (Path q : toDelete) {
                    try {
                        Files.deleteIfExists(q);
                        changed = true;
                    } catch (IOException e) {
                        log.accept("[pip] rm fail: " + q + " (" + e.getMessage() + ")");
                    }
                }
                return changed;
            }
        } else {
            try {
                return Files.deleteIfExists(p);
            } catch (IOException e) {
                log.accept("[pip] rm fail: " + p + " (" + e.getMessage() + ")");
                return false;
            }
        }
    }

    /** Shutdown internal executor. Call on app exit. */
    public void shutdown() {
        exec.shutdownNow();
    }

    public Path getImportsDir() { return importsDir; }
    public Path getBaseDir() { return baseDir; }
    public String getPythonExe() { return pythonExe; }
}