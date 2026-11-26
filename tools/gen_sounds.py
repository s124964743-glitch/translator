import numpy as np, wave, os, sys
SR = 44100
rng = np.random.default_rng(11)
OUT = sys.argv[1]

def tt(d): return np.arange(int(SR * d)) / SR

def band(x, lo, hi):
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1 / SR)
    m = np.ones_like(f)
    if lo: m *= 1 / (1 + (lo / np.maximum(f, 1)) ** 4)
    if hi: m *= 1 / (1 + (f / hi) ** 4)
    return np.fft.irfft(X * m, len(x))

def env(d, tau, atk=0.0006):
    x = tt(d); return np.exp(-x / tau) * np.minimum(1, x / atk)

def tone(f0, d, tau, f1=None):
    x = tt(d)
    if f1:
        f = f1 + (f0 - f1) * np.exp(-x / (tau * 0.4))
        ph = 2 * np.pi * np.cumsum(f) / SR
    else:
        ph = 2 * np.pi * f0 * x
    return np.sin(ph) * env(d, tau)

def noise(d, tau, lo, hi):
    n = band(rng.standard_normal(int(SR * d)), lo, hi)
    n /= np.max(np.abs(n)) + 1e-9
    return n * env(d, tau, 0.0003)

def mix(parts, d):
    out = np.zeros(int(SR * d))
    for sig, delay, g in parts:
        i = int(SR * delay); n = min(len(sig), len(out) - i)
        if n > 0: out[i:i + n] += sig[:n] * g
    return out

def save(name, x, peak):
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    n = int(SR * 0.006); x[-n:] *= np.linspace(1, 0, n)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype(np.int16).tobytes())

# profile: body(f0,f1,tau,g) ping(f,tau,g) noise(lo,hi,tau,g) extra(list of (kind,f,tau,g,delay))
P = {
 "red":   dict(body=(190,130,.040,1.0), ping=(1100,.010,.35), nz=(800,5000,.006,.5), extra=[], d=.20),
 "brown": dict(body=(210,140,.036,1.0), ping=(1300,.010,.30), nz=(900,5500,.006,.5),
               extra=[("t",2400,.004,.55,.008),("t",3600,.003,.25,.008)], d=.20),
 "black": dict(body=(150,100,.058,1.1), ping=(900,.012,.28), nz=(500,3500,.009,.5), extra=[], d=.24),
 "blue":  dict(body=(260,170,.030,.8), ping=(1500,.010,.25), nz=(1500,8000,.008,.7),
               extra=[("t",3300,.014,.9,0.0),("t",4600,.006,.5,0.0)], d=.20, bdelay=.016),
 "creamy":dict(body=(125,85,.080,1.15), ping=(700,.015,.15), nz=(300,2500,.010,.35),
               extra=[("t",420,.030,.35,0.0)], d=.28),
 "typewriter": dict(body=(320,220,.030,.7), ping=(2100,.060,.35), nz=(1000,9000,.004,.9),
               extra=[("t",3150,.055,.25,0.0),("t",4720,.045,.2,0.0)], d=.30),
}

def down(p, kind):
    bf0, bf1, btau, bg = p["body"]; pf, ptau, pg = p["ping"]; nlo, nhi, ntau, ng = p["nz"]
    scale = {"down":1.0, "space":0.62, "enter":0.8, "back":1.12}[kind]
    tauk = {"down":1.0, "space":1.7, "enter":1.25, "back":0.9}[kind]
    d = p["d"] * {"down":1.0, "space":1.35, "enter":1.15, "back":1.0}[kind]
    bd = p.get("bdelay", 0.0)
    parts = [(tone(bf0*scale, d, btau*tauk, bf1*scale), bd, bg),
             (tone(pf*(0.8 if kind=="space" else 1.0), d, ptau, None), bd, pg),
             (noise(d, ntau*tauk, nlo, nhi), bd, ng)]
    for k, f, tau, g, dl in p["extra"]:
        parts.append((tone(f, d, tau), dl, g))
    if kind == "space":   # stabilizer rattle
        for dl in (0.014, 0.027, 0.041):
            parts.append((noise(.012, .004, 1200, 6000), dl, 0.18))
    if kind == "enter":
        parts.append((tone(pf*1.5, d, ptau*1.2), bd+0.004, pg*0.7))
    return mix(parts, d)

def up(p):
    pf = p["ping"][0]
    d = 0.09
    parts = [(noise(d, .005, 1500, 7000), 0, .6), (tone(pf*0.9, d, .008), 0, .35),
             (tone(p["body"][0]*1.3, d, .015), 0, .3)]
    if p is P["blue"]: parts.append((tone(3000, d, .008), 0, .7))
    if p is P["typewriter"]: parts.append((tone(2600, d, .02), 0, .25))
    return mix(parts, d)

for name, p in P.items():
    for kind, pk in (("down",.85),("space",.9),("enter",.88),("back",.85)):
        save(f"{name}_{kind}", down(p, kind), pk)
    save(f"{name}_up", up(p), .42)
print("ok", len(os.listdir(OUT)))
