# Machine Bridge Android APK (`machinebridge-apk`)

A standalone, ultra-lean Android application that hosts the high-performance C++ **Machine Bridge** server engine. It provides an unprivileged userspace shell (`/system/bin/sh`) inside the Android application sandbox, exposing full machine execution and filesystem primitives to AI agents over standard REST, WebSocket, SSE, and MCP protocols.

Designed primarily for **non-rooted Android phones** (zero root, zero ADB, zero Termux required), while seamlessly supporting **optional root elevation** on rooted devices.

---

## Key Features & Highlights

* **No Root, No ADB, No Termux Required**: Install the APK like any regular app. The embedded C++ server boots up and provides an interactive terminal session using Android's native `/system/bin/sh`.
* **Ultra-Lean Footprint (523 KB)**: Native code is compressed via DEFLATE (`useLegacyPackaging = true`) and Java bytecode is optimized with R8, keeping the entire ready-to-install release APK at just **523 KB** (`535,794 bytes`).
* **Cloudflare Tunnel Enabled by Default**: Includes an on-demand Cloudflare Tunnel client. When enabled, it provides an immediate public HTTPS URL (`https://*.trycloudflare.com`) accessible to remote AI clients anywhere in the world.
  * **Dynamic Runtime Download**: The heavy Go-based `cloudflared` binary (~70 MB) is **never bundled** inside the APK; it is downloaded on demand directly into the app cache with SHA-256 integrity verification.
  * **Protocol Selection**: Defaults to `HTTP/2 (TCP)` for maximum cellular carrier compatibility, with optional `QUIC (UDP)`.
  * **Named Tunnel Token Support**: Connect your own custom domain or private Cloudflare Tunnel token via the UI.
* **Persistent Android Foreground Service**: The server runs under `MachineBridgeService` with an ongoing system notification, ensuring reliable server persistence when the screen is locked or the app is minimized.
* **Real-Time In-App Log Streaming**: Live C++ server events, HTTP access logs, and Cloudflare tunnel status stream directly into the scrollable in-app console view via a lock-free native ring buffer. Includes one-tap **Copy Logs** and **Clear** controls.
* **Zero Button Hangs / Responsive Lifecycle**: Implements a strict 4-state lifecycle (`STOPPED`, `STARTING`, `RUNNING`, `STOPPING`). Background tunnel threads detach cleanly, eliminating ANRs and UI freezes.
* **Comprehensive In-App Configuration**:
  * **Port & Bind Host**: Defaults to port `8080` and `0.0.0.0`.
  * **API Key Management**: Secure cryptographic key generated on first launch with Reveal, Copy, and Regenerate buttons.
  * **Shell Selector**: Quick presets for standard `/system/bin/sh` and Termux `bash`, plus custom path entry.
  * **Workspace Root**: App sandbox files directory (`/data/user/0/com.machinebridge.app/files`).
  * **Advanced Collapsible Settings**: Server Log Level (`info`, `debug`, `warn`), Max Sessions, Session TTL, and Verbose Logging toggles.
* **One-Tap LAN Discovery**: Auto-detects the phone's Wi-Fi / LAN IP address and displays a one-tap **Copy URL** button (`http://<phone-ip>:8080`) for instant local client configuration.
* **In-App PTY Diagnostic Suite (Phase 0 Gate)**: Includes a built-in **"RUN PTY SELF-TEST"** button that executes POSIX PTY allocation, child spawning, standard I/O, terminal resizing, signals, and concurrency directly within the app's UID sandbox.
* **Optional Verified Root Support**: On rooted phones, verifies working `su` capability via `su -c "id -u"` and allows AI agents to explicitly request elevated root sessions while keeping the app itself running as an unprivileged process.

---

## Architecture: Consuming the Canonical C++ Engine

```text
machinebridge-cpp  (Canonical C++ core engine, PTY, protocols, NDK builds)
       │
       │ Android NDK build (arm64-v8a in WSL)
       ▼
libmachinebridge.so  (Versioned native artifact, 1.39 MB uncompressed)
       │
       │ Consumed into machinebridge-apk/app/src/main/jniLibs/arm64-v8a/
       ▼
machinebridge-apk  (Lean Management UI, Foreground Service, JNI Bridge, 523 KB APK)
```

The Android repository contains **zero duplicated C++ source code**. It consumes `libmachinebridge.so` directly into its `jniLibs/arm64-v8a/` directory.

---

## APK Size & Optimization Breakdown

By enabling legacy packaging in AGP 8+ (`useLegacyPackaging = true`), Gradle applies DEFLATE compression to native libraries instead of storing them uncompressed (`Method: Stored`):

| Component | Size | Notes |
| :--- | :--- | :--- |
| **Release APK (`app-release.apk`)** | **523 KB** (`535,794 bytes`) | R8 minified & DEFLATE compressed |
| **Debug APK (`app-debug.apk`)** | **748 KB** (`748,886 bytes`) | Unminified with debug symbols |
| **Native Library (`libmachinebridge.so`)** | **510 KB** inside APK | Compressed from 1.39 MB uncompressed binary |
| **DEX Bytecode (`classes.dex`)** | **18.4 KB** (`18,484 bytes`) | Pure Android framework, zero third-party UI libraries |
| **Resource Table (`resources.arsc`)** | **1.8 KB** (`1,884 bytes`) | Vector drawables, dark theme, card layouts |
| **Target ABI** | `arm64-v8a` only | 64-bit ARM architecture for modern Android devices |

