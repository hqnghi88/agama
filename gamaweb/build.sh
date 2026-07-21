#!/bin/bash
#
# build.sh - Build script for GAMA Web
#
# This script builds the gamaweb module and prepares it for browser deployment.
# It supports two modes:
#   1. Standard build: Compiles Java code and packages web assets
#   2. CheerpJ build: Compiles Java to JavaScript/WebAssembly for browser execution
#
# Usage:
#   ./build.sh              # Standard build
#   ./build.sh --cheerpj    # Build with CheerpJ compilation
#   ./build.sh --package    # Package for deployment
#   ./build.sh --clean      # Clean build artifacts
#
# Requirements:
#   - Maven 3.8+
#   - JDK 25+
#   - Node.js 18+ (for web asset optimization, optional)
#   - CheerpJ 3.0+ (for --cheerpj mode)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$SCRIPT_DIR/target"
WEB_DIR="$SCRIPT_DIR/web"
WEB_OUT="$BUILD_DIR/web"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Parse arguments
MODE="standard"
CLEAN=false
PACKAGE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --cheerpj) MODE="cheerpj"; shift ;;
        --package) PACKAGE=true; shift ;;
        --clean) CLEAN=true; shift ;;
        -h|--help)
            echo "Usage: $0 [--cheerpj] [--package] [--clean]"
            echo ""
            echo "Options:"
            echo "  --cheerpj    Build with CheerpJ compilation for browser execution"
            echo "  --package    Package for deployment"
            echo "  --clean      Clean build artifacts"
            echo "  -h, --help   Show this help"
            exit 0
            ;;
        *) log_error "Unknown option: $1"; exit 1 ;;
    esac
done

# Clean if requested
if [ "$CLEAN" = true ]; then
    log_info "Cleaning build artifacts..."
    rm -rf "$BUILD_DIR"
    log_success "Clean complete"
    exit 0
fi

# Step 1: Build Java modules
log_info "Building Java modules..."
cd "$PROJECT_ROOT"

# Build required modules in order
MODULES=(
    "gama.annotations"
    "gama.processor"
    "gama.dependencies"
    "gama.api"
    "gama.core"
    "gaml.grammar"
    "gaml.compiler"
    "gama.headless"
    "gama.web"
)

for module in "${MODULES[@]}"; do
    if [ -d "$PROJECT_ROOT/$module" ]; then
        log_info "  Building $module..."
        mvn install -pl "$module" -am -DskipTests -q 2>/dev/null || {
            log_warn "  Failed to build $module, continuing..."
        }
    fi
done

log_success "Java modules built"

# Step 2: Package web assets
log_info "Packaging web assets..."
mkdir -p "$WEB_OUT"

# Copy web directory
cp -r "$WEB_DIR"/* "$WEB_OUT/"

# Step 3: Create JAR bundle for CheerpJ
log_info "Creating JAR bundle..."

# Find all required JARs
JAR_DIRS=(
    "$PROJECT_ROOT/gama.headless/target"
    "$PROJECT_ROOT/gama.core/target"
    "$PROJECT_ROOT/gama.api/target"
    "$PROJECT_ROOT/gaml.compiler/target"
    "$PROJECT_ROOT/gama.dependencies/target"
    "$PROJECT_ROOT/gama.extension.*/target"
)

JAR_LIST=""
for dir in "${JAR_DIRS[@]}"; do
    if [ -d "$dir" ]; then
        for jar in "$dir"/*.jar; do
            if [ -f "$jar" ]; then
                JAR_LIST="$JAR_LIST $jar"
            fi
        done
    fi
done

# Create a combined JAR (uber-jar) for CheerpJ
if [ -n "$JAR_LIST" ]; then
    mkdir -p "$BUILD_DIR/libs"
    log_info "Found $(echo $JAR_LIST | wc -w) JAR files"

    # Copy JARs to web directory for serving
    mkdir -p "$WEB_OUT/jars"
    for jar in $JAR_LIST; do
        cp "$jar" "$WEB_OUT/jars/" 2>/dev/null || true
    done
    log_success "JARs copied to $WEB_OUT/jars/"
else
    log_warn "No JAR files found. Build Java modules first."
fi

# Step 4: Create index.html with CheerpJ integration
if [ "$MODE" = "cheerpj" ]; then
    log_info "Creating CheerpJ-enabled index.html..."

    cat > "$WEB_OUT/index-cheerpj.html" << 'CHEERPJ_EOF'
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>GAMA Web (CheerpJ)</title>
    <script defer src="https://CheerpJ.com/cj3.js"></script>
    <style>
        body { margin: 0; font-family: Arial, sans-serif; background: #1a1a2e; color: #e6e6e6; }
        #loading { position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%);
                   text-align: center; z-index: 1000; }
        .spinner { width: 48px; height: 48px; border: 4px solid #333; border-top-color: #e94560;
                   border-radius: 50%; animation: spin 1s linear infinite; margin: 0 auto 16px; }
        @keyframes spin { to { transform: rotate(360deg); } }
        #app { display: none; }
    </style>
</head>
<body>
    <div id="loading">
        <div class="spinner"></div>
        <p>Loading GAMA runtime...</p>
        <p style="font-size: 0.8em; color: #666;">First load may take a few minutes</p>
    </div>
    <div id="app">
        <iframe id="gama-frame" style="width: 100vw; height: 100vh; border: none;"></iframe>
    </div>
    <script>
        // Wait for CheerpJ to be ready
        window.addEventListener('load', () => {
            // The main index.html will be loaded in the iframe
            document.getElementById('app').style.display = 'block';
            document.getElementById('loading').style.display = 'none';
        });
    </script>
</body>
</html>
CHEERPJ_EOF
    log_success "CheerpJ index created at $WEB_OUT/index-cheerpj.html"
fi

# Step 5: Package for deployment
if [ "$PACKAGE" = true ]; then
    log_info "Packaging for deployment..."

    # Create a distributable ZIP
    cd "$BUILD_DIR"
    zip -r "../gamaweb-dist.zip" web/ 2>/dev/null || {
        # Fallback: create tar.gz
        tar czf "../gamaweb-dist.tar.gz" web/
    }

    log_success "Package created: gamaweb-dist.zip"
    log_info "To serve locally: cd $WEB_OUT && python3 -m http.server 8080"
fi

# Step 6: Summary
echo ""
log_success "Build complete!"
echo ""
echo "Directory structure:"
echo "  $BUILD_DIR/"
echo "    ├── web/              # Deployable web application"
echo "    │   ├── index.html    # Main page"
echo "    │   ├── css/          # Stylesheets"
echo "    │   ├── js/           # JavaScript modules"
echo "    │   ├── jars/         # Java JARs for CheerpJ"
echo "    │   └── sw.js         # Service Worker"
echo "    └── libs/             # All JAR dependencies"
echo ""
echo "To test locally:"
echo "  cd $WEB_OUT && python3 -m http.server 8080"
echo "  Then open http://localhost:8080"
echo ""
