# GAMA Mobile — Build from Fresh Checkout

## Quick Start (10 min)

```bash
./scripts/build.sh all     # Full build: check → deps → rootfs → APK
./scripts/build.sh install # Build + install on connected device via ADB
./scripts/build.sh help    # Show all commands
```

This is the only command you need. It handles everything:
- Checks prerequisites (JDK 21, Node.js, npm, Docker, Android SDK)
- Creates `android/local.properties` if missing
- Runs `npm install` (reproducible via `package-lock.json`)
- Uses the pre-built rootfs from git (or rebuilds it via Docker)
- Compiles the APK

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | ≥ 21 | Eclipse Temurin recommended (used in CI) |
| Node.js | ≥ 18 | LTS version |
| npm | ≥ 9 | Ships with Node.js |
| Docker | latest | Only needed to rebuild rootfs (optional) |
| Android SDK | platforms;android-34, build-tools;34.0.0 | Set ANDROID_HOME |
| ADB | latest | For device install (optional) |

## Build Output

```
android/app/build/outputs/apk/debug/app-debug.apk
```

The APK bundles everything needed at runtime:
- Rootfs archive (`res/raw/rootfs_tar_gz`) — Debian Bookworm ARM64 with Java 25 Temurin JRE + Python bridge
- PRoot binaries (`jniLibs/arm64-v8a/`) — Termux proot with seccomp mode
- React Native JS bundle (compiled at build time)

## What the APK Does at Runtime

1. `SimulationService` starts the Kotlin proxy on port 8080
2. `PRootManager` extracts rootfs + starts PRoot container
3. Inside PRoot: `startup.sh` → bridge server on 8081 + GAMA headless (optional)
4. Kotlin proxy forwards 8080 → 8081
5. RN app polls health/status via 8080

## Architecture

- **Kotlin proxy**: `android/app/src/main/java/.../service/FallbackHealthServer.kt` — raw socket forwarding
- **PRoot manager**: `android/app/src/main/java/.../service/PRootManager.kt` — bind mounts, env, process management
- **Bridge server**: `proot-setup/bridge-server.py` — Python HTTP/WS bridge simulates GAMA progress
- **RN frontend**: `src/screens/DashboardScreen.tsx` — status polling, progress bar, result display

## Key Files

| Path | Purpose |
|------|---------|
| `android/app/src/main/res/raw/rootfs_tar_gz` | Pre-built ARM64 rootfs (97 MB, tracked in git) |
| `android/app/src/main/jniLibs/arm64-v8a/libproot.so` | PRoot binary for Android |
| `proot-setup/startup.sh` | Entrypoint run inside PRoot at boot |
| `proot-setup/bridge-server.py` | HTTP-to-GAMA-WebSocket bridge |
| `proot-setup/java-env.sh` | Java environment (Java 25 Temurin) |
| `proot-setup/gama-launcher.sh` | GAMA headless product launcher |
| `scripts/build.sh` | One-command build orchestrator |
| `scripts/build-rootfs.sh` | Docker-based rootfs builder |

## Advanced: Rebuild Rootfs from Scratch

The rootfs archive is pre-built and tracked in git. To rebuild it:

```bash
docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
./scripts/build-rootfs.sh
```

This uses Docker with QEMU to build an ARM64 Debian Bookworm rootfs with:
- Java 25 Temurin JRE
- Python 3 + websockets
- GAMA headless product (if pre-built)
- Bridge server + launcher scripts

## Common Issues

- **JDK version**: If `javac --version` shows < 21, set `JAVA_HOME` to a JDK 21+ installation
- **Android SDK**: If `ANDROID_HOME` is not set, the script tries `~/Library/Android/sdk` (macOS default)
- **Docker on macOS**: Ensure Docker Desktop is running with QEMU support enabled
- **ADB device not found**: Enable USB debugging, accept RSA fingerprint
