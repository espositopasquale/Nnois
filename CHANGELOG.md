# Changelog

All notable changes to Nnois are documented in this file.

## [0.2.0] - 2026-11-09

### Added

- A shaded executable JAR with a Java launcher for `run`, `demo`, `build-db`, `setup`, and `repair-data`; OMW database generation and validation now run without Python, and Maven keeps the built JAR in the module's `target/` directory.
- Contextual word-sense disambiguation in Java with local ONNX models: `ChangeIsKey/graded-wsd` for English and `intfloat/multilingual-e5-small` for Italian, French, Spanish, Portuguese, and Dutch.
- A deterministic word-alternatives mode that ranks source-language lexicalizations from the selected OMW synset by orthographic transparency to a comparison language.
- An automatic text modifier that can make a text orthographically more or less similar to a selected comparison language while keeping replacements in the source language.
- Optional local Qwen decoder validation for checking the contextual cohesion of proposed replacements. The decoder accepts or rejects existing candidates and never generates replacement text.
- Tests for multilingual sense selection, OMW alternatives, deterministic substitutions, text rewriting, and the expanded CLI.

### Changed

- Expanded the CLI from two to four modes: whole-text comparison, one word in context, word alternatives, and automatic text modifier.
- Grouped multilingual lexical candidates by OMW synset so that sense selection and substitution use the same concept across languages.
- Made ambiguous or low-confidence contextual sense selection explicit instead of silently selecting a lexical fallback.
- Reduced third-party runtime logging in interactive CLI output.
- Aligned project metadata and documentation with the GPL-3.0-only source license.

### Fixed

- Prevented target-language words from being inserted into source-language text.
- Prevented duplicate OMW glosses from being treated as separate contextual candidates.
- Preserved deterministic candidate ordering and stable tie-breaking across repeated runs.

## [0.1.0] - 2026-10-27

- Initial public release of the Java CLI, OMW database builder, contextual analysis, and multilingual orthographic similarity scoring.
