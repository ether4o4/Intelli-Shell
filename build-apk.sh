#!/bin/bash

# IntelliShell APK Build Script
# Usage: ./build-apk.sh [debug|release]
# Default: release

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
OUTPUT_DIR="$PROJECT_ROOT/output"
BUILD_TYPE="${1:-release}"

# Validate build type
if [[ "$BUILD_TYPE" != "debug" && "$BUILD_TYPE" != "release" ]]; then
    echo -e "${RED}Error: Invalid build type. Use 'debug' or 'release'${NC}"
    exit 1
fi

echo -e "${BLUE}=======================================${NC}"
echo -e "${BLUE}  IntelliShell APK Build Script      ${NC}"
echo -e "${BLUE}  Build Type: $BUILD_TYPE              ${NC}"
echo -e "${BLUE}=======================================${NC}"
echo ""

# Check prerequisites
check_prerequisites() {
    echo -e "${YELLOW}Checking prerequisites...${NC}"
    
    # Check for Android SDK
    if [ -z "$ANDROID_SDK_ROOT" ] && [ -z "$ANDROID_HOME" ]; then
        echo -e "${RED}Error: ANDROID_SDK_ROOT or ANDROID_HOME not set${NC}"
        echo -e "${YELLOW}Please set one of these environment variables to your Android SDK path${NC}"
        exit 1
    fi
    
    ANDROID_SDK="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
    echo -e "${GREEN}✓ Android SDK: $ANDROID_SDK${NC}"
    
    # Check for Java
    if ! command -v java &>/dev/null; then
        echo -e "${RED}Error: Java not found${NC}"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    echo -e "${GREEN}✓ Java version: $JAVA_VERSION${NC}"
    
    # Check for Node.js
    if ! command -v node &>/dev/null; then
        echo -e "${RED}Error: Node.js not found${NC}"
        exit 1
    fi
    
    NODE_VERSION=$(node --version)
    echo -e "${GREEN}✓ Node.js version: $NODE_VERSION${NC}"
    
    # Check Android SDK components
    if [ ! -d "$ANDROID_SDK/build-tools" ]; then
        echo -e "${RED}Error: Android Build Tools not found${NC}"
        exit 1
    fi
    
    if [ ! -d "$ANDROID_SDK/platforms" ]; then
        echo -e "${RED}Error: Android Platforms not found${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ All prerequisites met${NC}"
    echo ""
}

# Create debug keystore if needed
setup_keystore() {
    echo -e "${YELLOW}Setting up keystore...${NC}"
    
    cd "$ANDROID_DIR/app"
    
    if [ ! -f "debug.keystore" ]; then
        echo -e "${YELLOW}Creating debug.keystore...${NC}"
        keytool -genkey -v \
            -keystore debug.keystore \
            -alias androiddebugkey \
            -storepass android \
            -keypass android \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US"
        echo -e "${GREEN}✓ Debug keystore created${NC}"
    else
        echo -e "${GREEN}✓ Debug keystore exists${NC}"
    fi
    
    if [ "$BUILD_TYPE" == "release" ] && [ ! -f "release.keystore" ]; then
        echo -e "${YELLOW}Creating release.keystore...${NC}"
        keytool -genkey -v \
            -keystore release.keystore \
            -alias intellishell \
            -storepass intellishell \
            -keypass intellishell \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -dname "CN=IntelliShell,O=IntelliShell,C=US"
        echo -e "${GREEN}✓ Release keystore created${NC}"
        echo -e "${YELLOW}Note: For production, replace with your official signing key${NC}"
    fi
    
    cd "$PROJECT_ROOT"
    echo ""
}

# Clean previous builds
clean_builds() {
    echo -e "${YELLOW}Cleaning previous builds...${NC}"
    
    cd "$ANDROID_DIR"
    
    # Clean gradle builds
    ./gradlew clean 2>/dev/null || ./gradlew clean
    
    # Clean output directory
    rm -rf "$OUTPUT_DIR"
    mkdir -p "$OUTPUT_DIR"
    
    cd "$PROJECT_ROOT"
    
    echo -e "${GREEN}✓ Clean completed${NC}"
    echo ""
}

