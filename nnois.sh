#!/bin/sh
# Prepare OMW and start the Java CLI using the cross-platform setup helper.
set -eu

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd)

for python_command in python3 python; do
    if command -v "$python_command" >/dev/null 2>&1 &&
        "$python_command" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1; then
        exec "$python_command" "$script_dir/nnois.py" "$@"
    fi
done

printf '%s\n' 'Nnois requires Python 3.9 or later. Install it and add python3 to PATH.' >&2
exit 1
