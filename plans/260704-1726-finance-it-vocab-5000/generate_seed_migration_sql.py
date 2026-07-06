#!/usr/bin/env python3
"""Generate an idempotent Flyway seed migration from the two final vocab CSVs.

Idempotency:
- Decks inserted with ON CONFLICT (slug) DO NOTHING.
- Words inserted via SELECT ... FROM (VALUES ...) guarded so a deck's words are
  only inserted when that deck currently has zero words. Safe to apply manually
  now AND to let Flyway run later on the same DB without duplicating rows.

All seeded words are verified=false (AI draft, pending human review).
"""
import csv
import os

BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(
    BASE, "..", "..", "engly-be", "src", "main", "resources", "db", "migration",
    "V8__seed_finance_it_vocab_decks_ai_draft_unverified.sql",
)

DECKS = [
    {
        "csv": "finance-banking-vocab-import.csv",
        "name": "Finance & Banking English",
        "slug": "finance",
        "description": "Vocabulary for finance and banking: accounting, investment, "
                       "trading, insurance, taxation, fintech, risk, and more.",
        "topic": "Finance",
    },
    {
        "csv": "it-technology-vocab-import.csv",
        "name": "IT & Technology English",
        "slug": "it",
        "description": "Vocabulary for IT and technology: programming, networking, "
                       "security, cloud, databases, AI/ML, DevOps, and more.",
        "topic": "Technology",
    },
]


def q(s):
    """SQL string literal with single quotes escaped, or NULL for empty."""
    if s is None or s.strip() == "":
        return "NULL"
    return "'" + s.replace("'", "''") + "'"


def qnn(s):
    """Non-null SQL string literal (empty string stays a quoted empty string)."""
    return "'" + (s or "").replace("'", "''") + "'"


def main():
    lines = []
    lines.append("-- V8: Seed Finance/Banking and IT/Technology vocabulary decks.")
    lines.append("-- AI-drafted, verified=false (pending human review, matching the V2 irregular-verb precedent).")
    lines.append("-- Idempotent: decks use ON CONFLICT DO NOTHING; each deck's words insert only when the deck has none yet.")
    lines.append("")

    # Decks
    lines.append("INSERT INTO vocab_deck (name, slug, description, topic) VALUES")
    deck_vals = []
    for d in DECKS:
        deck_vals.append(
            f"    ({qnn(d['name'])}, {qnn(d['slug'])}, {qnn(d['description'])}, {qnn(d['topic'])})"
        )
    lines.append(",\n".join(deck_vals))
    lines.append("ON CONFLICT (slug) DO NOTHING;")
    lines.append("")

    # Words per deck
    for d in DECKS:
        rows = list(csv.reader(open(os.path.join(BASE, d["csv"]), encoding="utf-8")))[1:]
        lines.append(f"-- {d['name']} — {len(rows)} words")
        lines.append(
            "INSERT INTO vocab_word (deck_id, term, meaning_vi, ipa, example_en, example_vi, part_of_speech, verified)"
        )
        lines.append(
            "SELECT d.id, x.term, x.meaning_vi, x.ipa, x.example_en, x.example_vi, x.part_of_speech, false"
        )
        lines.append("FROM (VALUES")
        val_lines = []
        for r in rows:
            term, meaning, ipa, ex_en, ex_vi, pos = r
            val_lines.append(
                f"    ({qnn(term)}, {qnn(meaning)}, {q(ipa)}, {q(ex_en)}, {q(ex_vi)}, {q(pos)})"
            )
        lines.append(",\n".join(val_lines))
        lines.append(
            ") AS x(term, meaning_vi, ipa, example_en, example_vi, part_of_speech)"
        )
        lines.append(f"CROSS JOIN vocab_deck d")
        lines.append(f"WHERE d.slug = {qnn(d['slug'])}")
        lines.append("  AND NOT EXISTS (SELECT 1 FROM vocab_word w WHERE w.deck_id = d.id);")
        lines.append("")

    out_path = os.path.normpath(OUT)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("wrote:", out_path)
    print("bytes:", os.path.getsize(out_path))


if __name__ == "__main__":
    main()
