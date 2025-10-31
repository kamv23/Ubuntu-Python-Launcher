import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Cross-platform Launcher UI (Ubuntu 24+, macOS, Windows).
 * Ubuntu-focused build: integrates ScriptScanner + interactive console input + package management.
 *
 * Features enabled:
 *  • Choose base folder; creates /python, /python/imports, /scripts
 *  • Scrollable list of .py scripts with iOS-style toggles (green=ON, red=OFF)
 *  • Starts/stops scripts using ProcessRunner with live console logs
 *  • Background ScriptScanner flips toggles if scripts are started/stopped externally
 *  • Text input to send lines to the active process
 *  • One-click interactive Python Shell (REPL) in scripts folder
 *  • Package management: Install packages or requirements.txt into python/imports
 *  • Clean shutdown of processes and scanner on window close
 */
public class LauncherUI {

    // --------- Constants ----------
    private static final String APP_NAME = "Python Launcher";
    private static final String CONFIG_FILE = ".python_launcher_config.properties";
    private static final String KEY_BASE_DIR = "baseDir";
    private static final String KEY_PYTHON_EXE = "pythonExe";

    // Folder names inside baseDir
    private static final String DIR_PYTHON = "python";
    private static final String DIR_IMPORTS = "imports";
    private static final String DIR_SCRIPTS = "scripts";

    // On Ubuntu, default Python is usually "python3"
    private static final String[] PYTHON_CANDIDATES = {"python3", "python"};

    // --------- State ----------
    private JFrame frame;
    private JLabel baseDirLabel;
    private JTextArea console;
    private JTextField consoleInput;
    private JLabel inputTargetLabel;
    private JToggleButton shellToggle;
    private JPanel scriptsPanel;     // holds rows of [filename] [toggle]
    private JScrollPane scriptsScroll;

    private Path baseDir;
    private Path scriptsDir;
    private String pythonExe;

    // Script -> ToggleSwitch
    private final Map<Path, ToggleSwitch> toggleByScript = new HashMap<>();

    // Background scanner and suppression for programmatic toggle updates
    private ScriptScanner scanner;
    private volatile boolean suppressToggle = false;

    // Where console input goes: last started script or active Python shell
    private ProcessRunner inputTarget;
    private ProcessRunner shellRunner; // managed by shellToggle

