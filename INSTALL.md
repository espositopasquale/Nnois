# Installing and running Nnois 0.2.0

## Requirements

- Java JDK 21 (including `javac`)
- Maven 3.8+
- Python 3.9+ for the setup helper and OMW database builder
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

At `Text >`, enter a word or sentence, then accept automatic language detection or choose a language such as `en` or `it`. Select whole-text comparison, one word in context, word alternatives, or automatic text modifier. Enter `exit` or `quit` at the text prompt to finish.

## Linux

On Debian or Ubuntu, install the required tools with:

```bash
sudo apt update
sudo apt install openjdk-21-jdk maven python3 git
```

On Fedora:

```bash
sudo dnf install java-21-openjdk-devel maven python3 git
```

Confirm that Maven is using Java 21:

```bash
java -version
javac -version
mvn -version
python3 --version
git --version
```

Then open the extracted release or cloned repository and start Nnois:

```bash
cd /path/to/nnois
chmod +x nnois.sh
./nnois.sh
```

If Maven reports another Java version, locate the JDK with `readlink -f "$(command -v javac)"` and set `JAVA_HOME` to its JDK directory before starting Nnois. For example:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./nnois.sh
```

The exact JDK directory can differ by distribution and architecture.

## macOS

With [Homebrew](https://brew.sh/) installed, install the prerequisites with:

```bash
brew install openjdk@21 maven python git
```

Configure the current terminal to use the Homebrew JDK:

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

To keep this configuration, add those two `export` lines to `~/.zshrc`, then open a new terminal or run `source ~/.zshrc`. Verify the tools and start Nnois:

```bash
java -version
javac -version
mvn -version
python3 --version
git --version
cd /path/to/nnois
chmod +x nnois.sh
./nnois.sh
```

Apple Silicon and Intel Macs can use the same commands because `brew --prefix` resolves the correct Homebrew location.

## Shell launcher behavior

The Linux and macOS launcher selects Python 3.9 or later and prepares missing OMW data before starting the CLI. It also works when invoked by its full path from another directory. All helper options are forwarded:

```bash
./nnois.sh --help
./nnois.sh demo
./nnois.sh build-db
```

Initial database downloads require internet access. No additional Python packages are needed for the database builder or normal Java CLI. If executable permissions were lost while extracting an archive, run `chmod +x nnois.sh` again.

To use contextual models stored outside the project on Linux or macOS, configure their directories before launching:

```bash
export NNOIS_WSD_MODEL_DIR="/path/to/models/graded-wsd"
export NNOIS_MULTILINGUAL_WSD_MODEL_DIR="/path/to/models/multilingual-e5-small"
./nnois.sh
```

## Contextual models

Release 0.2.0 performs word-sense disambiguation locally in the Java process. Download or export the models separately; their binaries are excluded from Git and release archives. From the repository root, use this layout:

```text
models/
|-- graded-wsd/
|   |-- model.onnx
|   |-- tokenizer.json
|   `-- config.json
`-- multilingual-e5-small/
    |-- model.onnx
    |-- tokenizer.json
    `-- config.json
```

The English model is [ChangeIsKey/graded-wsd](https://huggingface.co/ChangeIsKey/graded-wsd). Italian, French, German, Spanish, Portuguese, and Dutch use [intfloat/multilingual-e5-small](https://huggingface.co/intfloat/multilingual-e5-small). If the directories are elsewhere, set them for the current PowerShell session:

```powershell
$env:NNOIS_WSD_MODEL_DIR = "C:\Models\graded-wsd"
$env:NNOIS_MULTILINGUAL_WSD_MODEL_DIR = "C:\Models\multilingual-e5-small"
.\nnois.cmd
```

Both directories must contain `model.onnx`, `tokenizer.json`, and `config.json`. Nnois reports a missing or inconclusive model result instead of substituting from an uncertain sense.

## Optional substitution validator

Candidate generation is deterministic Java logic and requires no generative model. To let modes 3 and 4 validate candidates in their full context, export [Qwen2.5-1.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct) for ONNX Runtime GenAI into `models/substitution-decoder-onnx`, then install `onnxruntime-genai` in an isolated `.venv-decoder` environment. The exported model directory must contain `genai_config.json`.

Override the defaults when needed:

```powershell
$env:NNOIS_SUBSTITUTION_MODEL_DIR = "C:\Models\substitution-decoder-onnx"
$env:NNOIS_SUBSTITUTION_PYTHON = "C:\Environments\nnois-decoder\Scripts\python.exe"
.\nnois.cmd
```

The validator receives only replacements already selected from the source-language lexicalizations of the chosen synset. It can mark them `accepted`, `rejected`, or `unverified`; it cannot generate or translate text. Automatic mode does not apply unverified candidates.

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
| `graded-wsd missing` | Put the three English model assets in `models/graded-wsd` or set `NNOIS_WSD_MODEL_DIR`. |
| `multilingual E5 missing` | Put the three E5 assets in `models/multilingual-e5-small` or set `NNOIS_MULTILINGUAL_WSD_MODEL_DIR`. |
| Modifier reports that the context checker is unavailable | Check `genai_config.json`, the isolated Python executable, and the `onnxruntime-genai` installation. |

See [database builder documentation](omw-database/README.md) for the schema, [ATTRIBUTIONS.md](ATTRIBUTIONS.md#open-multilingual-wordnet-citation) for OMW citations, or run `python nnois.py --help` for helper options. Developers can run Java tests with `mvn test` inside `nnois/` and helper tests with `python -m unittest discover -s tests -v` at the project root.
