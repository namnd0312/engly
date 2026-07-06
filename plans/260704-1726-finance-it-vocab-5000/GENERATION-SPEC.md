# Vocab Chunk Generation Spec (shared by all generator agents)

You generate ONE chunk of English learning vocabulary as a CSV data file.
Audience: Vietnamese learners studying a professional domain (Finance/Banking or IT).

## Output format — CRITICAL

Write a plain CSV file with EXACTLY these 6 columns, in this order, NO header row:

```
term,meaning_vi,ipa,example_en,example_vi,part_of_speech
```

Column rules:
1. `term` — the English word/phrase, lowercase unless it is a proper noun/acronym (e.g. `SWIFT`, `Docker`, `AWS`, `GDP`). No trailing spaces.
2. `meaning_vi` — accurate, concise Vietnamese meaning. May list 1–3 senses separated by `, `. Domain-correct.
3. `ipa` — IPA transcription with slashes, e.g. `/ˈmɔːrɡɪdʒ/`. For a multi-word phrase, give IPA of the HEAD word only (e.g. for "letter of credit" → `/ˈletər/`), OR leave the field EMPTY if unsure. Never invent nonsense IPA.
4. `example_en` — one natural English sentence using the term correctly in-domain.
5. `example_vi` — faithful Vietnamese translation of example_en.
6. `part_of_speech` — one of: noun, verb, adjective, adverb, phrase, acronym, preposition, conjunction. Use `phrase` for multi-word collocations, `acronym` for abbreviations.

## CSV quoting rules (MUST follow exactly)
- Wrap a field in double quotes `"..."` if it contains a comma, a double quote, or a newline.
- Escape a literal double quote inside a quoted field by doubling it: `""`.
- Fields without special chars need no quotes.
- One record per line. UTF-8. No BOM. No blank lines. No header.

Example valid lines:
```
mortgage,"khoản vay thế chấp mua nhà",/ˈmɔːrɡɪdʒ/,"They took out a mortgage to buy their first home.","Họ đã vay thế chấp để mua căn nhà đầu tiên.",noun
letter of credit,"thư tín dụng (L/C)",/ˈletər/,"The exporter shipped the goods after receiving the letter of credit.","Nhà xuất khẩu giao hàng sau khi nhận được thư tín dụng.",phrase
```

## Quality bar
- Every term must be a REAL, commonly-used term in the assigned sub-topic. No made-up words, no filler, no duplicates within your chunk.
- Prefer terms genuinely useful for a learner working in the domain.
- Vietnamese meanings must be correct domain terminology (not literal mistranslations).
- Keep example sentences short (8–16 words) and natural.
- Do NOT number the lines. Do NOT add commentary. The file must contain ONLY CSV data lines.
- Stay strictly within your assigned sub-topic to minimize overlap with other chunks.

## Deliverable
Write the CSV to the exact path given in your task, then reply with only:
`Status: DONE | rows: <count> | file: <path>`
