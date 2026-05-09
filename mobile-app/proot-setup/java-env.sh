#!/bin/bash
# Java environment configuration for GAMA Mobile backend
# Source this file to set up Java paths and JVM options

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export JDK_HOME=${JAVA_HOME}
export JRE_HOME=${JAVA_HOME}

export PATH=${JAVA_HOME}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# JVM tuning for mobile/ARM64 - optimized for limited resources
export JAVA_OPTS="\
  -Xms64m \
  -Xmx512m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UseStringDeduplication \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.awt.headless=true \
  -Dfile.encoding=UTF-8 \
  -Dorg.eclipse.swt.internal.gtk.cairoGraphics=false \
"

# GAMA-specific options for headless mode
export GAMA_OPTS="\
  -Dgama.application.ui=false \
  -Dgama.experiment.ui=false \
  -Dgama.headless=true \
  -Dgama.workspace=/workspace \
  -Dosgi.locking=none \
  -Dosgi.checkConfiguration=false \
"

export _JAVA_OPTIONS="${JAVA_OPTS} ${GAMA_OPTS}"

echo "[java-env] JAVA_HOME=${JAVA_HOME}"
echo "[java-env] Java version: $(${JAVA_HOME}/bin/java -version 2>&1 | head -1)"
echo "[java-env] JVM options: ${_JAVA_OPTIONS}"