> [!NOTE]
> The Cloudflare binary (`cloudflared`) is **not** included inside the APK. It is downloaded dynamically into app storage if tunnel mode is enabled, saving ~70 MB from the download size.

---

## Project Structure

```text
machinebridge-apk/
├── build.gradle                            # Root Gradle build file
├── settings.gradle                         # Project & module settings
├── gradle.properties                       # JVM & Android build properties
├── local.properties                        # Local Android SDK path
├── machinebridge.apk                       # Staged Release APK (523 KB)
└── app/
    ├── build.gradle                        # App build config (arm64-v8a filter, R8, useLegacyPackaging)
    ├── proguard-rules.pro                  # Proguard / R8 preservation rules
    └── src/main/
        ├── AndroidManifest.xml             # Permissions & service declarations
        ├── java/com/machinebridge/app/
        │   ├── MainActivity.java           # Server Dashboard UI, configuration forms & log poller
        │   ├── MachineBridgeService.java   # 4-State Foreground Service lifecycle manager
        │   ├── NativeBridge.java           # JNI loader & native method signatures
        │   └── NetworkUtils.java           # Local Wi-Fi / LAN IPv4 address discovery
        ├── res/
        │   ├── layout/activity_main.xml    # Card-based dark dashboard UI layout
        │   ├── drawable/                   # Vector background drawables and cards
        │   └── values/
        │       ├── colors.xml              # Dark mode color palette
        │       └── strings.xml             # UI string resources
        └── jniLibs/arm64-v8a/
            └── libmachinebridge.so         # Pre-compiled C++ native artifact
```

---

## Building the APK

### Prerequisites
* **Java Development Kit**: JDK 17 (e.g. OpenJDK 17)
* **Android SDK**: API 34 (`android-34`), Build-Tools `34.0.0`
* **Gradle**: Version 8.5 or newer

### Build Commands
```bash
# Build Release APK (R8 optimized, 523 KB)
./gradlew assembleRelease

# Build Debug APK
./gradlew assembleDebug
```

Outputs are generated at:
* `app/build/outputs/apk/release/app-release.apk`
* `app/build/outputs/apk/debug/app-debug.apk`

---

## Installation & Usage

### 1. Install on Device
Connect your Android phone via USB (with USB debugging enabled):
```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```
*Or transfer `machinebridge.apk` directly to your phone and install it with your device file manager.*

### 2. Configure & Start Server
1. Launch **Machine Bridge** from the app drawer.
2. Review configuration:
   * **Port**: Default `8080`
   * **Bind Host**: Default `0.0.0.0`
   * **Default Shell**: `/system/bin/sh`
   * **Enable Cloudflare Tunnel**: Checked by default (`HTTP/2 (TCP)` protocol)
3. *(Optional)* Tap **"RUN PTY SELF-TEST"** to verify that `/system/bin/sh` operates properly in your app sandbox.
4. Tap **"START SERVER"**. The service notification appears and status switches to `RUNNING`.
5. Once the server and tunnel initialize:
   * Tap **"Copy URL"** next to the Cloudflare Tunnel URL to copy the public `https://*.trycloudflare.com` link.
   * Tap **"Copy Key"** to copy your API authentication token.
6. The **Live Console** at the bottom will continuously stream server logs and tunnel connection notices.

---

## Real-Device Verification Evidence

Verified on real Android hardware:

### Xiaomi Redmi Note 5 Pro (Android 9 / Linux 4.4 aarch64)
* **Package UID**: `userId=10171` (`untrusted_app` sandbox).
* **Foreground Service**: Started cleanly, bound to `0.0.0.0:8080`.
* **Environment Payload (`GET /api/environment`)**:
  ```json
  {
    "architecture": "arm64-v8a",
    "default_shell": "/system/bin/sh",
    "home_dir": "/data/user/0/com.machinebridge.app/files",
    "host_capabilities": ["pty", "signals", "process_groups", "sockets"],
    "kernel_version": "4.4.153-perf+",
    "os_version": "Linux",
    "platform": "android",
    "privileged_shell_available": false,
    "server_is_root": false,
    "server_uid": 10171,
    "tmp_dir": "/data/user/0/com.machinebridge.app/cache",
    "type": "android-app",
    "workspace_dir": "/data/user/0/com.machinebridge.app/files"
  }
  ```
* **Tool Calling Verification**:
  * `execute_command`: Executed `id; pwd; echo HOME=$HOME; uname -a` under `context=u:r:untrusted_app`.
  * `create_session`: Spawned interactive PTY session (PID 15382).
  * `send_input` & `read_output`: Streamed shell input and received output prompt `:/ $`.
  * `write_file` & `read_file`: Performed file operations within the sandbox workspace.
* **Live In-App Logging**: Log console verified receiving real-time logs via `nativeGetRecentLogs()`.

---

## License

MIT License.
