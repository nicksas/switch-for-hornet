import re, itertools
pkts = set()
for line in open('iamp_tx_capture.txt', encoding='utf-8'):
    m = re.search(r'TX FFF3 \[([0-9, ]+)\]', line)
    if m:
        pkts.add(tuple(int(x) for x in m.group(1).split(',')))
pkts = sorted(pkts)

def refl(v, w):
    r = 0
    for i in range(w):
        if v >> i & 1: r |= 1 << (w - 1 - i)
    return r

def crc16(data, poly, init, refin, refout, xorout):
    c = init
    for b in data:
        if refin: b = refl(b, 8)
        c ^= b << 8
        for _ in range(8):
            c = ((c << 1) ^ poly) & 0xFFFF if c & 0x8000 else (c << 1) & 0xFFFF
    if refout: c = refl(c, 16)
    return c ^ xorout

hits = []
for start in range(0, 5):
    for poly in range(1, 0x10000):
        if poly not in (0x1021, 0x8005, 0x3D65, 0x0589, 0x8BB7, 0xA097, 0xC867, 0x6F63, 0x5935, 0x755B, 0x1DCF, 0x2F15): continue
        for init in (0x0000, 0xFFFF, 0x1D0F, 0x800D, 0xB2AA, 0x89EC, 0xC6C6):
            for refin, refout in ((False, False), (True, True)):
                for xorout in (0x0000, 0xFFFF):
                    for order in ('be', 'le'):
                        ok = True
                        for p in pkts:
                            c = crc16(p[start:-2], poly, init, refin, refout, xorout)
                            exp = (p[-2] << 8 | p[-1]) if order == 'be' else (p[-1] << 8 | p[-2])
                            if c != exp: ok = False; break
                        if ok: hits.append((start, hex(poly), hex(init), refin, refout, hex(xorout), order))
for p in pkts: print(' '.join('%02X' % b for b in p))
print(hits)
