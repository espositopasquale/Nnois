"""
Builds omw_multilingual.db, a SQLite extraction of the Open Multilingual Wordnet
data, reshaped into a lemma/definition schema for fast lookups by lang/pos/offset.

Standalone usage:
1. Clone the OMW data repository as a sibling of this project's root, i.e.:
       git clone https://github.com/omwn/omw-data.git
   Expected layout (this script reads TAB_FILES_PATTERN relative to itself):
       <project-root>/
           omw-data/
               wns/<proj>/wn-data-<lang>.tab   # required by this script
           omw-database/
               create_db.py                    # this file
2. Run `python create_db.py` from this directory.
3. `omw_multilingual.db` and `WORDNET_SOURCES.md` (per-wordnet attribution) are
   written to the current directory. Regenerate both whenever `omw-data/` is
   updated, and keep WORDNET_SOURCES.md alongside any copy of the .db file,
   since each bundled wordnet carries its own license/attribution requirements.
"""

import os
import glob
import sqlite3
import re

DB_FILE = "omw_multilingual.db"
CREDITS_FILE = "WORDNET_SOURCES.md"
TAB_FILES_PATTERN = "../omw-data/wns/**/wn-data-*.tab"

POS_MAP = {
    'n': 'noun',
    'v': 'verb',
    'a': 'adj',
    's': 'adj',
    'r': 'adv'
}

def create_database():
    if os.path.exists(DB_FILE):
        try:
            os.remove(DB_FILE)
        except PermissionError:
            print(f"Error: {DB_FILE} is locked by another process. Close it and try again.")
            return

    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()

    cursor.execute("PRAGMA synchronous = OFF;")
    cursor.execute("PRAGMA journal_mode = MEMORY;")

    # 1. Lemmas table
    cursor.execute("""
        CREATE TABLE synset_lemmas (
            synset_offset TEXT NOT NULL,
            pos TEXT NOT NULL, 
            synset_id TEXT NOT NULL,
            lang TEXT NOT NULL,
            lemma TEXT NOT NULL
        );
    """)

    # 2. Definitions table
    cursor.execute("""
        CREATE TABLE synset_def (
            synset_offset TEXT NOT NULL,
            pos TEXT NOT NULL,
            synset_id TEXT NOT NULL,
            lang TEXT NOT NULL,
            def TEXT NOT NULL
        );
    """)

    # 3. Sources table (attribution: each wordnet has its own name/url/license)
    cursor.execute("""
        CREATE TABLE sources (
            lang TEXT NOT NULL,
            name TEXT,
            url TEXT,
            license TEXT,
            file TEXT NOT NULL
        );
    """)

    tab_files = glob.glob(TAB_FILES_PATTERN, recursive=True)
    print(f"Found {len(tab_files)} .tab files to process.")

    total_lemmas = 0
    total_defs = 0
    sources_batch = []

    for file_path in tab_files:
        match = re.search(r'wn-data-([a-z]{2,3})\.tab$', os.path.basename(file_path))
        if not match:
            continue
        
        raw_lang = match.group(1)
        # Normalize 'en' to 'eng' if needed
        lang = "eng" if raw_lang in ("en", "eng") else raw_lang
        
        print(f"Processing language: '{lang}' -> {file_path}")

        lemmas_batch = []
        defs_batch = []
        header_seen = False

        with open(file_path, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                if line.startswith('#'):
                    if not header_seen:
                        header_seen = True
                        header_parts = line.lstrip('#').strip().split('\t')
                        name = header_parts[0].strip() if len(header_parts) > 0 else None
                        url = header_parts[2].strip() if len(header_parts) > 2 else None
                        license_ = header_parts[3].strip() if len(header_parts) > 3 else None
                        sources_batch.append((lang, name, url, license_, os.path.basename(file_path)))
                    continue

                parts = line.split('\t')
                if len(parts) < 3:
                    continue

                full_synset_id = parts[0].strip()
                key_type = parts[1].strip()  # E.g.: 'ita:lemma', 'eng:lemma', or just 'lemma'

                # Extract offset and pos
                if '-' in full_synset_id:
                    offset, raw_pos = full_synset_id.split('-', 1)
                else:
                    offset = full_synset_id[:-1]
                    raw_pos = full_synset_id[-1]

                pos_val = raw_pos

                # --- LEMMA HANDLING ---
                # Handle both ':lemma' suffix and the column being exactly 'lemma'
                if key_type.endswith(':lemma') or key_type == 'lemma':
                    lemma = parts[2].strip()
                    lemmas_batch.append((offset, pos_val, full_synset_id, lang, lemma))

                # --- DEFINITION HANDLING ---
                elif key_type.endswith(':def') or key_type == 'def':
                    definition_text = parts[-1].strip()
                    if len(definition_text) > 1:
                        defs_batch.append((offset, pos_val, full_synset_id, lang, definition_text))

        # Insert lemmas
        if lemmas_batch:
            cursor.executemany(
                "INSERT INTO synset_lemmas (synset_offset, pos, synset_id, lang, lemma) VALUES (?, ?, ?, ?, ?);", 
                lemmas_batch
            )
            total_lemmas += len(lemmas_batch)

        # Insert definitions
        if defs_batch:
            cursor.executemany(
                "INSERT INTO synset_def (synset_offset, pos, synset_id, lang, def) VALUES (?, ?, ?, ?, ?);", 
                defs_batch
            )
            total_defs += len(defs_batch)

    if sources_batch:
        cursor.executemany(
            "INSERT INTO sources (lang, name, url, license, file) VALUES (?, ?, ?, ?, ?);",
            sources_batch
        )

    print("\nBuilding B-Tree indexes for zero-latency lookups...")
    
    cursor.execute("CREATE INDEX idx_lemmas_offset_pos_lang ON synset_lemmas (synset_offset, pos, lang);")
    cursor.execute("CREATE INDEX idx_lemmas_synset_full ON synset_lemmas (synset_id, lang);")
    cursor.execute("CREATE INDEX idx_lemmas_lemma ON synset_lemmas (lemma, lang);")

    cursor.execute("CREATE INDEX idx_def_offset_pos_lang ON synset_def (synset_offset, pos, lang);")
    cursor.execute("CREATE INDEX idx_def_synset_full ON synset_def (synset_id, lang);")

    conn.commit()
    conn.close()

    write_credits_file(sources_batch)

    print("\n-------------------------------------------------------")
    print(f"Successfully completed '{DB_FILE}'!")
    print(f" - Total lemmas saved: {total_lemmas}")
    print(f" - Total definitions saved: {total_defs}")
    print(f" - Sources attributed: {CREDITS_FILE}")
    print("-------------------------------------------------------")


def write_credits_file(sources_batch):
    """Generate a human-readable attribution list for every individual wordnet used."""
    with open(CREDITS_FILE, "w", encoding="utf-8") as f:
        f.write("# Wordnet Sources\n\n")
        f.write(
            "Auto-generated by create_db.py. Lists every individual wordnet bundled "
            "into omw_multilingual.db, per Open Multilingual Wordnet's attribution terms. "
            "See omw-data/CITATION.cff for the overall OMW citation.\n\n"
        )
        f.write("| Lang | Name | License | URL |\n")
        f.write("| --- | --- | --- | --- |\n")
        for lang, name, url, license_, _file in sorted(sources_batch, key=lambda s: s[0]):
            f.write(f"| {lang} | {name or ''} | {license_ or ''} | {url or ''} |\n")

if __name__ == "__main__":
    create_database()