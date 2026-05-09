# goal
<<<<<<< HEAD
- Convert GAMA to a "pure Android" app (no PRoot/Termux).
- Native Android 16 (API 36) support.
- Headless first, with full UI planned for the future.

# constraints/assumptions
- GAMA engine will run directly on Android Runtime (ART).
- Use `GamlStandaloneSetup` to bypass Equinox registry requirements.
- Target architecture: arm64-v8a (native Android).

# key decisions
- Created a fresh `gama.android` project as the previous PRoot-based version was deemed irrelevant for the "pure" goal.
- Bundling GAMA core and dependency JARs into `libs/` for direct classpath access.
- Implementing a `MainActivity` that boots the GAMA platform in a separate thread.

# state
- Done: Scaffolding for `gama.android` (Gradle, AndroidManifest, Resources).
- Done: Implemented basic `MainActivity.java` with GAMA initialization logic.
- Done: Copied ~150 JARs from GAMA Headless product to `libs/`.
- Now: Building the initial native APK to verify dependency compatibility.
- Next: Implement a simple headless simulation run within the app (e.g., a GAML "Hello World").
- Next: Address any Java library incompatibilities (AWT, restricted APIs) on Android.

# open questions (UNCONFIRMED)
- Why are source files like `MainActivity.java` and `build.gradle` not visible in the local filesystem despite being in IDE metadata?
- Does "native" imply move away from the Ubuntu/PRoot virtualized environment towards a pure Android (AAR/APK) lifecycle?
- What specific feature of Android 16 is being targeted (e.g., the new security sandbox)?

# working set
- gama.android/app/src/main/java/org/gama/android/MainActivity.java (from metadata)
- gama.android/app/build.gradle (from metadata)
- gama.android/app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
=======
- Setup an Android project to "envelop and run" the ARM (aarch64) build of GAMA.
- Current build location: `gama.product/target/products/gama.ui.application.product/linux/gtk/aarch64`.
- The Android project should build an APK that packages these binaries and provides a way to execute them.

# constraints/assumptions
- GAMA is an Eclipse RCP app (requires GTK/X11 environment).
- Directly executing GLibc binaries on Bionic (Android) requires a compatibility layer like Proot or a specialized environment.
- The user has already built GAMA for aarch64.
- Target platform: Android (ARMv8-A/aarch64).

# key decisions
- Create a new Android project in `gama.android`.
- Package GAMA binaries as a compressed asset.
- Implement an extraction logic in the Android app.
- Provide a simple Activity/Fragment skeleton for execution.
- Use Gradle to automate the "bundling" of the Linux binaries into the APK.

# state
- Done: Identified current GAMA product structure.
- Now: Creating Android project structure in `gama.android`.
- Next: Implement shell script/task to sync Linux builds to Android assets.

# working set
- gama.android/
- travis/build.sh
- gama.product/target/products/gama.ui.application.product
>>>>>>> fe1d52c7d8a03bc903cfcd328c10bd3c93a6c74c
