.PHONY: all setup build-backend build-rootfs build-proot build-android \
        build-backend-jar package-gama test clean install-deps help

SHELL := /bin/bash
PROJECT_ROOT := $(shell pwd)
GAMA_ROOT := $(shell cd .. && pwd)

help:
	@echo "GAMA Mobile Build System"
	@echo ""
	@echo "Targets:"
	@echo "  all              - Full build: deps, rootfs, proot, backend, android"
	@echo "  setup            - Install JS dependencies"
	@echo "  build-backend    - Build Java backend JAR"
	@echo "  build-rootfs     - Build Debian ARM64 rootfs (requires Docker)"
	@echo "  build-proot      - Download/build PRoot ARM64 binary"
	@echo "  package-gama     - Package GAMA headless product into app"
	@echo "  build-android    - Build Android APK"
	@echo "  test             - Run all tests"
	@echo "  test-backend     - Test backend server locally"
	@echo "  clean            - Clean all build artifacts"
	@echo "  install-deps     - Install all system dependencies"

all: setup build-backend package-gama

# ─── JavaScript Dependencies ───────────────────────────────────────────
setup:
	@echo "[setup] Installing JS dependencies..."
	npm install
	@echo "[setup] Done"

# ─── Java Backend ──────────────────────────────────────────────────────
build-backend:
	@echo "[backend] Building API server JAR..."
	cd backend && ./gradlew build -x test
	@echo "[backend] JAR at backend/api-server/build/libs/api-server-uber.jar"

# ─── GAMA Headless Product Packaging ───────────────────────────────────
GAMA_PRODUCT_DIR := $(GAMA_ROOT)/gama.product/target/products/gama.headless.product/linux/gtk/aarch64
GAMA_ARCHIVE := $(GAMA_ROOT)/gama.product/target/products/gama.headless-linux.gtk.aarch64.tar.gz
ROOTFS_BUILD_DIR := build/rootfs
PROOT_SETUP_DIR := proot-setup

.PHONY: check-gama-product

check-gama-product:
	@if [ ! -d "$(GAMA_PRODUCT_DIR)" ] && [ ! -f "$(GAMA_ARCHIVE)" ]; then \
		echo "ERROR: GAMA headless product not found."; \
		echo "Build it first:"; \
		echo "  cd $(GAMA_ROOT)/gama.parent && mvn clean install"; \
		exit 1; \
	fi

package-gama: check-gama-product
	@echo "[gama] Packaging GAMA headless product..."
	mkdir -p $(ROOTFS_BUILD_DIR)/opt/gama/headless
	if [ -d "$(GAMA_PRODUCT_DIR)" ]; then \
		echo "[gama] Copying from product directory..."; \
		cp -r "$(GAMA_PRODUCT_DIR)/"* $(ROOTFS_BUILD_DIR)/opt/gama/headless/; \
	elif [ -f "$(GAMA_ARCHIVE)" ]; then \
		echo "[gama] Extracting from archive..."; \
		tar xzf "$(GAMA_ARCHIVE)" -C $(ROOTFS_BUILD_DIR)/opt/gama/headless/; \
	fi
	@echo "[gama] Copying launcher scripts..."
	cp $(PROOT_SETUP_DIR)/gama-launcher.sh $(ROOTFS_BUILD_DIR)/opt/gama/
	cp $(PROOT_SETUP_DIR)/bridge-server.py $(ROOTFS_BUILD_DIR)/opt/gama/
	cp $(PROOT_SETUP_DIR)/java-env.sh $(ROOTFS_BUILD_DIR)/opt/gama/
	cp $(PROOT_SETUP_DIR)/java-env.sh $(ROOTFS_BUILD_DIR)/
	cp $(PROOT_SETUP_DIR)/startup.sh $(ROOTFS_BUILD_DIR)/
	chmod +x $(ROOTFS_BUILD_DIR)/opt/gama/*.sh
	@echo "[gama] Setting up GAMA configuration for mobile..."
	@# Adjust memory settings for mobile
	@echo "[gama] Product packaged at $(ROOTFS_BUILD_DIR)"

# ─── Rootfs ────────────────────────────────────────────────────────────
build-rootfs: package-gama
	@echo "[rootfs] Building Debian ARM64 rootfs..."
	./scripts/build-rootfs.sh $(ROOTFS_BUILD_DIR)
	@echo "[rootfs] Rootfs archive at build/rootfs.tar.gz"

# ─── PRoot ─────────────────────────────────────────────────────────────
PROOT_VERSION := 5.4.0
PROOT_DIR := build/proot

build-proot:
	@echo "[proot] Setting up PRoot ARM64 binary..."
	./scripts/download-proot.sh build/proot
	@echo "[proot] PRoot binary at build/proot/proot_arm64"

# ─── Android ───────────────────────────────────────────────────────────
build-android: setup
	@echo "[android] Building APK..."
	JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
	cd android && ./gradlew assembleDebug
	@echo "[android] APK at android/app/build/outputs/apk/debug/app-debug.apk"

# ─── Testing ───────────────────────────────────────────────────────────
test:
	@echo "[test] Running API tests..."
	npx jest --passWithNoTests

test-backend:
	@echo "[test] Testing backend server..."
	./scripts/test-backend.sh

# ─── Clean ─────────────────────────────────────────────────────────────
clean:
	@echo "[clean] Cleaning build artifacts..."
	rm -rf build/
	rm -rf node_modules/
	cd android && ./gradlew clean 2>/dev/null || true
	cd backend && ./gradlew clean 2>/dev/null || true

clean-all: clean
	@echo "[clean] Deep clean..."
	rm -rf android/.gradle/
	rm -rf android/app/build/
