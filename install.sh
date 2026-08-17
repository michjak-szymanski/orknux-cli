#!/bin/sh
# ---------------------------------------------------------------------------
# Installs orkx for the current user. See usage() for the arguments.
#
# ~/.local/bin because that is where a user-owned binary goes, and because most
# shells already have it on PATH. If yours does not, this says so and prints the
# line to add - it does not edit your shell profile behind your back.
# ---------------------------------------------------------------------------
set -eu

here=$(cd "$(dirname "$0")" && pwd)
prefix="$HOME/.local/bin"
uninstall=false

usage() {
    cat <<'MESSAGE'
Installs orkx for the current user.

    ./install.sh                install into ~/.local/bin, building if needed
    ./install.sh --prefix DIR   install somewhere else
    ./install.sh --uninstall    remove it again
MESSAGE
}

while [ $# -gt 0 ]; do
    case $1 in
        --uninstall) uninstall=true ;;
        --prefix) shift; prefix=${1:?--prefix needs a directory} ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument '$1'. Try --help." >&2; exit 2 ;;
    esac
    shift
done

if [ "$uninstall" = true ]; then
    removed=false
    for name in orkx.exe orkx; do
        if [ -e "$prefix/$name" ]; then
            rm -f "$prefix/$name"
            echo "Removed $prefix/$name"
            removed=true
        fi
    done
    [ "$removed" = true ] || echo "Nothing installed in $prefix"
    exit 0
fi

# The plugin names it orkx.exe on Windows and orkx elsewhere. The .exe goes first
# because Windows resolves a bare name to it anyway, and taking that answer would
# install a PE file under a name no shell there will run.
find_binary() {
    for candidate in "$here/target/orkx.exe" "$here/target/orkx"; do
        if [ -f "$candidate" ]; then echo "$candidate"; return 0; fi
    done
    return 1
}

binary=$(find_binary || true)

if [ -z "$binary" ]; then
    echo "No binary in $here/target yet - building it."
    "$here/native.sh"
    binary=$(find_binary || true)
    [ -n "$binary" ] || { echo "The native build produced no binary; nothing installed." >&2; exit 1; }
fi

installed="$prefix/$(basename "$binary")"

mkdir -p "$prefix"
cp "$binary" "$installed"
chmod +x "$installed"
echo "Installed $installed"
"$installed" --version

case ":${PATH}:" in
    *":$prefix:"*)
        echo "Run 'orkx login'."
        ;;
    *)
        echo
        echo "$prefix is not on your PATH. Add it, then reopen your shell:"
        echo
        echo "    echo 'export PATH=\"\$PATH:$prefix\"' >> ~/.profile"
        ;;
esac
