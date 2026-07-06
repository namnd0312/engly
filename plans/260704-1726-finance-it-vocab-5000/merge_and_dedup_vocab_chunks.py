#!/usr/bin/env python3
"""Merge per-topic vocab chunk CSVs into two final import files.

- Reads chunks/{domain}-*.csv (no header, 6 columns).
- Dedups by lowercased, whitespace-trimmed `term` (keeps first occurrence).
- Validates each row has exactly 6 columns and a non-empty term/meaning.
- Writes finance + IT final CSVs with the canonical header the app importer expects.
"""
import csv
import glob
import os
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
CHUNK_DIR = os.path.join(BASE, "chunks")
HEADER = ["term", "meaning_vi", "ipa", "example_en", "example_vi", "part_of_speech"]

DOMAINS = {
    "finance": os.path.join(BASE, "finance-banking-vocab-import.csv"),
    "it": os.path.join(BASE, "it-technology-vocab-import.csv"),
}


def process(domain, out_path):
    files = sorted(glob.glob(os.path.join(CHUNK_DIR, f"{domain}-*.csv")))
    seen = {}
    order = []
    dropped_dupe = 0
    dropped_bad = 0
    per_file = []
    for f in files:
        rows_in = 0
        with open(f, newline="", encoding="utf-8") as fh:
            reader = csv.reader(fh)
            for row in reader:
                if not row or all(not c.strip() for c in row):
                    continue
                if len(row) != 6:
                    dropped_bad += 1
                    continue
                term = row[0].strip()
                meaning = row[1].strip()
                if not term or not meaning:
                    dropped_bad += 1
                    continue
                rows_in += 1
                key = term.lower()
                if key in seen:
                    dropped_dupe += 1
                    continue
                seen[key] = row
                order.append(key)
        per_file.append((os.path.basename(f), rows_in))

    with open(out_path, "w", newline="", encoding="utf-8") as out:
        writer = csv.writer(out, quoting=csv.QUOTE_MINIMAL)
        writer.writerow(HEADER)
        for key in order:
            writer.writerow(seen[key])

    print(f"\n=== {domain.upper()} ===")
    print(f"chunk files: {len(files)}")
    for name, n in per_file:
        print(f"  {name}: {n}")
    print(f"unique terms written: {len(order)}")
    print(f"cross-chunk duplicates dropped: {dropped_dupe}")
    print(f"malformed rows dropped: {dropped_bad}")
    print(f"output: {out_path}")
    return len(order)


def main():
    total = 0
    for domain, out_path in DOMAINS.items():
        total += process(domain, out_path)
    print(f"\nTOTAL unique terms across both files: {total}")


if __name__ == "__main__":
    main()
