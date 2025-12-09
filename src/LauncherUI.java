import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cross‑platform Launcher UI (Ubuntu 24+, macOS, Windows)
 *
 * This rewrite aligns the Ubuntu build with the "Mac gold" behavior while
 * matching our updated static PackageManager/ProcessRunner/ScriptScanner APIs.
 *
 * Key points:
 *  • Uses PackageManager (static) prefs for baseDir & pythonExec
 *  • Creates and opens folders with xdg‑open fallback on Linux
 *  • iOS‑style animated toggles for scripts (green=ON, red=OFF)
 *  • Background ScriptScanner flips toggles if scripts start externally
 *  • Live console + input bar (sends to the active script or Python shell)
 */
public class LauncherUI {

    private static final String APP_NAME = "Python Launcher";
    private static final String[] PYTHON_CANDIDATES = {"python3", "python"};

    // UI
    private JFrame frame;
    private JLabel baseDirLabel;
    private JTextArea console;
    private JTextField consoleInput;
    private JLabel inputTargetLabel;
    private JToggleButton shellToggle;
    private JPanel scriptsPanel;
    private JScrollPane scriptsScroll;

    // State
    private Path baseDir;
    private Path scriptsDir;
    private String pythonExe;

    // Script -> Toggle
    private final Map<Path, ToggleSwitch> toggleByScript = new HashMap<>();

    // Processes
    private ProcessRunner inputTarget;
    private ProcessRunner shellRunner;

