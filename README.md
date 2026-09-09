# GAMA Mobile

**GAMA Mobile** is an Android application that runs the [GAMA platform](https://gama-platform.org) — the open-source spatially explicit agent-based modeling and simulation environment — directly on the device. It packages a complete Linux runtime (a full ARM64 Ubuntu rootfs) and runs the GAMA desktop GUI inside [PRoot](https://proot-me.github.io/), streaming the desktop to the phone over VNC with an on-device web proxy.

```
┌─────────────────────────────── Android app (com.simulation.mobile) ───────────────────────────────┐
│                                                                                                  │
│  React Native UI ── http://127.0.0.1:8080 ──► Kotlin: SimulationService / FallbackHealthServer    │
│   (VncScreen, Dashboard, Settings)              │  8080 ─► 8081 (bridge, in-guest)                │
│                                                  │                                               │
│  Kotlin: VncScreen ── raw TCP ──► 127.0.0.1:5901 (x11vnc)                                        │
│  Kotlin: VncProxyServer ── WS 6080 / noVNC 6090 ─┘                                                │
│                                                                                                  │
│  PRootManager ─ spawns ─► PRoot (Termux proot, seccomp) ──► Ubuntu rootfs (ARM64)                │
│                             └── /opt/gama/startup.sh:                                            │
│                                   • Xvfb (patched) + x11vnc :0, 960x540                          │
│                                   • Mesa llvmpipe (software OpenGL 3.3)                          │
│                                   • bridge-server.py on 8081                                     │
│                                   • GAMA desktop GUI (JOGL 3D display)                           │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Why this exists

A normal GAMA install needs a full desktop environment. GAMA Mobile delivers that environment **at first launch** by downloading a pre-built rootfs (~700 MB) from GitHub Releases, extracting it into the app sandbox, and starting it with PRoot — no rooting, no Linux containers required. The whole simulation stack (kernel-side via PRoot, GUI via Xvfb, input/output via VNC) runs as a regular Android process.

## Runtime flow

1. **First launch** — `PRootManager` downloads `rootfs_tar_gz` from
   `https://github.com/hqnghi88/agama/releases/download/rootfs/rootfs_tar_gz`, verifies and extracts it
   (20-minute budget, periodic progress heartbeats pushed to the RN UI as `SetupProgress` events).
2. **Boot** — PRoot is spawned (`libproot.so` + Termux loader) and runs the guest `/opt/gama/startup.sh`:
   - starts the **bridge server** on `8081` (REST API for the RN app),
   - starts **Xvfb** + **x11vnc** on `:0` (960×540, RGB24),
   - runs **GAMA** with `DISPLAY=:0`, software OpenGL (Mesa llvmpipe), and a pre-extracted JOGL native dir.
3. **UI** — the RN frontend shows a full-screen **boot console** (terminal-style log of setup progress),
   then switches to the **VNC viewer** once GAMA's desktop is reachable on `5901`.

## Ports

| Port | Direction | Purpose |
|------|-----------|---------|
| `8080` | device loopback | RN API gateway (`BASE_URL`), HTTP-only; run by `FallbackHealthServer` |
| `8081` | device loopback | in-guest HTTP bridge (`bridge-server.py`) |
| `5901` | device loopback | VNC/RFB (raw TCP, used by `VncRfbClient`) |
| `6080` | device loopback | VNC WebSocket proxy (`VncProxyServer`) |
| `6090` | device loopback | noVNC web client page |
| `6868` | device loopback | GAMA headless WebSocket mode (used when running headless instead of GUI) |

## Repository layout (`mobile-app/`)

| Path | Purpose |
|------|---------|
| `android/` | Native Android app (Kotlin + React Native). |
| `src/` | RN frontend: screens, API client, simulation store. |
| `scripts/` | Build orchestrator, rootfs builder, binary patch tools, release uploader. |
| `proot-setup/` | Guest-side files baked into the rootfs archive. |
| `App.tsx` | App entry (renders `VncScreen`). |

### Native components

| File | Role |
|------|------|
| `…/service/PRootManager.kt` | Downloads/extracts the rootfs; builds the PRoot command line (bind mounts, env, guest `startup.sh`); inline startup template; progress events. |
| `…/service/SimulationService.kt` | Foreground service; owns backend lifecycle; starts `FallbackHealthServer`. |
| `…/service/FallbackHealthServer.kt` | 8080 gateway → forwards to `8081`; serves synthetic `/api/health` + `/api/models` while the bridge is down. |
| `…/service/VncProxyServer.kt` | Optional VNC over WebSocket proxy (`6080`) + noVNC page (`6090`). |
| `…/service/VncView.kt`, `…/vnc/VncRfbClient.kt` | Native view rendering the GAMA desktop; raw RFB client to `127.0.0.1:5901` with gating on backend state and auto-reconnect. |
| `…/jniLibs/arm64-v8a/` | PRoot binaries (`libproot.so`, loaders, `libtalloc.so`). |

### RN components

| File | Role |
|------|------|
| `src/screens/VncScreen.tsx` | Boot console + VNC viewer + keyboard toggle. |
| `src/screens/DashboardScreen.tsx` | Status polling, progress bar, result display. |
| `src/screens/SettingsScreen.tsx` | App settings. |
| `src/services/api.ts` | HTTP client to `http://127.0.0.1:8080`. |
| `src/store/useSimulationStore.ts` | Global state (health, jobs, log). |

### Guest-side scripts (inside the rootfs at `/opt/gama/`)

| File | Role |
|------|------|
| `startup.sh` | Boot entrypoint: bridge, Xvfb, x11vnc, GAMA. Also mirrored at `/startup.sh`. |
| `bridge-server.py` | Python HTTP/WS bridge; resilient GAMA reconnects; backs the RN API on `8081`. |
| `gama-launcher.sh`, `gama-vnc.sh`, `java-env.sh` | Headless / VNC / JVM helpers. |
| `natives/linux-aarch64/` | Pre-extracted JOGL native libraries (baked into the archive, see below). |

## Building

### Prerequisites

| Tool | Version |
|------|---------|
| JDK | 21+ (Eclipse Temurin recommended; used in CI) |
| Node.js | 18+ (LTS) |
| npm | 9+ |
| Android SDK | platforms;android-34, build-tools;34.0.0 (`ANDROID_HOME` set) |
| Docker | Latest (only needed to rebuild the rootfs) |

### Commands

```bash
./scripts/build.sh check    # prerequisites + local.properties
./scripts/build.sh all      # full build: rootfs → React Native bundle → APK
./scripts/build.sh install  # build + install on a connected device
./scripts/build.sh help     # list all commands
```

Outputs:

```
android/app/build/outputs/apk/debug/app-debug.apk      # debug APK
android/app/build/outputs/apk/release/app-release.apk  # release APK
android/app/build/outputs/bundle/release/app-release.aab  # release AAB (Play)
```

> The rootfs is **not** embedded in the APK (Google Play size limit). Store builds must not include
> `res/raw/rootfs_tar_gz` — it is delivered at runtime from GitHub Releases.

## The runtime rootfs

The rootfs is an ARM64 Debian Bookworm image containing Java 25 (Temurin JRE), Python 3, Xvfb/x11vnc, Mesa software OpenGL, the GAMA desktop product, and the guest scripts above. It is built with Docker + QEMU:

```bash
docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
./scripts/build-rootfs.sh
```

A few **required binary patches** are applied on top of the base distribution (all in `scripts/`, each
byte-for-byte verified and idempotent):

| Tool | Why |
|------|-----|
| `patch-xvfb.py` | NOPs the in-process xkbcomp branch and rewrites the xkbcomp argv so the X server uses a pre-placed keymap (`/var/lib/xkb/server-0.xkm`) — the stock binary crashes under PRoot. |
| `patch-mesa-glx.py` | NOPs the GLX swap-interval entry points (`glXQueryDrawable`, `glXSwapIntervalEXT/SGI`) — Mesa's swap-interval path segfaults JOGL on llvmpipe swrast drawables. |
| `patch_elf_16kb.py` | 16 KB ELF segment alignment for Google Play compliance. |
| JOGL natives | The `.so` files are extracted from GAMA's OpenGL plugin and baked into `/opt/gama/natives/linux-aarch64/` (JOGL loads natives from `<user.dir>/natives/linux-aarch64` when the temp-jar cache is disabled). |

The archive is published as the `rootfs_tar_gz` asset of the **rootfs** release
(`https://github.com/hqnghi88/agama/releases/download/rootfs/rootfs_tar_gz`) and re-uploaded with:

```bash
./scripts/github-release-rootfs.sh
```

## Releases

Artifacts live on the **`v0.2.1`** GitHub release:

- `app-release.apk` — installable Android APK
- `app-release.aab` — Google Play bundle
- `rootfs_tar_gz` (rootfs release) — the runtime rootfs archive

| Settings | Value |
|----------|-------|
| applicationId | `com.simulation.mobile` |
| versionName | `0.2.1` |
| versionCode | current (bumped on every published change) |
| min/target | Android API 34 (min), 36 (target) |

## Known caveats

- **Software rendering only** — OpenGL runs through Mesa llvmpipe (GL 3.3 compatibility). Vulkan/Zink are not viable inside PRoot (no `/dev/dri`/Turnip). Performance is CPU-bound.
- **First launch is long** — the emulator extracts the ~700 MB archive in ~12 minutes; a device is faster. Progress is streamed to the boot console so it doesn't look frozen.
- **Networking inside the guest** is best-effort (PRoot + Android); rely on the app's own connectivity, not apt-get inside the rootfs.
- The X server (Xvfb) and VNC (`5901`) bind to the device's loopback — the desktop is only reachable from the device itself unless tunnelled.

## Testing on an emulator

The AVD has no working DNS, so the GitHub download can't be exercised there; pre-place the archive instead:

```bash
adb uninstall com.simulation.mobile
adb install android/app/build/outputs/apk/debug/app-debug.apk
adb push rootfs.tar.gz /data/local/tmp/rootfs.tar.gz
adb shell "run-as com.simulation.mobile mkdir -p files \
  && run-as com.simulation.mobile cp /data/local/tmp/rootfs.tar.gz files/rootfs.tar.gz \
  && run-as com.simulation.mobile chmod 600 files/rootfs.tar.gz"
adb shell am start -n com.simulation.mobile/.MainActivity
```

Guest logs land in `/data/user/0/com.simulation.mobile/files/rootfs/opt/gama/logs/`
(`gama.log`, `bridge.log`, `x11vnc.err`, `xserver.log`) and the Eclipse workspace log at
`…/files/rootfs/data/Gama_Workspace/.metadata/.log` — the first place to look for 3D/OpenGL issues.