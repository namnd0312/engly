import csv, glob, os
LIM = {0:120,1:255,2:120,3:500,4:500,5:40}
POS_OK = {"noun","verb","adjective","adverb","pronoun","preposition","conjunction","determiner","interjection","article","numeral","modal verb","auxiliary verb","phrasal verb"}
total=0; problems=[]
for n in range(16):
    nn=f"{n:02d}"
    words=[w.strip() for w in open(f"chunks/chunk-{nn}",encoding="utf-8").read().splitlines() if w.strip()]
    with open(f"rows/rows-{nn}.csv",encoding="utf-8") as f:
        rows=list(csv.reader(f))
    if len(rows)!=len(words):
        problems.append(f"chunk {nn}: ROW COUNT {len(rows)} != words {len(words)}")
    total+=len(rows)
    for i,r in enumerate(rows):
        ctx=f"chunk {nn} line {i+1} term={r[0] if r else '?'!r}"
        if len(r)!=6:
            problems.append(f"{ctx}: has {len(r)} cols (need 6)"); continue
        if i<len(words) and r[0]!=words[i]:
            problems.append(f"{ctx}: TERM MISMATCH vs source '{words[i]}'")
        if not r[0].strip(): problems.append(f"{ctx}: blank term")
        if not r[1].strip(): problems.append(f"{ctx}: blank meaning_vi")
        for c in range(6):
            if len(r[c])>LIM[c]: problems.append(f"{ctx}: col{c} len {len(r[c])}>{LIM[c]}")
print(f"TOTAL ROWS: {total}")
print(f"PROBLEMS: {len(problems)}")
for p in problems[:60]: print(" -",p)