# Bundle JavaScript assets
bundle_assets() {
    echo -e "${YELLOW}Bundling JavaScript assets...${NC}"
    
    # Check if index.js exists
    if [ ! -f "$PROJECT_ROOT/index.js" ]; then
        echo -e "${YELLOW}Warning: index.js not found, creating minimal entry point...${NC}"
        mkdir -p "$PROJECT_ROOT"
        cat > "$PROJECT_ROOT/index.js" << 'EOF'
import { AppRegistry } from 'react-native';
import { name as appName } from './app.json';

// Minimal placeholder app
const App = () => null;

AppRegistry.registerComponent(appName, () => App);
EOF
    fi
    
    # Check if react-native CLI is available
    if [ -f "$PROJECT_ROOT/node_modules/.bin/react-native" ]; then
        cd "$PROJECT_ROOT"
        npx react-native bundle \
            --platform android \
            --dev false \
            --entry-file index.js \
            --bundle-output "$ANDROID_DIR/app/src/main/assets/index.android.bundle" \
            --assets-dest "$ANDROID_DIR/app/src/main/res" \
            --reset-cache
        echo -e "${GREEN}✓ Assets bundled${NC}"
    else
        echo -e "${YELLOW}Warning: React Native not installed. Skipping asset bundling.${NC}"
        echo -e "${YELLOW}Run 'npm install' or 'yarn install' first for full build.${NC}"
        
        # Create empty bundle placeholder
        mkdir -p "$ANDROID_DIR/app/src/main/assets"
        echo "// Placeholder bundle - run npm install for actual build" > "$ANDROID_DIR/app/src/main/assets/index.android.bundle"
    fi
    
    echo ""
}

# Build APK
build_apk() {
    echo -e "${YELLOW}Building $BUILD_TYPE APK...${NC}"
    
    cd "$ANDROID_DIR"
    
    if [ "$BUILD_TYPE" == "release" ]; then
        ./gradlew assembleRelease
    else
        ./gradlew assembleDebug
    fi
    
    cd "$PROJECT_ROOT"
    
    echo -e "${GREEN}✓ Build completed${NC}"
    echo ""
}

# Copy outputs
copy_outputs() {
    echo -e "${YELLOW}Copying APKs to output directory...${NC}"
    
    # Find and copy APK files
    find "$ANDROID_DIR/app/build/outputs/apk" -name "*.apk" -type f | while read apk; do
        echo -e "${BLUE}Found: $(basename "$apk")${NC}"
        cp "$apk" "$OUTPUT_DIR/"
    done
    
    # Copy mapping file for release builds
    if [ "$BUILD_TYPE" == "release" ]; then
        MAPPING_FILE="$ANDROID_DIR/app/build/outputs/mapping/release/mapping.txt"
        if [ -f "$MAPPING_FILE" ]; then
            cp "$MAPPING_FILE" "$OUTPUT_DIR/mapping.txt"
            echo -e "${GREEN}✓ Mapping file copied${NC}"
        fi
    fi
    
    echo ""
    echo -e "${GREEN}=======================================${NC}"
    echo -e "${GREEN}  Build Successful!                    ${NC}"
    echo -e "${GREEN}=======================================${NC}"
    echo ""
    echo -e "${BLUE}Output files:${NC}"
    ls -lh "$OUTPUT_DIR/"
    echo ""
}

# Main execution
main() {
    # Record start time
    START_TIME=$(date +%s)
    
    # Run all steps
    check_prerequisites
    setup_keystore
    clean_builds
    bundle_assets
    build_apk
    copy_outputs
    
    # Calculate duration
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    MINUTES=$((DURATION / 60))
    SECONDS=$((DURATION % 60))
    
    echo -e "${GREEN}Total build time: ${MINUTES}m ${SECONDS}s${NC}"
    echo ""
}

# Run main function
main
