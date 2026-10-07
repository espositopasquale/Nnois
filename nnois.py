#!/usr/bin/env python3
"""Start Nnois; download OMW data and build its database automatically on first run."""
import argparse
from contextlib import closing
import importlib.util
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent
MODULE = ROOT / "nnois"
RESOURCES = MODULE / "src/main/resources/omw"


def executable(name):
    found = shutil.which(name)
    if not found:
        raise RuntimeError(f"{name} is required but was not found on PATH.")
    return found


def run(command, cwd=ROOT):
    print(f"Running: {subprocess.list2cmdline([str(arg) for arg in command])}", flush=True)
    subprocess.run([str(arg) for arg in command], cwd=cwd, check=True)


def prepare_data(data_dir, fetch=False, repair=False):
    data_dir = Path(data_dir).resolve()
    cloned = False
    if not data_dir.exists() and fetch:
        run([executable("git"), "clone", "--depth", "1", "--no-checkout",
             "https://github.com/omwn/omw-data.git", data_dir])
        cloned = True
    if cloned or repair:
        git = executable("git")
        if not (data_dir / ".git").exists():
            raise RuntimeError(f"Not a Git checkout: {data_dir}")
        # The builder reads wn-data-*.tab, not the wn-wikt-*.tab files.
        # The latter include filenames that cannot be checked out on Windows.
        # Permit indexing the excluded upstream name without changing Git config.
        git_command = [git, "-c", "core.protectNTFS=false", "-C", data_dir]
        run([*git_command, "sparse-checkout", "set", "--no-cone",
             "/*", "!/wns/wikt/"])
        if repair:
            print("Restoring the failed OMW checkout from HEAD.", flush=True)
        run([*git_command, "read-tree", "-mu", "HEAD"])
    return data_dir


def build_database(data_dir, fetch=True, destination=RESOURCES):
    data_dir = prepare_data(data_dir, fetch)
    # OMW repositories have used both wns/ and omw/ layouts.
    files = sorted(data_dir.rglob("wn-data-*.tab")) if data_dir.is_dir() else []
    if not files:
        raise RuntimeError(f"No wn-data-*.tab files found in {data_dir}. "
                           "Select a valid OMW checkout with --data-dir PATH.")
    spec = importlib.util.spec_from_file_location("omw_builder", ROOT / "omw-database/create_db.py")
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    # Build separately so a failed build does not overwrite the installed database.
    with tempfile.TemporaryDirectory(prefix="omw-build-", dir=destination) as staging:
        staging = Path(staging)
        builder.DB_FILE = str(staging / "omw_multilingual.db")
        builder.CREDITS_FILE = str(staging / "WORDNET_SOURCES.md")
        builder.TAB_FILES_PATTERN = str(data_dir / "**/wn-data-*.tab")
        builder.create_database()
        # SQLite's connection context manager ends transactions but does not
        # close the file; Windows requires it closed before os.replace.
        with closing(sqlite3.connect(builder.DB_FILE)) as connection:
            if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise RuntimeError("Generated database failed SQLite integrity checking.")
            if connection.execute("SELECT count(*) FROM synset_lemmas").fetchone()[0] == 0:
                raise RuntimeError("Generated database contains no lemmas.")
        for name in ("omw_multilingual.db", "WORDNET_SOURCES.md"):
            os.replace(staging / name, destination / name)
    print(f"Database and attribution installed in {destination}")


def app_tools(maven=None):
    java = executable("java")
    maven = str(Path(maven).resolve()) if maven else executable("mvn")
    if not Path(maven).is_file() and not shutil.which(maven):
        raise RuntimeError(f"Maven executable not found: {maven}")
    return java, maven


def start_app(maven=None, app_args=()):
    java, maven = app_tools(maven)
    run([maven, "compile", "dependency:build-classpath",
         "-Dmdep.includeScope=runtime", "-Dmdep.outputFile=target/runtime-classpath.txt"], MODULE)
    classpath_file = MODULE / "target/runtime-classpath.txt"
    classpath = str(MODULE / "target/classes") + os.pathsep + classpath_file.read_text().strip()
    run([java, "-cp", classpath, "com.nnois.App", *app_args], MODULE)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run", "demo", "build-db", "setup", "repair-data"), nargs="?", default="run",
                        help="default: start app with automatic setup; demo: analyze seven European language samples; build-db: rebuild DB; setup: rebuild DB and start; repair-data: recover a failed clone")
    parser.add_argument("--data-dir", type=Path, default=ROOT / "omw-data")
    parser.add_argument("--fetch-data", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--maven", help="Path to mvn or mvn.cmd when Maven is not on PATH")
    parser.add_argument("--language", help="Demo language (en, it, fr, de, es, pt, nl); default: all seven")
    args = parser.parse_args(argv)
    if args.language and args.command != "demo":
        parser.error("--language is only available with demo")
    try:
        if args.command == "repair-data":
            prepare_data(args.data_dir, repair=True)
            return 0
        if args.command != "build-db":
            app_tools(args.maven)
        if args.command in ("build-db", "setup") or not all((RESOURCES / name).is_file()
                for name in ("omw_multilingual.db", "WORDNET_SOURCES.md")):
            build_database(args.data_dir)
        if args.command != "build-db":
            if args.command == "demo":
                start_app(args.maven, ["--demo", *([args.language] if args.language else [])])
            else:
                start_app(args.maven)
    except (RuntimeError, OSError, subprocess.CalledProcessError, sqlite3.Error) as error:
        print(f"Nnois helper: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
