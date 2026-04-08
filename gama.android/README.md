# Gama Platform - Android Wrapper

This project "envelops" the ARM (aarch64) build of GAMA to run as an APK.

## Prerequisites

1.  **GAMA for Linux aarch64**: Build it using `bash travis/build.sh`.
2.  **Android SDK**: Needed for building the APK.

## How to Build

1.  Bundle GAMA into Android assets:
    ```bash
    ./build_apk.sh
    ```
2.  Assemble the APK (requires Gradle & Android SDK):
    ```bash
    cd gama.android
    ./gradlew assembleDebug
    ```

## Execution Details

-   On its first run, the app extracts `gama_aarch64.tar.gz` from the assets to its internal storage (`/data/data/org.gama.android/files/gama`).
-   It sets the executable bit and attempts to execute the `Gama` entry point.
-   **Note**: Since this is a GUI application, it requires an X server app (like XServer XSDL) to be running on the Android device, with `DISPLAY` set correctly (e.g., `:0`).
-   The current `MainActivity` is a skeleton that shows process output.

## Troubleshooting

-   **Linker Errors**: If the binary complains about `libc.so.6`, you may need a Proot-based compatibility layer included in the APK to provide a GLibc environment.
-   **Permissions**: Ensure the device has enough storage to extract the ~500MB GAMA product.
