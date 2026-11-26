"""Slice real keyboard recordings (Mechvibes-style packs, NK Cream, IBM Model M) into short
per-key samples for the app. Usage: build_sounds.py <packs_dir> <out_raw_dir>"""
import json, os, subprocess, sys, tempfile, wave
import numpy as np

SR = 44100
PACKS, OUT = sys.argv[1], sys.argv[2]
LETTER_CODES = [16, 18, 20, 22, 24, 30, 32, 34, 36, 44, 46, 48]
PICK = [0, 3, 5, 8, 10]          # 5 letter variants
TARGET = {"d": 0.78, "space": 0.92, "enter": 0.86, "back": 0.80}

def read_wav(p):
    w = wave.open(p); ch = w.getnchannels(); a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    if ch > 1: a = a.reshape(-1, ch).mean(axis=1)
    return a

def envelope(x, ms=2):
    w = max(1, int(SR * ms / 1000)); return np.sqrt(np.convolve(x * x, np.ones(w) / w, mode="same"))

def split_press_release(seg):
    env = envelope(seg)
    i1 = int(np.argmax(env[: int(0.06 * SR)])); p1 = env[i1]
    start = i1 + int(0.035 * SR)
    if start >= len(seg) - 200: return seg, None
    tail = env[start:]; j = int(np.argmax(tail)); p2 = tail[j]
    if j > 8:
        mn = tail[: j + 1].min()
        if p2 > 0.04 * p1 and p2 > 3.0 * (mn + 1e-6):
            k = int(np.argmin(tail[: j + 1])); sp = start + k
            return seg[:sp], seg[sp:]
    return seg, None

def trim_lead(x, frac, pre_ms=2):
    th = frac * np.max(np.abs(x)); idx = int(np.argmax(np.abs(x) > th))
    return x[max(0, idx - int(SR * pre_ms / 1000)):]

def fade_out(x, ms):
    n = min(len(x), int(SR * ms / 1000)); x = x.copy(); x[-n:] *= np.linspace(1, 0, n); return x

def save_ogg(name, x):
    tmp = tempfile.mktemp(suffix=".wav")
    with wave.open(tmp, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype(np.int16).tobytes())
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", tmp, "-ac", "1", "-c:a", "libvorbis", "-q:a", "4",
                    os.path.join(OUT, name + ".ogg")], check=True)
    os.remove(tmp)

def emit(pid, press_by_kind, ups):
    """press_by_kind: dict kind-> list of (press, up_or_None) full natural slices."""
    for kind, items in press_by_kind.items():
        for i, (pr, _) in enumerate(items):
            pr = trim_lead(pr, 0.15)
            pr = pr / (np.max(np.abs(pr)) + 1e-9) * TARGET[kind]
            pr = fade_out(pr, 6)
            save_ogg(f"{pid}_d{i}" if kind == "d" else f"{pid}_{kind}", pr)
    for i, up in enumerate(ups[:3]):
        pk = float(np.max(np.abs(up))) + 1e-9
        if pk < 0.22: up = up * min(8.0, 0.22 / pk)   # release clicks are naturally very quiet
        save_ogg(f"{pid}_u{i}", fade_out(up, 5))

def slice_pack_single(pid, folder):
    cfg = json.load(open(f"{folder}/config.json")); a = read_wav(f"{folder}/full.wav"); d = cfg["defines"]
    def get(code):
        s, dur = d[str(code)]; seg = a[int(s * SR / 1000): int((s + dur) * SR / 1000)]
        return split_press_release(seg)
    kinds = {"d": [], "space": [], "enter": [], "back": []}; ups = []
    scale_ref = None
    def add(kind, code, ref_peak_target):
        pr, up = get(code)
        peak = np.max(np.abs(pr)) + 1e-9
        kinds[kind].append((pr, up))
        if up is not None and len(up) > int(0.01 * SR):
            f = ref_peak_target / peak
            u = trim_lead(up * f, 0.3); ups.append(u)
    for ix in PICK: add("d", LETTER_CODES[ix], TARGET["d"])
    add("space", 57, TARGET["space"]); add("enter", 28, TARGET["enter"]); add("back", 14, TARGET["back"])
    emit(pid, kinds, [u for u in ups if np.max(np.abs(u)) > 0.02])
    return len([u for u in ups if np.max(np.abs(u)) > 0.02])

def slice_files(pid, press_files, up_files):
    kinds = {k: [(read_wav(f), None)] if k != "d" else [(read_wav(f), None) for f in press_files["d"]] for k, f in
             ((k, press_files[k]) for k in press_files if k != "d")}
    kinds["d"] = [(read_wav(f), None) for f in press_files["d"]]
    ups = []
    for f in up_files:
        u = read_wav(f); ups.append(trim_lead(u / (np.max(np.abs(u)) + 1e-9) * 0.35, 0.3))
    emit(pid, kinds, ups)
    return len(ups)

def slice_nk(pid, folder):
    kinds = {"d": [], "space": [], "enter": [], "back": []}; ups = []
    for ch in ["q", "t", "a", "d", "z"]:
        a = read_wav(f"{folder}/{ch}.wav"); pr, up = split_press_release(a); kinds["d"].append((pr, up))
        if up is not None and len(up) > 400: ups.append(trim_lead(up / (np.max(np.abs(pr)) + 1e-9) * TARGET["d"], 0.3))
    for kind, fn in (("space", "space"), ("enter", "enter"), ("back", "backspace")):
        a = read_wav(f"{folder}/{fn}.wav"); pr, up = split_press_release(a); kinds[kind].append((pr, up))
    emit(pid, kinds, [u for u in ups if np.max(np.abs(u)) > 0.02]); return len(ups)

SPECS = [("red", "cherrymx-red-pbt"), ("brown", "cherrymx-brown-pbt"), ("black", "cherrymx-black-pbt"),
         ("blue", "cherrymx-blue-pbt"), ("oreo", "eg-oreo"), ("crystal", "eg-crystal-purple"),
         ("topre", "topre-purple-hybrid-pbt")]
for pid, folder in SPECS:
    n = slice_pack_single(pid, os.path.join(PACKS, folder)); print(pid, "ups:", n)
print("cream ups:", slice_nk("cream", os.path.join(PACKS, "nk-cream")))
ib = os.path.join(PACKS, "ibm")
def w(h, s): return f"{ib}/{h}-{s}.wav"
letters = ["10", "13", "17", "1e", "22", "26", "2c", "31", "2e"]
print("ibm ups:", slice_files("ibm",
    {"d": [w(h, 0) for h in letters[:5]], "space": w("39", 0), "enter": w("1c", 0), "back": w("0e", 0)},
    [w(h, 1) for h in letters[5:8]]))
print(len(os.listdir(OUT)), "files")
