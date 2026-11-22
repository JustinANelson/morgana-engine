#!/bin/sh
# Zero-setup launcher for Morgana Engine (macOS / Linux).
# Automatically downloads a local Eclipse Temurin JDK 17 into .toolchain/jdk-17 if Java 17+ is not installed,
# then runs ./gradlew. With no arguments, launches the Desktop Client (:client-desktop:run).
set -e

REPO_ROOT=$(cd "$(dirname "$0")" && pwd)
cd "$REPO_ROOT"

LOCAL_JDK="$REPO_ROOT/.toolchain/jdk-17"

java_major_version() {
    "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*".*/\1/p' | head -n 1
}

find_java17() {
    if [ -x "$LOCAL_JDK/bin/java" ]; then
        JAVA_HOME="$LOCAL_JDK"
        export JAVA_HOME
        return 0
    fi
    if [ -x "$LOCAL_JDK/Contents/Home/bin/java" ]; then
        JAVA_HOME="$LOCAL_JDK/Contents/Home"
        export JAVA_HOME
        return 0
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        v=$(java_major_version "$JAVA_HOME/bin/java" || true)
        if [ -n "$v" ] && [ "$v" -ge 17 ] 2>/dev/null; then
            return 0
        fi
    fi
    if command -v java >/dev/null 2>&1; then
        v=$(java_major_version java || true)
        if [ -n "$v" ] && [ "$v" -ge 17 ] 2>/dev/null; then
            return 0
        fi
    fi
    return 1
}

if ! find_java17; then
    OS_RAW=$(uname -s)
    ARCH_RAW=$(uname -m)
    case "$OS_RAW" in
        Darwin) OS=mac ;;
        Linux)  OS=linux ;;
        *) echo "Unsupported OS '$OS_RAW' for automatic JDK download. Please install Java 17+ from https://adoptium.net/" >&2; exit 1 ;;
    esac
    case "$ARCH_RAW" in
        x86_64|amd64) ARCH=x64 ;;
        arm64|aarch64) ARCH=aarch64 ;;
        *) echo "Unsupported architecture '$ARCH_RAW' for automatic JDK download. Please install Java 17+ from https://adoptium.net/" >&2; exit 1 ;;
    esac

    URL="https://api.adoptium.net/v3/binary/latest/17/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse"
    echo "Java 17+ was not found. Downloading Eclipse Temurin JDK 17 ($OS/$ARCH) into .toolchain/jdk-17 ..."
    mkdir -p "$REPO_ROOT/.toolchain"
    ARCHIVE="$REPO_ROOT/.toolchain/temurin-jdk17.tar.gz"
    TMP_DIR="$REPO_ROOT/.toolchain/extract-tmp"
    rm -rf "$TMP_DIR" "$LOCAL_JDK"
    mkdir -p "$TMP_DIR"

    if command -v curl >/dev/null 2>&1; then
        curl -fSL "$URL" -o "$ARCHIVE"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$ARCHIVE" "$URL"
    else
        echo "Neither curl nor wget is available. Please install Java 17+ from https://adoptium.net/" >&2
        exit 1
    fi

    tar -xzf "$ARCHIVE" -C "$TMP_DIR"
    INNER=$(find "$TMP_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    mv "$INNER" "$LOCAL_JDK"
    rm -rf "$ARCHIVE" "$TMP_DIR"

    if [ -x "$LOCAL_JDK/Contents/Home/bin/java" ]; then
        JAVA_HOME="$LOCAL_JDK/Contents/Home"
    else
        JAVA_HOME="$LOCAL_JDK"
    fi
    export JAVA_HOME
fi

if [ "$#" -eq 0 ]; then
    exec "$REPO_ROOT/gradlew" :client-desktop:run
else
    exec "$REPO_ROOT/gradlew" "$@"
fi
