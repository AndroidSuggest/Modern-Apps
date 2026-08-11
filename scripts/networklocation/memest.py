#!/usr/bin/env python3
# Estimate JVM retained heap for GeocoderGenerator over the whole planet, from a sample.
# Models GeoAddress objects + their (non-deduped) Strings + the writer's transient arrays.
import json, sys

SAMPLE = int(sys.argv[2]) if len(sys.argv) > 2 else 5_000_000
TOTAL_LINES = 289_881_205  # wc -l addr.geojsonseq

def roundup8(x): return (x + 7) & ~7
# JDK17 compressed-oops sizes
def strbytes(s):
    if not s: return 0            # empty strings are shared -> ~0 amortized
    # String shell (~24B) + byte[] (header 16 + len, latin1 ~1B/char), 8-align
    return 24 + roundup8(16 + len(s))

path = sys.argv[1]
kept = 0
lines = 0
addr_obj = 0      # GeoAddress shells + arraylist backing ref
addr_str = 0      # retained (non-deduped) string bytes
uniq = {k:set() for k in ('house','street','city','state','country','postcode')}

FIELDS = ('house','street','city','state','country','postcode')
with open(path, 'r', encoding='utf-8', errors='replace') as f:
    for raw in f:
        if lines >= SAMPLE: break
        line = raw.strip().strip('\x1e')
        if not line or line[0] != '{':
            continue
        lines += 1
        try:
            o = json.loads(line)
        except Exception:
            continue
        p = o.get('properties') or {}
        street = p.get('addr:street','') or ''
        house  = p.get('addr:housenumber','') or ''
        if not street and not house:
            continue
        geom = o.get('geometry') or {}
        if 'coordinates' not in geom:
            continue
        city = p.get('addr:city','') or ''
        state = (p.get('addr:state','') or p.get('addr:province','')) or ''
        country = p.get('addr:country','') or ''
        postcode = p.get('addr:postcode','') or ''
        vals = dict(house=house,street=street,city=city,state=state,country=country,postcode=postcode)
        kept += 1
        addr_obj += 56 + 4       # GeoAddress shell (56) + one ArrayList ref (4)
        for k in FIELDS:
            v = vals[k]
            addr_str += strbytes(v)
            if v: uniq[k].add(v)

scale = TOTAL_LINES / lines if lines else 0
kept_total = kept * scale
retained_addr = (addr_obj + addr_str) * scale
# Writer transient peak: recs(56+4) + 8 int columns(32) + fwd boxed sort(20+4) per kept record
peak_extra = kept_total * (60 + 32 + 24)
peak = retained_addr + peak_extra

GB = 1024**3
print(f"sample_lines_parsed={lines}")
print(f"sample_kept={kept}  keep_ratio={kept/max(lines,1):.3f}")
print(f"scale_factor={scale:.2f}")
print(f"est_total_kept_records={kept_total/1e6:.1f} M")
print(f"est_retained_addresses={retained_addr/GB:.1f} GB")
print(f"est_writer_transient_extra={peak_extra/GB:.1f} GB")
print(f"est_PEAK_heap={peak/GB:.1f} GB")
print("--- sample unique counts (not scaled) ---")
for k in FIELDS:
    print(f"  uniq_{k}={len(uniq[k])}")
