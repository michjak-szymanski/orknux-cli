#!/bin/sh
# ---------------------------------------------------------------------------
# Builds target/orkx — the same job native.cmd does on Windows, and for the same
# reason: the native-maven-plugin finds native-image through GRAALVM_HOME, so
# this sets it for one build rather than asking you to keep it in your
# environment.
#
# Arguments are passed on to Maven: `./native.sh -DskipTests`.
# ---------------------------------------------------------------------------
set -eu

here=$(dirname "$0")

usable() {
    # The .cmd is for anyone running this from a POSIX shell on Windows, Git Bash included.
    [ -x "$1/bin/native-image" ] || [ -f "$1/bin/native-image.cmd" ]
}

if [ -n "${GRAALVM_HOME:-}" ]; then
    if ! usable "$GRAALVM_HOME"; then
        echo "GRAALVM_HOME is set to '$GRAALVM_HOME', which has no bin/native-image." >&2
        exit 1
    fi
else
    # Newest first, so an upgraded GraalVM is picked up without editing anything.
    for candidate in $(ls -d "$HOME"/.jdks/graalvm* "$HOME"/.sdkman/candidates/java/*graal* 2>/dev/null | sort -r); do
        if usable "$candidate"; then
            GRAALVM_HOME=$candidate
            export GRAALVM_HOME
            echo "Using GraalVM at $GRAALVM_HOME"
            break
        fi
    done
fi

if [ -z "${GRAALVM_HOME:-}" ]; then
    cat >&2 <<'MESSAGE'
No GraalVM found in ~/.jdks or ~/.sdkman/candidates/java.

Install one — a GraalVM JDK 25, from
https://github.com/graalvm/graalvm-ce-builds/releases — and unpack it into
~/.jdks, or set GRAALVM_HOME to where yours already is.
MESSAGE
    exit 1
fi

exec "$here/mvnw" -Pnative package "$@"
