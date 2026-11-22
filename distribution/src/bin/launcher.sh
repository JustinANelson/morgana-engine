#!/bin/sh
# @DESCRIPTION@
# Part of the Morgana ORPG SDK @VERSION@. Runs from the SDK folder, so relative paths (games/, worlds/, server-data/)
# always mean the folders next to bin/. Needs Java 17 or newer: set JAVA_HOME, or put java on PATH.
# Extra JVM options can be passed in MORGANA_JAVA_OPTS (for example -Xmx2g).
MORGANA_HOME=$(cd "$(dirname "$0")/.." && pwd) || exit 1
cd "$MORGANA_HOME" || exit 1

if [ -n "$JAVA_HOME" ]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
else
    JAVA_EXE=java
fi
if ! "$JAVA_EXE" -version >/dev/null 2>&1; then
    echo "Morgana needs Java 17 or newer. Install a JDK or JRE (for example Eclipse Temurin 17) and set JAVA_HOME or add java to PATH." >&2
    exit 1
fi

EXTRA_OPTS=""
# LWJGL windows must be created on the first thread on macOS.
if [ "@NEEDS_WINDOW@" = "yes" ] && [ "$(uname -s)" = "Darwin" ]; then
    EXTRA_OPTS="-XstartOnFirstThread"
fi

# shellcheck disable=SC2086
exec "$JAVA_EXE" $EXTRA_OPTS $MORGANA_JAVA_OPTS -cp "$MORGANA_HOME/lib/*" @MAIN@ "$@"
