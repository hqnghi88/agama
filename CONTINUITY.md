# goal
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
