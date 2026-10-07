# Installing and running Nnois

## Requirements

- Java JDK 21 (including `javac`)
- Maven 3.8+
- Python 3.9+
- Git, when downloading OMW data

Make the tools available on `PATH`. Check with `java -version`, `javac -version`, `mvn -version`, `python --version`, and `git --version`. If Maven selects another Java version, set `JAVA_HOME` to your JDK 21 directory. No Python packages need to be installed.

## Start the app

From the project root on Windows:

```powershell
.\nnois.cmd
```

On any platform, you can also run:

```text
python nnois.py
```

Use `python3` on Linux/macOS if needed. The Windows launcher selects `py -3` when available, otherwise `python`.

This single command downloads missing OMW data, builds and validates the database when it or its attribution file is missing, compiles Java, and opens the interactive CLI. On later runs, it reuses the database. Maven compiles the application without running the test suite during normal startup. Initial downloads require internet access.

At `Input text`, enter a word or sentence, then choose automatic language detection or a language code such as `eng` or `ita`. For a sentence, choose a target word or aggregate nominal analysis. Enter `exit` or `quit` at the text prompt to finish.

## Linux and macOS

Install JDK 21, Maven 3.8+, Python 3.9+, and Git with your operating system's
package manager or the tools' official installers. Homebrew is another option
on macOS. Open a terminal in the repository root and check the tools:

```sh
java -version
javac -version
mvn -version
python3 --version
git --version
sh ./nnois.sh
```

The shell launcher selects Python 3.9+ and prepares missing OMW data before
starting the CLI. It also works when invoked by path from another directory.
To run it directly, use `chmod +x nnois.sh` once, then `./nnois.sh`.
All helper options are forwarded, for example `sh ./nnois.sh --help`,
`sh ./nnois.sh demo`, or `sh ./nnois.sh build-db`.
Initial downloads
require internet access; no additional Python packages are needed. If Maven
uses a different JDK, set `JAVA_HOME` to your JDK 21 directory.

For the web interface, also install Node.js 22+ and the pnpm version declared
in `nnois_web/nnois/package.json`. Start the API from the repository root:

```sh
sh ./nnois.sh build-db
mvn install -DskipTests
java -Dfile.encoding=UTF-8 -jar api/target/nnois-api-0.1.0.war
```

In another terminal, starting from the repository root:

```sh
cd nnois_web/nnois
pnpm install --frozen-lockfile
pnpm dev
```

Open http://localhost:3000. Keep the API and frontend terminals running; stop
each with Ctrl+C. The frontend forwards requests to the Java API on port 8080.
For production, use `pnpm build` followed by `pnpm start`.

## Rebuild the database

```powershell
.\nnois.cmd build-db
```

Or use `python nnois.py build-db`. This downloads missing data and installs both files into `nnois/src/main/resources/omw/`:

- `omw_multilingual.db`
- `WORDNET_SOURCES.md`

It does not require Java or Maven. Existing OMW checkouts are reused without updating them; rebuild after updating the source data yourself. A failed build leaves the installed database in place. Keep the attribution file with distributed copies of the database.

## Optional configuration

Use a different OMW checkout:

```powershell
.\nnois.cmd --data-dir "C:\Data\omw-data"
```

Use Maven outside `PATH`:

```powershell
.\nnois.cmd --maven "C:\Tools\apache-maven\bin\mvn.cmd"
```

Both options also work with `build-db` and with the Python entry point. You can invoke the launcher by its full path from another directory; explicit relative option paths are resolved against your current directory.

Older `run`, `setup`, and `--fetch-data` commands still work. `setup` explicitly rebuilds the database before starting; ordinary startup only builds a missing database or attribution file.

## Troubleshooting

| Problem | Action |
| --- | --- |
| A required tool cannot be found | Install it, add its executable directory to `PATH`, and reopen the terminal. Use `--maven` for an explicit Maven path. |
| Unsupported Java release | Check `mvn -version` and set `JAVA_HOME` to JDK 21. |
| No `wn-data-*.tab` files found | Use `--data-dir` for a valid OMW checkout. An existing empty directory is not automatically replaced. |
| Download fails | Check your network connection and Git/Maven proxy configuration, then rerun. |
| Database replacement denied | Close programs holding the database open and check write access to the resources directory. |
| Language detection is unreliable | Use a longer text or choose the source language manually. |

See [database builder documentation](omw-database/README.md) for the schema, [ATTRIBUTIONS.md](ATTRIBUTIONS.md#open-multilingual-wordnet-citation) for OMW citations, or run `python nnois.py --help` for helper options. Developers can run Java tests with `mvn test` inside `nnois/` and helper tests with `python -m unittest discover -s tests -v` at the project root.
