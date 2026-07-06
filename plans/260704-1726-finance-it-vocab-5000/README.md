# Finance/Banking + IT Vocabulary (English → Vietnamese)

Two ready-to-import vocabulary CSVs for the Engly "study new words" bulk import feature.

## Deliverables

| File | Terms | Topic |
|------|-------|-------|
| `finance-banking-vocab-import.csv` | 2438 | Finance & Banking |
| `it-technology-vocab-import.csv` | 2485 | IT / Technology |

Total: **4923 unique terms.**

## Format

Matches the app's bulk-import schema exactly (same header as `plans/260608-1717-vocab-4000-words-csv/vocab-4000-words-upload.csv`):

```
term,meaning_vi,ipa,example_en,example_vi,part_of_speech
```

- UTF-8, RFC-4180 quoting (fields with commas are double-quoted).
- `part_of_speech`: noun / verb / adjective / phrase / acronym.
- IPA: head-word transcription for phrases; empty for a few phrases where no clean single IPA applies (29 finance, 19 IT).
- Deduplicated by lowercased `term` within each file (0 duplicates).

## How it was generated

- Each domain split into 25 sub-topics; one subagent per sub-topic wrote a ~100–130 row chunk into `chunks/`.
- `merge_and_dedup_vocab_chunks.py` merges all chunks per domain, drops cross-chunk duplicate terms and malformed rows, and writes the final CSV with the canonical header.
- To regenerate the final files after editing any chunk: `python3 merge_and_dedup_vocab_chunks.py`

## Import

Use the app's vocab bulk-import (CSV upload) once per file, into a separate deck for each domain.

## Notes / open items

- Target was ~2500 per domain; the finance "common core" saturates a bit earlier (higher cross-topic overlap), landing at 2438 vs IT 2485.
- Meanings/examples are model-generated — recommend a light human review pass on the IPA column before publishing to learners, since phrase-level IPA is the weakest field.
