Ubuntu-Python-Launcher

A lightweight desktop utility (Java) that discovers and runs your local Python scripts with iOS-style on/off toggles and live console output—all from a simple GUI. Perfect for keeping small utilities running in the background without terminal windows or cron jobs.

Stack: Java (GUI / process control) + your Python scripts
Status: Early alpha — stable enough for personal use, still evolving

✨ Features

Auto-discovery of scripts
Points at a scripts/ folder and lists each *.py with a toggle.

iOS-style toggles
Click to start/stop a Python process; UI shows running state.

Live console output
Stream stdout/stderr to the GUI for easy monitoring.

One-click package install (optional)
A simple Package Manager helper for installing Python packages your scripts need.

Graceful stop
Attempts a clean shutdown first, then escalates if needed.

🧱 Project Structure (high level)
Ubuntu-Python-Launcher/
├─ src/                         # Java sources (4 classes)
│  ├─ LauncherUI.java           # GUI: toggles, console, layout
│  ├─ PackageManager.java       # Minimal pip helper / package status
│  ├─ ProcessRunner.java        # Start/stop Python processes & stream logs
│  └─ ScriptScanner.java        # Discover and watch scripts/ folder
├─ scripts/                     # Put your *.py files here (you create them)
├─ dist/                        # Build artifacts (JAR / DEB) after packaging
├─ out/                         # Compiled classes (generated)
├─ MANIFEST.MF                  # Populated during build
└─ README.md


Note: The app doesn’t ship with example scripts. Add your own .py files under scripts/.

✅ Requirements

Ubuntu 22.04+ (tested on 24+)

Java 21+ (OpenJDK recommended)

Python 3.10+ on PATH

Optional (for building a .deb): fakeroot, jpackage

Check Java:

java -version


Check Python:

python3 --version

🚀 Quick Start (Run from Source)

Clone the repo

git clone https://github.com/kamv23/Ubuntu-Python-Launcher.git
cd Ubuntu-Python-Launcher


Add scripts

Create a scripts/ folder (if it doesn’t exist).

Drop any *.py files inside, e.g. hello.py.

Build & run

rm -rf out dist MANIFEST.MF sources.txt
mkdir -p out/linux-classes dist
find ./src -name '*.java' > sources.txt

javac -encoding UTF-8 -d out/linux-classes @sources.txt
printf "Main-Class: LauncherUI\n" > MANIFEST.MF
jar cfm dist/PythonLauncher.jar MANIFEST.MF -C out/linux-classes .

java -jar dist/PythonLauncher.jar

🧪 How the Toggles Work

ON → starts python3 <script.py> as a subprocess (tracked by ProcessRunner).

OFF → attempts to stop gracefully; if the script ignores it, the process is terminated.

Best practice for your Python scripts

Handle SIGINT/SIGTERM and exit cleanly.

Flush output (print(..., flush=True)) so logs appear immediately in the GUI.

Avoid daemonizing; let this app own the process lifecycle.

📦 Using the Package Manager (Optional)

Some scripts need packages (e.g., requests, pandas). Use the built-in Package Manager to:

Check whether a package appears available

Attempt a pip install command on your behalf

You can always install packages manually:

python3 -m pip install <package>

🛠️ Build a .deb (Optional)

Create a native installer for Ubuntu using jpackage.

sudo apt-get update
sudo apt-get install -y openjdk-21-jdk fakeroot

# Clean & build the runnable JAR
rm -rf out dist MANIFEST.MF sources.txt
mkdir -p out/linux-classes dist
find ./src -name '*.java' > sources.txt
javac -encoding UTF-8 -d out/linux-classes @sources.txt
printf "Main-Class: LauncherUI\n" > MANIFEST.MF
jar cfm dist/PythonLauncher.jar MANIFEST.MF -C out/linux-classes .

# Build a .deb (example — adjust metadata as you like)
jpackage \
  --type deb \
  --name "PythonLauncher" \
  --app-version "1.0.0" \
  --input dist \
  --main-jar PythonLauncher.jar \
  --dest dist \
  --vendor "kamv23" \
  --linux-shortcut

# Install it
sudo apt install ./dist/PythonLauncher_1.0.0-1_$(dpkg --print-architecture).deb

🧩 Troubleshooting

Toggles get “stuck” ON:
Make sure your Python script handles termination signals and exits. If it spawns other scripts or servers, ensure it also stops them on exit.

No scripts listed:
Confirm your files are in scripts/ and end with .py.

No console output:
Add flush=True to Python prints, or call sys.stdout.flush() periodically.

Permission issues with pip:
Prefer user installs: python3 -m pip install --user <package>.

Java not found / wrong version:
Install OpenJDK 21+ and ensure java resolves to it.

🗺️ Roadmap (short list)

Settings panel for custom Python path & working dirs

Per-script environment variables

Better “graceful stop” detection & status badges

Optional web preview for scripts that host HTTP services

🤝 Contributing

Issues and PRs are welcome!
Please keep PRs small and focused (one fix/feature per PR).

📄 License

MIT — do whatever you want, just don’t hold the author liable.

🙌 Credits

Built by @kamv23 for a no-nonsense way to run Python utilities on Ubuntu with a clean, friendly UI.
