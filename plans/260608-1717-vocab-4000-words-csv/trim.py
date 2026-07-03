import csv
for nn in ["02","04","15"]:
    words=[w.strip() for w in open(f"chunks/chunk-{nn}",encoding="utf-8").read().splitlines() if w.strip()]
    with open(f"rows/rows-{nn}.csv",encoding="utf-8") as f:
        rows=[r for r in csv.reader(f) if r and len(r)==6]
    keep=rows[:len(words)]
    assert [r[0] for r in keep]==words, f"chunk {nn} term order broken"
    with open(f"rows/rows-{nn}.csv","w",encoding="utf-8",newline="") as f:
        csv.writer(f,quoting=csv.QUOTE_MINIMAL).writerows(keep)
    print(f"chunk {nn}: trimmed to {len(keep)} rows OK")