    // Background
    private ScriptScanner scanner;
    private volatile boolean suppressToggle = false;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignore) {
        }
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

        // Bootstrap project state from PackageManager prefs
        baseDir = PackageManager.getRootDir();
        pythonExe = PackageManager.getPythonExec();
        try {
            PackageManager.ensureScaffold(this::log);
        } catch (Exception e) {
            log("[setup] " + e.getMessage());
        }
        scriptsDir = PackageManager.getScriptsDir();
        updateBaseDirLabel();

        if (pythonExe == null || pythonExe.isEmpty()) {
            log("[warn] No Python interpreter found. Set one in Settings → Python.");
        }

        refreshScriptsList();
        startScanner();

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (scanner != null) scanner.stop();
                ProcessRunner.Registry.stopAll(3_000);
            }
        });

        frame.setVisible(true);
    }

    // ================= Python Executable Management =================

    /**
     * Get the current Python executable, refreshing from PackageManager if needed.
     * This is the single source of truth for Python exe access.
     */
    private String getActivePythonExe() {
        if (pythonExe == null || pythonExe.isBlank()) {
            pythonExe = PackageManager.getPythonExec();
        }
        return pythonExe;
    }

    /**
     * Validate that Python executable exists and is executable.
     * Shows error dialog if validation fails.
     */
    private boolean validatePython() {
        String exe = getActivePythonExe();
        if (exe == null || exe.isEmpty()) {
            showError("No Python interpreter configured.\nPlease set one in Settings → Python.");
            return false;
        }
        Path exePath = Paths.get(exe);
        if (!Files.exists(exePath)) {
            showError("Python executable not found:\n" + exe + "\n\nPlease configure a valid Python interpreter in Settings.");
            return false;
        }
        if (!Files.isExecutable(exePath)) {
            showError("Python executable is not executable:\n" + exe + "\n\nPlease check file permissions.");
            return false;
        }
        return true;
    }

    // ================= UI: Top Bar =================

    private JComponent buildTopBar() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(new EmptyBorder(10, 10, 10, 10));

        baseDirLabel = new JLabel("Base: —");
        baseDirLabel.setFont(baseDirLabel.getFont().deriveFont(Font.BOLD, 13f));
        top.add(baseDirLabel, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

        JButton btnChange = new JButton("Change Base Folder");
        btnChange.addActionListener(e -> onChangeBaseFolder());

        JButton btnOpenScripts = new JButton("Open Scripts Folder");
        btnOpenScripts.addActionListener(e -> {
            if (scriptsDir != null) PackageManager.openDir(scriptsDir, this::log);
        });

        JButton btnRefresh = new JButton("Refresh");
        btnRefresh.addActionListener(e -> refreshScriptsList());

        JButton btnInstallPkg = new JButton("Install Packages…");
        btnInstallPkg.setToolTipText("Install Python packages into imports");
        btnInstallPkg.addActionListener(e -> showInstallPackagesDialog());

        JButton btnInstallReq = new JButton("Install Requirements…");
        btnInstallReq.setToolTipText("Install from requirements.txt into imports");
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

    private void onChangeBaseFolder() {
        Path chosen = chooseFolderDialog(frame, baseDir);
        if (chosen == null) return;
        try {
            PackageManager.setRootDir(chosen);
            baseDir = PackageManager.getRootDir();
            scriptsDir = PackageManager.getScriptsDir();
            PackageManager.ensureScaffold(this::log);
            updateBaseDirLabel();
            refreshScriptsList();
            restartScanner();
            log("[info] Base folder set to: " + baseDir);
        } catch (Exception ex) {
            showError("Failed to set base folder:\n" + ex.getMessage());
        }
    }

    // ================= UI: Center (Scripts) =================

    private JComponent buildCenter() {
        scriptsPanel = new JPanel(new GridBagLayout());
        scriptsPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        scriptsScroll = new JScrollPane(scriptsPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scriptsScroll.getVerticalScrollBar().setUnitIncrement(16);
        return scriptsScroll;
    }

    private void refreshScriptsList() {
        toggleByScript.clear();
        scriptsPanel.removeAll();

        List<Path> scripts = listPyScripts(scriptsDir);
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

        gc.weighty = 1.0;
        gc.fill = GridBagConstraints.BOTH;
        scriptsPanel.add(Box.createVerticalGlue(), gc);

        scriptsPanel.revalidate();
        scriptsPanel.repaint();
    }

    private List<Path> listPyScripts(Path dir) {
        if (dir == null) return List.of();
        try (var stream = Files.list(dir)) {
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
            if (suppressToggle) return;
            boolean on = toggle.isSelected();
            if (on) startScript(script);
            else stopScript(script);
        });
        toggleByScript.put(script, toggle);

        row.add(left, BorderLayout.CENTER);
        row.add(toggle, BorderLayout.EAST);
        return row;
    }

    private void startScript(Path script) {
        if (!validatePython()) {
            setToggleState(script, false);
            return;
        }

        try {
            String exe = getActivePythonExe();
            ProcessRunner pr = ProcessRunner.Registry.getOrCreate(script, exe, baseDir);
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
            try {
                t.setSelected(on);
            } finally {
                suppressToggle = false;
            }
        }
    }

    // ================= UI: Bottom Console =================

    private JComponent buildBottomConsole() {
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(new EmptyBorder(0, 10, 10, 10));

        console = new JTextArea(8, 20);
        console.setEditable(false);
        console.setLineWrap(true);
        console.setWrapStyleWord(true);
        bottom.add(new JScrollPane(console,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);

        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.setBorder(new EmptyBorder(8, 0, 0, 0));

        shellToggle = new JToggleButton("Python Shell");
        shellToggle.setToolTipText("Start/Stop an interactive Python REPL in the scripts folder");
        shellToggle.addActionListener(e -> {
            if (shellToggle.isSelected()) startShell();
            else stopShell();
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

    private void sendConsoleLine() {
        String text = consoleInput.getText();
        if (text == null) text = "";
        consoleInput.setText("");
        ProcessRunner target = (shellRunner != null && shellToggle.isSelected()) ? shellRunner : inputTarget;
        if (target != null && target.isRunning()) target.sendLine(text);
        else log("[warn] No active process to receive input.");
    }

    private void startShell() {
        if (!validatePython()) {
            shellToggle.setSelected(false);
            return;
        }

        try {
            Path synthetic = scriptsDir.resolve("__PY_SHELL__.py");
            String exe = getActivePythonExe();
            shellRunner = ProcessRunner.Registry.getOrCreate(synthetic, exe, baseDir);
            shellRunner.startInteractiveShell(this::log);
            inputTarget = shellRunner;
            updateInputTargetLabel();
        } catch (Exception ex) {
            shellToggle.setSelected(false);
            log("[error] Failed to start Python shell: " + ex.getMessage());
            showError("Failed to start Python shell:\n" + ex.getMessage());
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
        if (shellRunner != null && shellToggle.isSelected() && shellRunner.isRunning()) who = "Python Shell";
        else if (inputTarget != null && inputTarget.isRunning()) who = "PID " + inputTarget.getPid();
        else who = "— none —";
        inputTargetLabel.setText("Input target: " + who);
    }

    // ================= Packages =================

    private void showInstallPackagesDialog() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.add(new JLabel("Enter package names (one per line, e.g., 'requests', 'numpy==1.26.0'):"), BorderLayout.NORTH);
        JTextArea textArea = new JTextArea(10, 40);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(textArea);
        panel.add(scroll, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(frame, panel, "Install Packages",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String text = textArea.getText().trim();
        if (text.isEmpty()) return;
        final List<String> pkgs = new ArrayList<>();
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) pkgs.add(s);
        }
        installPackagesAsync(pkgs);
    }

    private void showInstallRequirementsDialog() {
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
            installPackagesAsync(List.of("-r", reqFile.toString()));
        }
    }

    private void installPackagesAsync(List<String> args) {
        if (args == null || args.isEmpty()) return;

        // Validate Python before starting install thread
        if (!validatePython()) {
            return;
        }

        // Capture the Python exe outside the thread to avoid race conditions
        final String exe = getActivePythonExe();

        new Thread(() -> {
            try {
                PackageManager.ensureScaffold(this::log);
                log("[pip] Installing " + args + " …");
                PackageManager.installPackages(exe, args, this::log);
                log("[pip] Installation complete.");
            } catch (Exception ex) {
                log("[pip] ✗ Error: " + ex.getMessage());
                SwingUtilities.invokeLater(() ->
                        showError("Package installation failed:\n" + ex.getMessage())
                );
            }
        }, "pip-install").start();
    }

    // ================= Settings =================

    private void showSettingsDialog() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(5, 5, 5, 5);

        panel.add(new JLabel("Base Folder:"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        JLabel baseLabel = new JLabel(baseDir == null ? "—" : baseDir.toAbsolutePath().toString());
        panel.add(baseLabel, gc);

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

        gc.gridx = 0;
        gc.gridy++;
        panel.add(new JLabel("Python:"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1.0;
        JTextField pythonField = new JTextField(getActivePythonExe());
        panel.add(pythonField, gc);

        gc.gridx = 0;
        gc.gridy++;
        panel.add(new JLabel("Version:"), gc);
        gc.gridx = 1;
        JLabel versionLabel = new JLabel(getPythonVersionSafe());
        panel.add(versionLabel, gc);

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
                String selected = ch.getSelectedFile().getAbsolutePath();
                pythonField.setText(selected);
                // Update version label immediately
                SwingUtilities.invokeLater(() -> {
                    String tempExe = pythonExe;
                    pythonExe = selected;
                    versionLabel.setText(getPythonVersionSafe());
                    pythonExe = tempExe; // Restore until OK is clicked
                });
            }
        });
        panel.add(browseBtn, gc);

        // Add Download Latest Python (Portable)… button
        gc.gridy++;
        JButton downloadBtn = new JButton("Download Latest Python (Portable)…");
        downloadBtn.addActionListener(e -> {
            downloadBtn.setEnabled(false);
            new Thread(() -> {
                try {
                    log("[python] Downloading and building latest CPython…");
                    String exe = PackageManager.downloadAndInstallLatestPython(this::log);
                    if (exe != null && !exe.isEmpty()) {
                        SwingUtilities.invokeLater(() -> {
                            pythonField.setText(exe);
                            PackageManager.setPythonExec(exe);
                            pythonExe = exe;
                            versionLabel.setText(getPythonVersionSafe());
                            log("[python] Using portable Python at: " + exe);
                        });
                    } else {
                        log("[python] Portable Python installation did not complete.");
                    }
                } catch (Exception ex) {
                    log("[python] Error installing Python: " + ex.getMessage());
                    SwingUtilities.invokeLater(() ->
                            showError("Failed to download Python:\n" + ex.getMessage())
                    );
                } finally {
                    SwingUtilities.invokeLater(() -> downloadBtn.setEnabled(true));
                }
            }, "python-download").start();
        });
        panel.add(downloadBtn, gc);

        int result = JOptionPane.showConfirmDialog(frame, panel, "Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            String newPython = pythonField.getText().trim();
            if (!newPython.isEmpty() && !newPython.equals(pythonExe)) {
                // Validate before saving
                Path newPath = Paths.get(newPython);
                if (!Files.exists(newPath)) {
                    showError("Python executable not found:\n" + newPython + "\n\nPlease select a valid file.");
                    return;
                }
                if (!Files.isExecutable(newPath)) {
                    showError("File is not executable:\n" + newPython + "\n\nPlease check file permissions.");
                    return;
                }
                PackageManager.setPythonExec(newPython);
                pythonExe = newPython;
                log("[info] Python set to: " + pythonExe);
            }
        }
    }

    private String getPythonVersionSafe() {
        String exe = getActivePythonExe();
        if (exe == null || exe.isBlank()) return "unknown";
        try {
            Process p = new ProcessBuilder(exe, "--version").redirectErrorStream(true).start();
            p.waitFor(800, TimeUnit.MILLISECONDS);
            byte[] out = p.getInputStream().readAllBytes();
            String s = new String(out, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? "unknown" : s;
        } catch (Exception e) {
            return "unknown (" + e.getMessage() + ")";
        }
    }

    // ================= Helpers =================

    private static Path chooseFolderDialog(Component parent, Path initial) {
        JFileChooser ch = new JFileChooser(initial != null ? initial.toFile() : new File(System.getProperty("user.home")));
        ch.setDialogTitle("Choose Base Folder for " + APP_NAME);
        ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        ch.setMultiSelectionEnabled(false);
        int res = ch.showOpenDialog(parent);
        if (res == JFileChooser.APPROVE_OPTION) {
            File f = ch.getSelectedFile();
            if (f != null && f.isDirectory()) return f.toPath();
        }
        return null;
    }

    private void updateBaseDirLabel() {
        String s = (baseDir == null) ? "—" : baseDir.toAbsolutePath().toString();
        baseDirLabel.setText("Base: " + s);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, APP_NAME, JOptionPane.ERROR_MESSAGE);
    }

    private void log(String line) {
        console.append(line + "\n");
        console.setCaretPosition(console.getDocument().getLength());
    }

    // ================= Scanner Integration =================

    private void startScanner() {
        restartScanner();
    }

    private void restartScanner() {
        try {
            if (scanner != null) scanner.stop();
        } catch (Exception ignore) {
        }
        if (scriptsDir == null) return;
        scanner = new ScriptScanner(
                scriptsDir,
                Arrays.asList(PYTHON_CANDIDATES),
                new ScriptScanner.Listener() {
                    @Override
                    public void onScriptsListChanged(List<Path> scripts) {
                        SwingUtilities.invokeLater(LauncherUI.this::refreshScriptsList);
                    }

                    @Override
                    public void onScriptRunningState(Path script, boolean running, long pid) {
                        SwingUtilities.invokeLater(() -> setToggleState(script, running));
                    }
                },
                this::log
        );
        try {
            scanner.start();
        } catch (Exception ex) {
            log("[warn] Script scanner failed to start: " + ex.getMessage());
        }
    }

    // ================= iOS-style Toggle =================

    public static class ToggleSwitch extends JToggleButton {
        private float anim = 0f; // 0 off, 1 on
        private final javax.swing.Timer timer;

        public ToggleSwitch() {
            setModel(new ToggleButtonModel());
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
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER)
                        setSelected(!isSelected());
                }
            });
        }

        private void stepAnimation() {
            float target = isSelected() ? 1f : 0f;
            float speed = 0.2f;
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
            int arc = h;
            Color on = new Color(76, 217, 100);   // iOS green
            Color off = new Color(255, 59, 48);   //
        }
    }
}