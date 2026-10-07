# OMW Database Builder

Builds `omw_multilingual.db`, a SQLite extraction of the [Open Multilingual Wordnet (OMW)](https://github.com/omw/omw-data) data, reshaped into a lemma/definition schema for fast lookups by language, POS, and synset offset.

## Quick Start

Recommended, from the project root:

```bash
python nnois.py build-db
```

The helper downloads missing OMW data and installs both the database and attribution into `nnois/src/main/resources/omw/`. Use `python nnois.py` (or `.\nnois.cmd` on Windows) to build a missing database and start the application in one step. An existing checkout can be selected with `--data-dir PATH`; both `wns/` and `omw/` layouts are supported. The steps below describe the standalone builder.

### 1. Clone OMW Data

Clone the OMW data repository inside this project's root. On Windows, use the helper above to avoid incompatible upstream filenames:

```bash
cd /path/to/Nnois
git clone https://github.com/omwn/omw-data.git
```

Expected layout:

```text
<project-root>/
    omw-data/
        wns/<proj>/wn-data-<lang>.tab
    omw-database/
        create_db.py
        README.md
```

### 2. Run the Builder

From the `omw-database/` directory:

```bash
cd omw-database
python create_db.py
```

This generates two files:

- **`omw_multilingual.db`** — The SQLite database for Nnois
- **`WORDNET_SOURCES.md`** — Per-wordnet attribution and license information

### 3. Install the Database

```bash
mkdir -p ../nnois/src/main/resources/omw
cp omw_multilingual.db ../nnois/src/main/resources/omw/
cp WORDNET_SOURCES.md ../nnois/src/main/resources/omw/
```

## Database Schema

### `synset_lemmas`

Lemmatized forms for each synset in each language.

| Column | Type | Description |
| --- | --- | --- |
| `synset_offset` | TEXT | WordNet synset offset (e.g., `00001740`) |
| `pos` | TEXT | Part of speech: `n`, `v`, `a`, `s`, `r` |
| `synset_id` | TEXT | Full synset ID with POS (e.g., `00001740-n`) |
| `lang` | TEXT | ISO 639-3 language code (e.g., `eng`, `ita`, `fra`) |
| `lemma` | TEXT | Lemmatized word form in the target language |

**Indexes:**
- `idx_lemmas_offset_pos_lang` — `(synset_offset, pos, lang)` for fast offset+POS lookups
- `idx_lemmas_synset_full` — `(synset_id, lang)` for synset-to-lemma resolution
- `idx_lemmas_lemma` — `(lemma, lang)` for lemma-to-synset reverse lookups

### `synset_def`

Definitions and glosses for each synset in each language.

| Column | Type | Description |
| --- | --- | --- |
| `synset_offset` | TEXT | WordNet synset offset |
| `pos` | TEXT | Part of speech |
| `synset_id` | TEXT | Full synset ID with POS |
| `lang` | TEXT | ISO 639-3 language code |
| `def` | TEXT | Definition or gloss text |

**Indexes:**
- `idx_def_offset_pos_lang` — `(synset_offset, pos, lang)` for definition lookups
- `idx_def_synset_full` — `(synset_id, lang)` for synset-to-definition joins

### `sources`

Attribution metadata for each wordnet included in the database.

| Column | Type | Description |
| --- | --- | --- |
| `lang` | TEXT | ISO 639-3 language code |
| `name` | TEXT | Wordnet name (e.g., `Italian WordNet`) |
| `url` | TEXT | Project or repository URL |
| `license` | TEXT | License name or identifier |
| `file` | TEXT | Source file basename (e.g., `wn-data-it.tab`) |

Extracted from the header comments in each `.tab` file and written to `WORDNET_SOURCES.md` for human-readable attribution.

## Input Format

The builder processes tab-separated `.tab` files from `omw-data/wns/*/`. Each file has a header and data rows:

```
# <Wordnet Name>	<Version>	<URL>	<License>
00001740	lemma	tree
00001740	def	a woody perennial plant...
00002098	lemma	plant
```

- **Column 1:** Synset ID (offset + POS, e.g., `00001740-n`)
- **Column 2:** Key type (`lemma`, `def`, `ita:lemma`, `eng:lemma`, etc.)
- **Column 3+:** Data (lemma text or definition text)

The builder normalizes language codes (e.g., `en` → `eng`) and extracts the header comment to populate the `sources` table.

## Build Process

1. **Create schema** — Three tables and pragmas for performance
2. **Process `.tab` files** — Read from `../omw-data/wns/**/wn-data-*.tab`
3. **Normalize language codes** — Map non-ISO-639-3 codes to standard codes
4. **Batch insert** — Bulk insert lemmas, definitions, and source metadata
5. **Build indexes** — Create B-Tree indexes for zero-latency lookups
6. **Generate attribution** — Write `WORDNET_SOURCES.md` from source headers

## Regeneration

Whenever the OMW data is updated, regenerate the database:

```bash
cd /path/to/omw-data
git pull origin master

cd ../omw-database
rm omw_multilingual.db WORDNET_SOURCES.md
python create_db.py

cp omw_multilingual.db ../nnois/src/main/resources/omw/
cp WORDNET_SOURCES.md ../nnois/src/main/resources/omw/
```

## Attribution

OMW recommends citing Francis Bond and Ryan Foster (2013), [Linking and Extending an Open Multilingual Wordnet](https://aclanthology.org/P13-1133/), ACL, pages 1352–1362, together with the individual wordnets used. The full citation and BibTeX are in [ATTRIBUTIONS.md](../ATTRIBUTIONS.md#open-multilingual-wordnet-citation).

The builder extracts per-wordnet attribution from each `.tab` file's header and writes it to `WORDNET_SOURCES.md`. This file **must accompany any copy** of `omw_multilingual.db`, as each individual wordnet carries its own license and attribution requirements.

**Keep `WORDNET_SOURCES.md` and `omw_multilingual.db` together.**

For overall OMW citation, see:

- **OMW Project:** https://github.com/omw/omw-data
- **OMW Citation:** https://github.com/omw/omw-data/blob/master/CITATION.cff

## Performance

- **Build time:** ~30 seconds to 2 minutes (depending on OMW size and disk speed)
- **Database size:** ~50–150 MB (uncompressed) for seven European languages
- **Query latency:** <10ms for indexed lookups after build completes

## Troubleshooting

| Issue | Solution |
| --- | --- |
| `glob` returns no files | Verify that `omw-data/wns/*/` exists and contains `.tab` files. Check the path relative to `create_db.py`. |
| `omw_multilingual.db` is locked | Close any open connections (IDE, Nnois application) and try again. |
| Encoding errors on non-ASCII lemmas | Ensure source `.tab` files use UTF-8 encoding. |
| Missing definitions or lemmas | Some wordnets may not include all fields. The builder skips empty rows silently. |
| `WORDNET_SOURCES.md` not generated | Check that the `.tab` files contain header comments (lines starting with `#`). |

## Development

### Modifying the Schema

To add columns or tables:

1. Edit the `cursor.execute()` calls in `create_database()` to define new tables
2. Update the parsing logic to populate new columns
3. Add indexes as needed for performance
4. Regenerate the database and test with Nnois

### Language Filtering

To build a database for only specific languages, modify the `TAB_FILES_PATTERN` or add a language filter:

```python
LANGUAGES_TO_INCLUDE = {"eng", "ita", "fra", "deu", "spa", "por", "nld"}

for file_path in tab_files:
    # ... extract lang ...
    if lang not in LANGUAGES_TO_INCLUDE:
        continue
```

## References

- **OMW Project:** https://github.com/omw/omw-data
- **WordNet Format:** https://wordnet.princeton.edu/documentation
- **SQLite Documentation:** https://www.sqlite.org/docs.html
- **ISO 639 Language Codes:** https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes

