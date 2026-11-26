"""Build app/src/main/assets/zhuyin.txt from McBopomofo data (MIT):
BPMFMappings.txt (phrase readings), BPMFBase.txt (character readings), phrase.occ (frequencies).
Output lines: reading<TAB>text<TAB>freq   (syllables separated by space, neutral tone mark moved to the end)"""
import sys, collections
src, out = sys.argv[1], sys.argv[2]
TONES = "ˊˇˋ˙"
def norm(s):
    if s.startswith("˙"): s = s[1:] + "˙"
    return s
def ok(s): return all(("ㄅ" <= c <= "ㄩ") or c in TONES for c in s) and len(s) > 0
occ = {}
for line in open(f"{src}/phrase.occ", encoding="utf-8"):
    p = line.split()
    if len(p) == 2 and p[1].isdigit(): occ[p[0]] = int(p[1])
base = collections.OrderedDict()
for line in open(f"{src}/BPMFBase.txt", encoding="utf-8"):
    p = line.split()
    if len(p) >= 2 and len(p[0]) == 1 and ok(p[1]):
        r = norm(p[1])
        if r not in base.setdefault(p[0], []): base[p[0]].append(r)
phr = []
for line in open(f"{src}/BPMFMappings.txt", encoding="utf-8"):
    p = line.split()
    if len(p) >= 3 and len(p[0]) == len(p) - 1 and len(p[0]) >= 2:
        syl = [norm(s) for s in p[1:]]
        if all(ok(s) for s in syl): phr.append((p[0], syl))
w = collections.defaultdict(lambda: collections.defaultdict(float))
for t, syl in phr:
    f = occ.get(t, 0) + 1
    for ch, r in zip(t, syl): w[ch][r] += f
rows = []
for ch, rs in base.items():
    tot = sum(w[ch][r] + 1 for r in rs); o = occ.get(ch, 0)
    for r in rs: rows.append((r, ch, int(o * (w[ch][r] + 1) / tot)))
for t, syl in phr:
    f = occ.get(t, 0)
    if f == 0 and len(t) > 4: continue
    rows.append((" ".join(syl), t, f))
rows.sort(key=lambda x: -x[2])
with open(out, "w", encoding="utf-8") as fo:
    for r, t, f in rows: fo.write(f"{r}\t{t}\t{f}\n")
print(len(rows), "rows")
