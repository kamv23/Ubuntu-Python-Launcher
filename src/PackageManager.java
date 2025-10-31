import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * PackageManager
 * --------------
 * Cross-platform helper for:
 *  - Root/scripts/imports directory management
 *  - Python interpreter detection (pref -> system python3/python -> optional portable)
 *  - pip bootstrap on systems where ensurepip may be disabled (Ubuntu note)
 *  - Installing packages into the local "imports" folder via `python -m pip install --target`
 *  - Opening folders (Desktop API with xdg-open fallback on Linux)
 *
 * This version is aligned with the Mac "gold" implementation but adds Linux niceties.
 */
public final class PackageManager {

    private static final Preferences PREFS = Preferences.userNodeForPackage(PackageManager.class);

    private static final String PREF_ROOT_DIR   = "launcher.rootDir";
    private static final String PREF_PYTHON_EXE = "launcher.pythonExec";

    private static final String DEFAULT_ROOT_NAME   = "Python-Launcher";
    private static final String SCRIPTS_DIR_NAME    = "scripts";
    private static final String IMPORTS_DIR_NAME    = "imports";
    private static final String PORTABLE_DIR_NAME   = "portable-python"; // optional, if you choose to ship one

    private PackageManager() {}

    /* =========================
     * OS / Platform helpers
     * ========================= */
    public static boolean isMac() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }
    public static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
    }
    public static boolean isArm64() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64") || arch.contains("armv8");
    }
    /** Used if you distribute a portable CPython tarball by triplet */
    public static String platformTriplet() {
        if (isMac())   return isArm64() ? "aarch64-apple-darwin"      : "x86_64-apple-darwin";
        if (isLinux()) return isArm64() ? "aarch64-unknown-linux-gnu" : "x86_64-unknown-linux-gnu";
        throw new UnsupportedOperationException("Unsupported OS: " + System.getProperty("os.name"));
    }

    /* =========================
     * Root & directory layout
     * ========================= */
    public static Path getRootDir() {
        String saved = PREFS.get(PREF_ROOT_DIR, "");
        Path root;
        if (saved == null || saved.isEmpty()) {
            // Default to ~/Python-Launcher (works on Mac & Linux)
            root = Paths.get(System.getProperty("user.home"), DEFAULT_ROOT_NAME);
            PREFS.put(PREF_ROOT_DIR, root.toString());
        } else {
            root = Paths.get(saved);
        }
        return root;
    }

    public static void setRootDir(Path newRoot) {
        Objects.requireNonNull(newRoot, "newRoot");
        PREFS.put(PREF_ROOT_DIR, newRoot.toString());
    }

    public static Path getScriptsDir() {
        return getRootDir().resolve(SCRIPTS_DIR_NAME);
    }

    public static Path getImportsDir() {
        return getRootDir().resolve(IMPORTS_DIR_NAME);
    }

    /** Create root/scripts/imports if missing */
    public static void ensureScaffold(Consumer<String> log) throws IOException {
        Path root    = getRootDir();
        Path scripts = getScriptsDir();
        Path imports = getImportsDir();

        Files.createDirectories(root);
        Files.createDirectories(scripts);
        Files.createDirectories(imports);

        if (log != null) {
            log.accept("[setup] Root: " + root);
            log.accept("[setup] Scripts: " + scripts);
            log.accept("[setup] Imports: " + imports);
        }
    }

    /* =========================
     * Python detection & config
     * ========================= */
    public static void setPythonExec(String pythonExec) {
        if (pythonExec == null) pythonExec = "";
        PREFS.put(PREF_PYTHON_EXE, pythonExec);
    }

    public static String getPythonExec() {
        // Preference wins if set
        String pref = PREFS.get(PREF_PYTHON_EXE, "").trim();
        if (!pref.isEmpty() && commandWorks(pref, "--version")) return pref;

        // Try portable under root (if you ship one)
        String portable = findPortablePython();
        if (portable != null && commandWorks(portable, "--version")) {
            setPythonExec(portable);
            return portable;
        }

        // Try system python3, then python
        if (commandWorks("python3", "--version")) {
            setPythonExec("python3");
            return "python3";
        }
        if (commandWorks("python", "--version")) {
            setPythonExec("python");
            return "python";
        }

        // Give up, but keep empty (caller may prompt user)
        return "";
    }

    /**
     * Attempt to locate a portable python inside:
     *   <root>/portable-python/<triplet>/bin/python3 (Linux/macOS layouts)
     */
    private static String findPortablePython() {
        Path base = getRootDir().resolve(PORTABLE_DIR_NAME).resolve(platformTriplet());
        Path bin1 = base.resolve("bin").resolve("python3");
        Path bin2 = base.resolve("bin").resolve("python");
        if (Files.isExecutable(bin1)) return bin1.toString();
        if (Files.isExecutable(bin2)) return bin2.toString();
        return null;
    }

    /* =========================
     * pip bootstrap & installs
     * ========================= */
    public static void ensurePipInstalled(String pythonExec, Consumer<String> log) throws IOException, InterruptedException {
        Objects.requireNonNull(pythonExec, "pythonExec");

        if (hasPip(pythonExec)) {
            if (log != null) log.accept("[pip] Found.");
            return;
        }

        // Try ensurepip first (works on macOS; sometimes disabled on Ubuntu/Debian)
        if (log != null) log.accept("[pip] Attempting: python -m ensurepip --upgrade");
        int rc = runLogged(log, getRootDir(), pythonExec, "-m", "ensurepip", "--upgrade");
        if (rc == 0 && hasPip(pythonExec)) {
            if (log != null) log.accept("[pip] Installed via ensurepip.");
            return;
        }

        // If still missing on Linux, advise apt-get
        if (isLinux()) {
            if (log != null) {
                log.accept("[pip] Not available. On Ubuntu/Debian, install pip with:");
                log.accept("      sudo apt-get update && sudo apt-get install -y python3-pip");
            }
            // After user installs system pip, our next run will see it.
        } else {
            if (log != null) log.accept("[pip] ensurepip failed and pip still missing.");
        }
    }

    public static boolean hasPip(String pythonExec) throws IOException, InterruptedException {
        return runSilently(pythonExec, "-m", "pip", "--version") == 0;
    }

    /**
     * Install packages into the local imports directory:
     *  python -m pip install --upgrade pip
     *  python -m pip install --target <imports> <pkg...>
     */
    public static void installPackages(String pythonExec, Collection<String> packages, Consumer<String> log)
            throws IOException, InterruptedException {

        if (packages == null || packages.isEmpty()) {
            if (log != null) log.accept("[pip] No packages specified.");
            return;
        }
        ensureScaffold(log);
        ensurePipInstalled(pythonExec, log);

        Path imports = getImportsDir();

        if (log != null) log.accept("[pip] Upgrading pip…");
        runLogged(log, getRootDir(), pythonExec, "-m", "pip", "install", "--upgrade", "pip");

        List<String> cmd = new ArrayList<>();
        cmd.add(pythonExec);
        cmd.add("-m");
        cmd.add("pip");
        cmd.add("install");
        cmd.add("--no-warn-script-location");
        cmd.add("--target");
        cmd.add(imports.toString());
        cmd.addAll(packages);

        if (log != null) log.accept("[pip] Installing into: " + imports);
        int rc = runLogged(log, getRootDir(), cmd.toArray(new String[0]));
        if (log != null) {
            log.accept(rc == 0 ? "[pip] Install complete." : "[pip] Install failed with code " + rc);
        }
    }

    /* =========================
     * Open folders (Desktop/xdg)
     * ========================= */
    public static void openDir(Path dir, Consumer<String> log) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
                return;
            }
        } catch (Exception ignored) {
            // Fall through to xdg-open
        }
        if (isLinux()) {
            try {
                new ProcessBuilder("xdg-open", dir.toString())
                        .redirectErrorStream(true)
                        .start();
                return;
            } catch (IOException ioe) {
                if (log != null) log.accept("[open] xdg-open failed: " + ioe.getMessage());
            }
        }
        if (log != null) log.accept("[open] Opening folder not supported on this environment.");
    }

    /* =========================
     * Environment for runners
     * ========================= */
    /** Build a base env map for Python processes, setting PYTHONPATH to imports dir. */
    public static Map<String, String> buildPythonEnv(Map<String, String> extras) throws IOException {
        ensureScaffold(null);
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("PYTHONPATH", getImportsDir().toString());
        // Nice defaults for numerical libs to avoid oversubscribing CPUs
        env.putIfAbsent("OMP_NUM_THREADS", "1");
        env.putIfAbsent("OPENBLAS_NUM_THREADS", "1");
        env.putIfAbsent("MKL_NUM_THREADS", "1");
        if (extras != null) env.putAll(extras);
        return env;
    }

    /* =========================
     * Process helpers
     * ========================= */
    public static boolean commandWorks(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (br.readLine() != null) { /* drain */ }
            }
            int rc = p.waitFor();
            return rc == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int runSilently(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            while (br.readLine() != null) { /* drain */ }
        }
        return p.waitFor();
    }

    private static int runLogged(Consumer<String> log, Path workingDir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workingDir != null) pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (log != null) log.accept(line);
            }
        }
        return p.waitFor();
    }

    /* =========================
     * Utility / public helpers
     * ========================= */
    public static void logEnvDiagnostics(Consumer<String> log) {
        if (log == null) return;
        log.accept("[env] OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        log.accept("[env] Arch: " + System.getProperty("os.arch"));
        log.accept("[env] Root: " + getRootDir());
        log.accept("[env] Scripts: " + getScriptsDir());
        log.accept("[env] Imports: " + getImportsDir());
        String py = getPythonExec();
        log.accept("[env] Python: " + (py.isEmpty() ? "(not found)" : py));
    }
}