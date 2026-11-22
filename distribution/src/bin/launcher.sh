#!/bin/sh
# @DESCRIPTION@
# Part of the Morgana Engine SDK @VERSION@. Runs from the SDK folder, so relative paths (games/, worlds/, server-data/)
# always mean the folders next to bin/. Automatically downloads Eclipse Temurin JRE 17 into .jre/jre-17 if Java 17+ is missing.
# Extra JVM options can be passed in MORGANA_JAVA_OPTS (for example -Xmx2g).
set -e
MORGANA_HOME=$(cd "$(dirname "$0")/.." && pwd) || exit 1
cd "$MORGANA_HOME" || exit 1

LOCAL_JRE="$MORGANA_HOME/.jre/jre-17"
JAVA_EXE=""

java_major_version() {
    "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*".*/\1/p' | head -n 1
}

if [ -x "$LOCAL_JRE/bin/java" ]; then
    JAVA_EXE="$LOCAL_JRE/bin/java"
elif [ -x "$LOCAL_JRE/Contents/Home/bin/java" ]; then
    JAVA_EXE="$LOCAL_JRE/Contents/Home/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    v=$(java_major_version "$JAVA_HOME/bin/java" || true)
    if [ -n "$v" ] && [ "$v" -ge 17 ] 2>/dev/null; then JAVA_EXE="$JAVA_HOME/bin/java"; fi
elif command -v java >/dev/null 2>&1; then
    v=$(java_major_version java || true)
    if [ -n "$v" ] && [ "$v" -ge 17 ] 2>/dev/null; then JAVA_EXE="java"; fi
fi

if [ -z "$JAVA_EXE" ]; then
    OS_RAW=$(uname -s)
    ARCH_RAW=$(uname -m)
    case "$OS_RAW" in
        Darwin) OS=mac ;;
        Linux)  OS=linux ;;
        *) echo "Morgana needs Java 17+. Install Eclipse Temurin 17 from https://adoptium.net/" >&2; exit 1 ;;
    esac
    case "$ARCH_RAW" in
        x86_64|amd64) ARCH=x64 ;;
        arm64|aarch64) ARCH=aarch64 ;;
        *) echo "Morgana needs Java 17+. Install Eclipse Temurin 17 from https://adoptium.net/" >&2; exit 1 ;;
    esac

    URL="https://api.adoptium.net/v3/binary/latest/17/ga/$OS/$ARCH/jre/hotspot/normal/eclipse"
    echo "Java 17+ was not found. Downloading Eclipse Temurin JRE 17 ($OS/$ARCH) into .jre/jre-17 ..."
    mkdir -p "$MORGANA_HOME/.jre"
    ARCHIVE="$MORGANA_HOME/.jre/temurin-jre17.tar.gz"
    TMP_DIR="$MORGANA_HOME/.jre/extract-tmp"
    rm -rf "$TMP_DIR" "$LOCAL_JRE"
    mkdir -p "$TMP_DIR"

    if command -v curl >/dev/null 2>&1; then
        curl -fSL "$URL" -o "$ARCHIVE"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$ARCHIVE" "$URL"
    else
        echo "Morgana needs Java 17+. Install Eclipse Temurin 17 from https://adoptium.net/" >&2
        exit 1
    fi

    tar -xzf "$ARCHIVE" -C "$TMP_DIR"
    INNER=$(find "$TMP_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    mv "$INNER" "$LOCAL_JRE"
    rm -rf "$ARCHIVE" "$TMP_DIR"

    if [ -x "$LOCAL_JRE/Contents/Home/bin/java" ]; then
        JAVA_EXE="$LOCAL_JRE/Contents/Home/bin/java"
    else
        JAVA_EXE="$LOCAL_JRE/bin/java"
    fi
fi

EXTRA_OPTS=""
# LWJGL windows must be created on the first thread on macOS.
if [ "@NEEDS_WINDOW@" = "yes" ] && [ "$(uname -s)" = "Darwin" ]; then
    EXTRA_OPTS="-XstartOnFirstThread"
fi

# shellcheck disable=SC2086
exec "$JAVA_EXE" $EXTRA_OPTS $MORGANA_JAVA_OPTS -cp "$MORGANA_HOME/lib/*" @MAIN@ "$@"
