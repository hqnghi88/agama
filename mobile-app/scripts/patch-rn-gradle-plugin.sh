#!/bin/bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════════════
#  Patch @react-native/gradle-plugin in node_modules for the JDK 25 /
#  Gradle 9.1 build. npm install pulls 0.74.87 which:
#    * uses the Gradle-9-removed `serviceOf` internal API in its build
#      script (only needed for the plugin's own unit tests),
#    * pulls the too-old foojay-resolver-convention 0.5.0 (uses the
#      Gradle-9-removed JvmVendorSpec.IBM_SEMERU field),
#    * pins Kotlin 1.9.22 / toolchain 17 whose compiler crashes on
#      JDK 25 (JavaVersion.parse cannot handle the 4-part version
#      string; Kotlin >= 2.1.21 handles JDK 25),
#    * uses the Gradle-9-removed Project.exec in BundleHermesCTask.
#  Run after every `npm install`. Idempotent: just copies the patched
#  files from scripts/patches/rn-gradle-plugin over node_modules.
# ═══════════════════════════════════════════════════════════════════════

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOBILE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PLUGIN_DIR="${MOBILE_ROOT}/node_modules/@react-native/gradle-plugin"
PATCH_DIR="${SCRIPT_DIR}/patches/rn-gradle-plugin"

if [ ! -d "${PLUGIN_DIR}" ]; then
  echo "[rn-plugin-patch] @react-native/gradle-plugin not installed — run npm install first" >&2
  exit 1
fi

for f in \
    "settings.gradle.kts" \
    "gradle/libs.versions.toml" \
    "react-native-gradle-plugin/build.gradle.kts" \
    "react-native-gradle-plugin/src/main/kotlin/com/facebook/react/tasks/BundleHermesCTask.kt"; do
  cp "${PATCH_DIR}/${f}" "${PLUGIN_DIR}/${f}"
  echo "[rn-plugin-patch] patched ${f}"
done

echo "[rn-plugin-patch] done"