    // Package manager
    private PackageManager packageManager;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignore) {}

        SwingUtilities.invokeLater(() -> new LauncherUI().start());
    }

    private void start() {
        frame = new JFrame(APP_NAME);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setLocationByPlatform(true);

        frame.setLayout(new BorderLayout(0, 0));
        frame.add(buildTopBar(), BorderLayout.NORTH);
        frame.add(buildCenter(), BorderLayout.CENTER);
        frame.add(buildBottomConsole(), BorderLayout.SOUTH);

        // Persist base dir and init folders/UI
        loadOrChooseBaseDir();
        ensureProjectFolders();
        initPackageManager();
        refreshScriptsList();

        // Start background scanner
        startScanner();

        // Clean shutdown
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (scanner != null) scanner.stop();
                ProcessRunner.Registry.stopAll(3_000);
                if (packageManager != null) packageManager.shutdown();
            }
        });

        frame.setVisible(true);
    }

    // ========== UI Building ==========

    private JComponent buildTopBar() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Left: base dir label
        baseDirLabel = new JLabel("Base: — not selected —");
        baseDirLabel.setFont(baseDirLabel.getFont().deriveFont(Font.BOLD, 13f));
        top.add(baseDirLabel, BorderLayout.WEST);

        // Right: actions
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

        JButton btnChange = new JButton("Change Base Folder");
        btnChange.addActionListener(e -> {
            Path chosen = chooseFolderDialog(frame, baseDir);
            if (chosen != null) {
                baseDir = chosen;
                updateBaseDirLabel();
                saveConfig();
                ensureProjectFolders();
                initPackageManager();
                refreshScriptsList();
                log("[info] Base folder set to: " + baseDir);
            }
        });

        JButton btnOpenScripts = new JButton("Open Scripts Folder");
        btnOpenScripts.addActionListener(e -> {
            if (scriptsDir != null) {
                openFolder(scriptsDir);
            }
        });

        JButton btnRefresh = new JButton("Refresh");
        btnRefresh.addActionListener(e -> refreshScriptsList());

        JButton btnInstallPkg = new JButton("Install Packages…");
        btnInstallPkg.setToolTipText("Install Python packages into python/imports");
        btnInstallPkg.addActionListener(e -> showInstallPackagesDialog());

        JButton btnInstallReq = new JButton("Install Requirements…");
        btnInstallReq.setToolTipText("Install from requirements.txt into python/imports");
        btnInstallReq.addActionListener(e -> showInstallRequirementsDialog());

        JButton btnSettings = new JButton("Settings");
        btnSettings.addActionListener(e -> showSettingsDialog());

        right.add(btnChange);
        right.add(btnOpenScripts);
        right.add(btnRefresh);
        right.add(new JSeparator(SwingConstants.VERTICAL));
        right.add(btnInstallPkg);
        right.add(btnInstallReq);
        right.add(new JSeparator(SwingConstants.VERTICAL));
        right.add(btnSettings);

        top.add(right, BorderLayout.EAST);
        return top;
    }

    private JComponent buildCenter() {
        scriptsPanel = new JPanel();
        scriptsPanel.setLayout(new GridBagLayout());
        scriptsPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        scriptsScroll = new JScrollPane(scriptsPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scriptsScroll.getVerticalScrollBar().setUnitIncrement(16);

        return scriptsScroll;
    }

    private JComponent buildBottomConsole() {
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(new EmptyBorder(0, 10, 10, 10));

        // Log area
        console = new JTextArea(8, 20);
        console.setEditable(false);
        console.setLineWrap(true);
        console.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(console,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        bottom.add(scroll, BorderLayout.CENTER);

        // Input row
        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.setBorder(new EmptyBorder(8, 0, 0, 0));

        shellToggle = new JToggleButton("Python Shell");
        shellToggle.setToolTipText("Start/Stop an interactive Python REPL in the scripts folder");
        shellToggle.addActionListener(e -> {
            if (shellToggle.isSelected()) {
                startShell();
            } else {
                stopShell();
            }
        });

        inputTargetLabel = new JLabel("Input target: — none —");
        inputTargetLabel.setForeground(new Color(90, 90, 90));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(shellToggle);
        left.add(new JSeparator(SwingConstants.VERTICAL));

        consoleInput = new JTextField();
        consoleInput.setToolTipText("Type a line and press Enter to send to the active process");

        JButton sendBtn = new JButton("Send");
        sendBtn.addActionListener(e -> sendConsoleLine());

        consoleInput.addActionListener(e -> sendConsoleLine());

        JPanel right = new JPanel(new BorderLayout(6, 0));
        right.add(consoleInput, BorderLayout.CENTER);
        right.add(sendBtn, BorderLayout.EAST);

        JPanel southRow = new JPanel(new BorderLayout());
        southRow.add(left, BorderLayout.WEST);
        southRow.add(right, BorderLayout.CENTER);
        southRow.add(inputTargetLabel, BorderLayout.SOUTH);

        inputRow.add(southRow, BorderLayout.CENTER);

        bottom.add(inputRow, BorderLayout.SOUTH);
        return bottom;
    }

    // ========== Folder / Config ==========

    private void loadOrChooseBaseDir() {
        // Config lives next to the app (user home) so we're not using mac-only Library paths.
        Path home = Paths.get(System.getProperty("user.home"));
        Path cfgPath = home.resolve(CONFIG_FILE);
        Properties props = new Properties();
        if (Files.isRegularFile(cfgPath)) {
            try (var in = Files.newInputStream(cfgPath)) {
                props.load(in);
            } catch (Exception ignore) {}
        }
        String saved = props.getProperty(KEY_BASE_DIR, "").trim();
        if (!saved.isEmpty()) {
            Path candidate = Paths.get(saved);
            if (Files.isDirectory(candidate)) {
                baseDir = candidate;
            }
        }

        // Load python exe preference
        pythonExe = props.getProperty(KEY_PYTHON_EXE, "").trim();
        if (pythonExe.isEmpty()) {
            pythonExe = resolvePythonExe();
        }

        if (baseDir == null) {
            Path chosen = chooseFolderDialog(frame, null);
            if (chosen == null) {
                JOptionPane.showMessageDialog(frame,
                        "No base folder selected. Exiting.",
                        APP_NAME, JOptionPane.WARNING_MESSAGE);
                System.exit(0);
            }
            baseDir = chosen;
            saveConfig();
        }

        updateBaseDirLabel();
    }

    private void ensureProjectFolders() {
        try {
            Files.createDirectories(baseDir.resolve(DIR_PYTHON));
            Files.createDirectories(baseDir.resolve(DIR_PYTHON).resolve(DIR_IMPORTS));
            scriptsDir = baseDir.resolve(DIR_SCRIPTS);
            Files.createDirectories(scriptsDir);
        } catch (Exception ex) {
            showError("Failed to create project folders:\n" + ex.getMessage());
        }
    }

    private void saveConfig() {
        try {
            Properties props = new Properties();
            props.setProperty(KEY_BASE_DIR, baseDir.toAbsolutePath().toString());
            props.setProperty(KEY_PYTHON_EXE, pythonExe);
            Path cfg = Paths.get(System.getProperty("user.home")).resolve(CONFIG_FILE);
            try (var out = Files.newOutputStream(cfg)) {
                props.store(out, APP_NAME + " config");
            }
        } catch (Exception ex) {
            log("[warn] Failed to save config: " + ex.getMessage());
        }
    }

    private void updateBaseDirLabel() {
        String s = (baseDir == null) ? "— not selected —" : baseDir.toAbsolutePath().toString();
        baseDirLabel.setText("Base: " + s);
    }

    private void initPackageManager() {
        if (baseDir == null) return;
        try {
            packageManager = new PackageManager(baseDir, pythonExe, this::log);
            packageManager.ensureImportsDir();
        } catch (Exception ex) {
            log("[warn] Failed to initialize package manager: " + ex.getMessage());
        }
    }

    // ========== Scripts ==========

    private void refreshScriptsList() {
        toggleByScript.clear();
        scriptsPanel.removeAll();

        List<Path> scripts = listPyScripts();
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.insets = new Insets(6, 6, 6, 6);
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;

        if (scripts.isEmpty()) {
            JLabel hint = new JLabel("No .py scripts found. Add files to: " + scriptsDir);
            hint.setForeground(new Color(90, 90, 90));
            hint.setBorder(new EmptyBorder(8, 2, 8, 2));
            scriptsPanel.add(hint, gc);
            gc.gridy++;
        } else {
            for (Path p : scripts) {
                scriptsPanel.add(buildScriptRow(p), gc);
                gc.gridy++;
            }
        }

        // Spacer to push content to top in scroll
        gc.weighty = 1.0;
        gc.fill = GridBagConstraints.BOTH;
        scriptsPanel.add(Box.createVerticalGlue(), gc);

        scriptsPanel.revalidate();
        scriptsPanel.repaint();
    }

    private List<Path> listPyScripts() {
        if (scriptsDir == null) return List.of();
        try (var stream = Files.list(scriptsDir)) {
            return stream
                    .filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".py"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (Exception ex) {
            log("[error] Failed to list scripts: " + ex.getMessage());
            return List.of();
        }
    }

    private JComponent buildScriptRow(Path script) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 220, 220)),
                new EmptyBorder(8, 10, 8, 10)
        ));

        JLabel name = new JLabel(script.getFileName().toString());
        name.setFont(name.getFont().deriveFont(Font.PLAIN, 14f));

        JLabel path = new JLabel(script.toAbsolutePath().toString());
        path.setForeground(new Color(100, 100, 100));
        path.setFont(path.getFont().deriveFont(11f));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(name);
        left.add(Box.createVerticalStrut(2));
        left.add(path);

        ToggleSwitch toggle = new ToggleSwitch();
        toggle.setPreferredSize(new Dimension(52, 30));
        toggle.setToolTipText("Start/Stop this script");

        toggle.addActionListener(e -> {
            if (suppressToggle) return; // ignore programmatic changes
            boolean on = toggle.isSelected();
            if (on) {
                startScript(script);
            } else {
                stopScript(script);
            }
        });

        toggleByScript.put(script, toggle);

        row.add(left, BorderLayout.CENTER);
        row.add(toggle, BorderLayout.EAST);
        return row;
    }

    private void startScript(Path script) {
        try {
            ProcessRunner pr = ProcessRunner.Registry.getOrCreate(script, pythonExe, baseDir);
            pr.start(this::log);
            setToggleState(script, true);
            inputTarget = pr;
            updateInputTargetLabel();
            log("[run] " + script.getFileName() + " (PID=" + pr.getPid() + ") started.");
        } catch (Exception ex) {
            setToggleState(script, false);
            JOptionPane.showMessageDialog(frame,
                    "Failed to start script:\n" + script.toAbsolutePath() + "\n\n" + ex.getMessage(),
                    APP_NAME, JOptionPane.ERROR_MESSAGE);
            log("[error] Failed to start " + script.getFileName() + ": " + ex.getMessage());
        }
    }

    private void stopScript(Path script) {
        try {
            ProcessRunner pr = ProcessRunner.Registry.get(script);
            if (pr != null) pr.stopGracefully(3_000);
            setToggleState(script, false);
            if (inputTarget == pr) {
                inputTarget = null;
                updateInputTargetLabel();
            }
            log("[stop] " + script.getFileName() + " stopped.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame,
                    "Could not stop script:\n" + script.toAbsolutePath() + "\n\n" + ex.getMessage(),
                    APP_NAME, JOptionPane.WARNING_MESSAGE);
            log("[warn] Could not stop " + script.getFileName() + ": " + ex.getMessage());
        }
    }

    private void setToggleState(Path script, boolean on) {
        ToggleSwitch t = toggleByScript.get(script);
        if (t != null) {
            suppressToggle = true;
            try { t.setSelected(on); } finally { suppressToggle = false; }
        }
    }

    // ========== Package Management ==========

    private void showInstallPackagesDialog() {
        if (packageManager == null) {
            showError("Package manager not initialized. Please select a base folder first.");
            return;
        }

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.add(new JLabel("Enter package names (one per line, e.g., 'requests', 'numpy==1.26.0'):"), BorderLayout.NORTH);

        JTextArea textArea = new JTextArea(10, 40);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(textArea);
        panel.add(scroll, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(frame, panel, "Install Packages",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String text = textArea.getText().trim();
            if (text.isEmpty()) return;

            final String[] packages = text.split("\n");
            log("[pip] Installing " + packages.length + " package(s)...");

            // Run in background
            new Thread(() -> {
                for (String pkgLine : packages) {
                    final String pkg = pkgLine.trim();
                    if (pkg.isEmpty() || pkg.startsWith("#")) continue;
                    try {
                        Future<Integer> future = packageManager.installAsync(pkg, null);
                        int code = future.get();
                        if (code == 0) {
                            SwingUtilities.invokeLater(() -> log("[pip] ✓ Successfully installed: " + pkg));
                        } else {
                            SwingUtilities.invokeLater(() -> log("[pip] ✗ Failed to install: " + pkg + " (code " + code + ")"));
                        }
                    } catch (Exception ex) {
                        SwingUtilities.invokeLater(() -> log("[pip] ✗ Error installing " + pkg + ": " + ex.getMessage()));
                    }
                }
                SwingUtilities.invokeLater(() -> log("[pip] Installation complete."));
            }, "pkg-install").start();
        }
    }

    private void showInstallRequirementsDialog() {
        if (packageManager == null) {
            showError("Package manager not initialized. Please select a base folder first.");
            return;
        }

        JFileChooser chooser = new JFileChooser(scriptsDir != null ? scriptsDir.toFile() : baseDir.toFile());
        chooser.setDialogTitle("Select requirements.txt");
        chooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".txt");
            }
            @Override
            public String getDescription() {
                return "Text files (*.txt)";
            }
        });

        int result = chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            Path reqFile = chooser.getSelectedFile().toPath();
            log("[pip] Installing from: " + reqFile.getFileName());

            // Run in background
            new Thread(() -> {
                try {
                    Future<Integer> future = packageManager.installRequirementsAsync(reqFile, null);
                    int code = future.get();
                    if (code == 0) {
                        SwingUtilities.invokeLater(() -> log("[pip] ✓ Successfully installed requirements"));
                    } else {
                        SwingUtilities.invokeLater(() -> log("[pip] ✗ Failed to install requirements (code " + code + ")"));
                    }
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> log("[pip] ✗ Error: " + ex.getMessage()));
                }
            }, "req-install").start();
        }
    }

    // ========== Settings / Helpers ==========

    private void showSettingsDialog() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(5, 5, 5, 5);

        // Base folder
        panel.add(new JLabel("Base Folder:"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        JLabel baseLabel = new JLabel(baseDir == null ? "—" : baseDir.toAbsolutePath().toString());
        panel.add(baseLabel, gc);

        // Scripts folder
        gc.gridx = 0;
        gc.gridy++;
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        panel.add(new JLabel("Scripts Folder:"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        JLabel scriptsLabel = new JLabel(scriptsDir == null ? "—" : scriptsDir.toAbsolutePath().toString());
        panel.add(scriptsLabel, gc);

        // Python exe
        gc.gridx = 0;
        gc.gridy++;
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        panel.add(new JLabel("Python:"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        JTextField pythonField = new JTextField(pythonExe);
        panel.add(pythonField, gc);

        // Python version
        gc.gridx = 0;
        gc.gridy++;
        panel.add(new JLabel("Version:"), gc);
        gc.gridx = 1;
        String version = packageManager != null ? packageManager.getPythonVersion() : "unknown";
        JLabel versionLabel = new JLabel(version);
        panel.add(versionLabel, gc);

        // Browse button
        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 2;
        JButton browseBtn = new JButton("Choose Python Executable…");
        browseBtn.addActionListener(e -> {
            JFileChooser ch = new JFileChooser();
            ch.setDialogTitle("Select Python Executable");
            ch.setFileSelectionMode(JFileChooser.FILES_ONLY);
            int res = ch.showOpenDialog(frame);
            if (res == JFileChooser.APPROVE_OPTION) {
                pythonField.setText(ch.getSelectedFile().getAbsolutePath());
            }
        });
        panel.add(browseBtn, gc);

        int result = JOptionPane.showConfirmDialog(frame, panel, "Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String newPython = pythonField.getText().trim();
            if (!newPython.isEmpty() && !newPython.equals(pythonExe)) {
                pythonExe = newPython;
                saveConfig();
                initPackageManager();
                log("[info] Python executable set to: " + pythonExe);
            }
        }
    }

    private static Path chooseFolderDialog(Component parent, Path initial) {
        JFileChooser ch = new JFileChooser(initial != null ? initial.toFile() : new File(System.getProperty("user.home")));
        ch.setDialogTitle("Choose Base Folder for " + APP_NAME);
        ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        ch.setMultiSelectionEnabled(false);
        int res = ch.showOpenDialog(parent);
        if (res == JFileChooser.APPROVE_OPTION) {
            File f = ch.getSelectedFile();
            if (f != null && f.isDirectory()) {
                return f.toPath();
            }
        }
        return null;
    }

    private void openFolder(Path path) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            } else {
                // Fallback: do nothing; user can navigate manually
                log("[warn] Desktop integration not supported on this system.");
            }
        } catch (Exception ex) {
            log("[warn] Could not open folder: " + ex.getMessage());
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, APP_NAME, JOptionPane.ERROR_MESSAGE);
    }

    private void log(String line) {
        console.append(line + "\n");
        console.setCaretPosition(console.getDocument().getLength());
    }

    private void sendConsoleLine() {
        String text = consoleInput.getText();
        if (text == null) text = "";
        consoleInput.setText("");

        ProcessRunner target = (shellRunner != null && shellToggle.isSelected()) ? shellRunner : inputTarget;
        if (target != null && target.isRunning()) {
            target.sendLine(text);
        } else {
            log("[warn] No active process to receive input.");
        }
    }

    private void startShell() {
        try {
            // Use a synthetic key for the REPL runner; file doesn't need to exist.
            Path synthetic = scriptsDir.resolve("__PY_SHELL__.py");
            shellRunner = ProcessRunner.Registry.getOrCreate(synthetic, pythonExe, baseDir);
            shellRunner.startInteractiveShell(this::log);
            inputTarget = shellRunner;
            updateInputTargetLabel();
        } catch (Exception ex) {
            shellToggle.setSelected(false);
            log("[error] Failed to start Python shell: " + ex.getMessage());
            JOptionPane.showMessageDialog(frame,
                    "Failed to start Python shell:\n" + ex.getMessage(),
                    APP_NAME, JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopShell() {
        try {
            if (shellRunner != null) {
                shellRunner.stopGracefully(2_000);
                if (inputTarget == shellRunner) inputTarget = null;
                shellRunner = null;
                updateInputTargetLabel();
            }
        } catch (Exception ex) {
            log("[warn] Failed to stop shell: " + ex.getMessage());
        }
    }

    private void updateInputTargetLabel() {
        String who;
        if (shellRunner != null && shellToggle.isSelected() && shellRunner.isRunning()) {
            who = "Python Shell";
        } else if (inputTarget != null && inputTarget.isRunning()) {
            who = "PID " + inputTarget.getPid();
        } else {
            who = "— none —";
        }
        inputTargetLabel.setText("Input target: " + who);
    }

    // ========== Scanner Integration ==========

    private void startScanner() {
        restartScanner();
    }

    private void restartScanner() {
        try {
            if (scanner != null) scanner.stop();
        } catch (Exception ignore) {}
        if (scriptsDir == null) return;

        Consumer<String> logSink = this::log;

        scanner = new ScriptScanner(
                scriptsDir,
                Arrays.asList(PYTHON_CANDIDATES),
                new ScriptScanner.Listener() {
                    @Override public void onScriptsListChanged(List<Path> scripts) {
                        SwingUtilities.invokeLater(() -> {
                            refreshScriptsList();
                        });
                    }
                    @Override public void onScriptRunningState(Path script, boolean running, long pid) {
                        SwingUtilities.invokeLater(() -> setToggleState(script, running));
                    }
                },
                logSink
        );
        try {
            scanner.start();
        } catch (Exception ex) {
            log("[warn] Script scanner failed to start: " + ex.getMessage());
        }
    }

    // ========== Python resolution (Ubuntu-friendly) ==========

    private String resolvePythonExe() {
        // Later we can allow a user override; for Ubuntu default to python3 if present.
        for (String cand : PYTHON_CANDIDATES) {
            if (isOnPath(cand)) return cand;
        }
        return "python3"; // fallback; ProcessRunner will handle errors
    }

    private boolean isOnPath(String exe) {
        String p = System.getenv("PATH");
        if (p == null) return false;
        String[] dirs = p.split(File.pathSeparator);
        for (String d : dirs) {
            Path test = Paths.get(d, exe);
            if (Files.isRegularFile(test) && Files.isExecutable(test)) return true;
        }
        return false;
    }

    // ========== Pretty iOS-style Toggle ==========

    /**
     * A lightweight animated toggle switch that looks like iOS.
     * Clickable; animation handled with a Swing Timer.
     * OFF = red, ON = green.
     */
    public static class ToggleSwitch extends JToggleButton {
        private float anim = 0f;              // 0 = off, 1 = on (animated)
        private final javax.swing.Timer timer;

        public ToggleSwitch() {
            setModel(new ToggleButtonModel()); // avoid text painting
            setBorderPainted(false);
            setFocusPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setPreferredSize(new Dimension(52, 30));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            timer = new javax.swing.Timer(15, e -> stepAnimation());
            addItemListener(e -> timer.start());
            addChangeListener(e -> repaint());
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER) {
                        setSelected(!isSelected());
                    }
                }
            });
        }

        private void stepAnimation() {
            float target = isSelected() ? 1f : 0f;
            float speed = 0.2f; // easing factor
            anim = anim + (target - anim) * speed;
            if (Math.abs(target - anim) < 0.02f) {
                anim = target;
                timer.stop();
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics gOld) {
            Graphics2D g = (Graphics2D) gOld.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int arc = h; // pill

            // Track (background): OFF red, ON green
            Color on = new Color(76, 217, 100);    // iOS green
            Color off = new Color(255, 59, 48);    // iOS red
            Color track = blend(off, on, anim);
            g.setColor(track);
            g.fillRoundRect(0, 0, w, h, arc, arc);

            // Thumb
            int margin = 3;
            int thumbSize = h - margin * 2;
            int xOff = (int) (margin + anim * (w - margin * 2 - thumbSize));
            g.setColor(Color.WHITE);
            g.fillOval(xOff, margin, thumbSize, thumbSize);

            // Subtle border
            g.setColor(new Color(0, 0, 0, 40));
            g.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);

            g.dispose();
        }

        private static Color blend(Color a, Color b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int r = (int) (a.getRed() + (b.getRed() - a.getRed()) * t);
            int g = (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t);
            int bl = (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t);
            return new Color(r, g, bl);
        }
    }
}
