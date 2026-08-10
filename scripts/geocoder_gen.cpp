// geocoder_gen.cpp — offline planet geocoder database builder.
//
// Reads an OSM-address GeoJSONSeq stream (one GeoJSON Feature per line, as produced by
// `osmium export`) and writes a packed `geocoder.geodb` that the Modern Apps network-location
// app mmaps at runtime (see networklocation/.../geocoder/GeoDb.kt + GeoDbReader.kt).
//
// This replaces the old in-app Kotlin generator: generating the whole planet needs tens of GB
// of working memory and a multi-hour run, which does not belong on the JVM test classpath.
//
// On-disk format (must byte-match GeoDbReader.kt — all ints/longs are BIG-ENDIAN):
//   int  MAGIC   = 0x4D414745 ("MAGE")
//   int  VERSION = 2                  (v2 = per-block codec is Zstandard)
//   int  n                            (record count)
//   6x dict section   : [int size][ [int rawLen][int compLen][zstd(raw)] ]
//                       raw = [int count]( [int len][utf8 bytes] )*count
//                       order: house, street, city, state, country, postcode
//                       entries sorted in Kotlin String order (UTF-16 code units) so the
//                       reader's binary search over the dict works.
//   8x column section : [int size][ [int n][int blocks]([int rawLen][int compLen])*blocks
//                                   [zstd block]*blocks ]
//                       each block: up to BLOCK values, delta(optional)+zigzag+varint(LEB128).
//                       order: lat(delta), lon(delta), house, street, city, state, country,
//                       postcode. lat/lon are micro-degrees; the rest are dict indices.
//                       Records are in grid-primary order (sorted by 0.05-deg cell id).
//   grid section      : [int size][ [int cellCount]( [long cellId][int startRec] )*count ]
//                       (uncompressed; cellIds ascending)
//   fwd section        : one delta column of record indices sorted by
//                       (country,state,city,street,house) dict index — for forward lookup.
//
// Build (see geocoder_gen.sh):
//   g++ -O2 -fopenmp -std=c++17 geocoder_gen.cpp simdjson.cpp -lzstd -o geocoder_gen
// Run:
//   ./geocoder_gen generate addr.geojsonseq geocoder.geodb   # build (then self-verifies)
//   ./geocoder_gen verify   geocoder.geodb                   # re-check an existing DB

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <zstd.h>

#include "simdjson.h"

// ------------------------------------------------------------------ constants (match GeoDb.kt)
static const uint32_t MAGIC = 0x4D414745u;
static const uint32_t VERSION = 2u;
static const int BLOCK = 4096;
static const int CELL_MICRO = 50000;
static const int64_t COLS = 360000000LL / CELL_MICRO; // 7200
static const int MIN_LAT_MICRO = -90000000;
static const int MIN_LON_MICRO = -180000000;
static const int ZSTD_LEVEL = 19;
static const int FIELDS = 6; // house, street, city, state, country, postcode
enum { F_HOUSE = 0, F_STREET, F_CITY, F_STATE, F_COUNTRY, F_POSTCODE };

[[noreturn]] static void die(const std::string& m) {
    fprintf(stderr, "geocoder_gen: %s\n", m.c_str());
    exit(1);
}

// ------------------------------------------------------------------ little helpers
static inline void be32(std::string& o, uint32_t v) {
    o.push_back((char)(v >> 24)); o.push_back((char)(v >> 16));
    o.push_back((char)(v >> 8));  o.push_back((char)v);
}
static inline void be64(std::string& o, uint64_t v) {
    for (int s = 56; s >= 0; s -= 8) o.push_back((char)(v >> s));
}
static inline uint32_t rd32(const uint8_t* p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}
static inline uint64_t rd64(const uint8_t* p) {
    uint64_t v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | p[i]; return v;
}
static inline uint32_t zigzag(int32_t v) { return ((uint32_t)v << 1) ^ (uint32_t)(v >> 31); }
static inline int32_t unzigzag(uint32_t v) { return (int32_t)(v >> 1) ^ -(int32_t)(v & 1); }
static inline void writeVarInt(std::string& o, uint32_t v) {
    while (true) {
        uint8_t b = v & 0x7F; v >>= 7;
        if (v == 0) { o.push_back((char)b); return; }
        o.push_back((char)(b | 0x80));
    }
}
static inline int toMicro(double deg) {
    // Kotlin roundToInt(): ties round toward +infinity == floor(x + 0.5).
    return (int)std::floor(deg * 1000000.0 + 0.5);
}
static inline int64_t cellIdOf(int latM, int lonM) {
    int64_t row = (int64_t)(latM - MIN_LAT_MICRO) / CELL_MICRO;
    int64_t col = (int64_t)(lonM - MIN_LON_MICRO) / CELL_MICRO;
    return row * COLS + col;
}

static std::string zstdCompress(const std::string& raw) {
    size_t bound = ZSTD_compressBound(raw.size());
    std::string c; c.resize(bound);
    size_t r = ZSTD_compress(&c[0], bound, raw.data(), raw.size(), ZSTD_LEVEL);
    if (ZSTD_isError(r)) die(std::string("zstd compress: ") + ZSTD_getErrorName(r));
    c.resize(r);
    return c;
}
static std::string zstdDecompress(const uint8_t* comp, size_t compLen, size_t rawLen) {
    std::string raw; raw.resize(rawLen);
    size_t r = ZSTD_decompress(&raw[0], rawLen, comp, compLen);
    if (ZSTD_isError(r) || r != rawLen) die(std::string("zstd decompress: ") + ZSTD_getErrorName(r));
    return raw;
}

// Decode UTF-8 -> UTF-16 units so we can sort exactly like Kotlin's String.compareTo.
static std::u16string toUtf16(const std::string& s) {
    std::u16string o; o.reserve(s.size());
    size_t i = 0, n = s.size();
    while (i < n) {
        uint32_t cp; unsigned char c = (unsigned char)s[i];
        if (c < 0x80) { cp = c; i += 1; }
        else if ((c >> 5) == 0x6 && i + 1 < n) { cp = (c & 0x1F); cp = (cp << 6) | (s[i + 1] & 0x3F); i += 2; }
        else if ((c >> 4) == 0xE && i + 2 < n) { cp = (c & 0x0F); cp = (cp << 6) | (s[i + 1] & 0x3F); cp = (cp << 6) | (s[i + 2] & 0x3F); i += 3; }
        else if ((c >> 3) == 0x1E && i + 3 < n) { cp = (c & 0x07); cp = (cp << 6) | (s[i + 1] & 0x3F); cp = (cp << 6) | (s[i + 2] & 0x3F); cp = (cp << 6) | (s[i + 3] & 0x3F); i += 4; }
        else { cp = 0xFFFD; i += 1; }
        if (cp <= 0xFFFF) o.push_back((char16_t)cp);
        else { cp -= 0x10000; o.push_back((char16_t)(0xD800 + (cp >> 10))); o.push_back((char16_t)(0xDC00 + (cp & 0x3FF))); }
    }
    return o;
}

// ------------------------------------------------------------------ record model
struct Rec {
    int32_t latM, lonM;
    uint32_t f[FIELDS]; // dict ids (provisional during parse, final after remap)
};

// Per-field string interner: value -> provisional id, plus the unique values in insertion order.
struct Interner {
    std::unordered_map<std::string, uint32_t> id;
    std::vector<std::string> vals;
    uint32_t intern(const std::string& s) {
        auto it = id.find(s);
        if (it != id.end()) return it->second;
        uint32_t v = (uint32_t)vals.size();
        id.emplace(s, v);
        vals.push_back(s);
        return v;
    }
};

// ------------------------------------------------------------------ column encoder
// Encode `values` as [int n][int blocks]([int rawLen][int compLen])*blocks [zstd block]*blocks.
static std::string encodeColumn(const std::vector<int32_t>& values, bool delta) {
    int n = (int)values.size();
    int blocks = (n + BLOCK - 1) / BLOCK;
    std::vector<std::string> comp(blocks);
    std::vector<int> rawLens(blocks), compLens(blocks);
#pragma omp parallel for schedule(dynamic, 16)
    for (int b = 0; b < blocks; b++) {
        int start = b * BLOCK;
        int end = std::min(start + BLOCK, n);
        std::string raw;
        int32_t prev = 0;
        for (int i = start; i < end; i++) {
            int32_t v = values[i];
            int32_t enc = delta ? (v - prev) : v;
            writeVarInt(raw, zigzag(enc));
            prev = v;
        }
        rawLens[b] = (int)raw.size();
        std::string c = zstdCompress(raw);
        compLens[b] = (int)c.size();
        comp[b] = std::move(c);
    }
    std::string head;
    be32(head, (uint32_t)n);
    be32(head, (uint32_t)blocks);
    for (int b = 0; b < blocks; b++) { be32(head, (uint32_t)rawLens[b]); be32(head, (uint32_t)compLens[b]); }
    std::string out = std::move(head);
    for (int b = 0; b < blocks; b++) out += comp[b];
    return out;
}

static std::string encodeDict(const std::vector<std::string>& sorted) {
    std::string raw;
    be32(raw, (uint32_t)sorted.size());
    for (const auto& s : sorted) { be32(raw, (uint32_t)s.size()); raw += s; }
    std::string comp = zstdCompress(raw);
    std::string out;
    be32(out, (uint32_t)raw.size());
    be32(out, (uint32_t)comp.size());
    out += comp;
    return out;
}

static void writeSection(FILE* f, const std::string& bytes) {
    std::string h; be32(h, (uint32_t)bytes.size());
    if (fwrite(h.data(), 1, 4, f) != 4) die("write section size");
    if (fwrite(bytes.data(), 1, bytes.size(), f) != bytes.size()) die("write section body");
}

// ------------------------------------------------------------------ GeoJSON field extraction
using namespace simdjson;

static std::string getStr(dom::element obj, const char* key) {
    dom::element v;
    if (obj[key].get(v) != SUCCESS) return {};
    std::string_view sv;
    if (v.get_string().get(sv) != SUCCESS) return {};
    return std::string(sv);
}
static bool getNum(dom::element e, double& out) {
    double d; if (e.get_double().get(d) == SUCCESS) { out = d; return true; }
    int64_t i; if (e.get_int64().get(i) == SUCCESS) { out = (double)i; return true; }
    uint64_t u; if (e.get_uint64().get(u) == SUCCESS) { out = (double)u; return true; }
    return false;
}
// Average of every [lon,lat] leaf (a cheap representative point for ways/areas), matching
// the old Kotlin averageCoord().
static void walkCoords(dom::element e, double& sumLon, double& sumLat, long& cnt) {
    dom::array a;
    if (e.get_array().get(a) != SUCCESS) return;
    auto it = a.begin();
    if (it == a.end()) return;
    dom::element first = *it;
    if (first.is_number()) {
        double lon, lat;
        if (!getNum(first, lon)) return;
        auto it2 = it; ++it2;
        if (it2 == a.end()) return;
        if (!getNum(*it2, lat)) return;
        sumLon += lon; sumLat += lat; cnt++;
    } else {
        for (dom::element child : a) walkCoords(child, sumLon, sumLat, cnt);
    }
}

static void generate(const char* inPath, const char* outPath);
static void verify(const char* dbPath);

// ------------------------------------------------------------------ generate
static void generate(const char* inPath, const char* outPath) {
    int fd = open(inPath, O_RDONLY);
    if (fd < 0) die(std::string("open input: ") + inPath);
    struct stat st{};
    if (fstat(fd, &st) != 0) die("fstat input");
    size_t fsize = (size_t)st.st_size;
    void* map = mmap(nullptr, fsize, PROT_READ, MAP_PRIVATE, fd, 0);
    if (map == MAP_FAILED) die("mmap input");
    madvise(map, fsize, MADV_SEQUENTIAL);
    const char* data = (const char*)map;

    fprintf(stderr, "Parsing %s (%.1f GB)...\n", inPath, fsize / 1e9);

    Interner interns[FIELDS];
    std::vector<Rec> recs;
    recs.reserve(240000000);

    dom::parser parser;

    auto isTrim = [](unsigned char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == 0x1e;
    };

    long long lines = 0, kept = 0, skipped = 0;
    size_t pos = 0;
    while (pos < fsize) {
        size_t nl = pos;
        while (nl < fsize && data[nl] != '\n') nl++;
        size_t s = pos, e = nl;
        pos = nl + 1;
        while (s < e && isTrim((unsigned char)data[s])) s++;
        while (e > s && isTrim((unsigned char)data[e - 1])) e--;
        if (s >= e || data[s] != '{') continue;
        lines++;

        dom::element doc;
        if (parser.parse(data + s, e - s, true).get(doc) != SUCCESS) { skipped++; goto progress; }
        {
            dom::element props;
            if (doc["properties"].get(props) != SUCCESS) { skipped++; goto progress; }
            std::string street = getStr(props, "addr:street");
            if (street.empty()) { skipped++; goto progress; } // require a street; drop house-only
            std::string house = getStr(props, "addr:housenumber");

            dom::element geom, coords;
            if (doc["geometry"].get(geom) != SUCCESS) { skipped++; goto progress; }
            if (geom["coordinates"].get(coords) != SUCCESS) { skipped++; goto progress; }
            double sumLon = 0, sumLat = 0; long cnt = 0;
            walkCoords(coords, sumLon, sumLat, cnt);
            if (cnt == 0) { skipped++; goto progress; }

            std::string city = getStr(props, "addr:city");
            std::string state = getStr(props, "addr:state");
            if (state.empty()) state = getStr(props, "addr:province");
            std::string country = getStr(props, "addr:country");
            std::string postcode = getStr(props, "addr:postcode");

            Rec r;
            r.latM = toMicro(sumLat / cnt);
            r.lonM = toMicro(sumLon / cnt);
            r.f[F_HOUSE] = interns[F_HOUSE].intern(house);
            r.f[F_STREET] = interns[F_STREET].intern(street);
            r.f[F_CITY] = interns[F_CITY].intern(city);
            r.f[F_STATE] = interns[F_STATE].intern(state);
            r.f[F_COUNTRY] = interns[F_COUNTRY].intern(country);
            r.f[F_POSTCODE] = interns[F_POSTCODE].intern(postcode);
            recs.push_back(r);
            kept++;
        }
    progress:
        if ((lines % 20000000) == 0 && lines > 0)
            fprintf(stderr, "  ...parsed %lld lines, kept %lld\n", lines, kept);
    }
    munmap(map, fsize);
    close(fd);

    size_t n = recs.size();
    if (n == 0) die("no records kept");
    if (n > 0x7fffffffull) die("record count exceeds 2^31 (reader uses signed int)");
    fprintf(stderr, "Parsed %lld features; kept %lld; skipped %lld.\n", lines, kept, skipped);
    for (int fi = 0; fi < FIELDS; fi++)
        fprintf(stderr, "  distinct[%d] = %zu\n", fi, interns[fi].vals.size());

    // Build sorted dictionaries (Kotlin String / UTF-16 order) and remap provisional->final ids.
    std::vector<std::vector<std::string>> dicts(FIELDS);
    std::vector<std::vector<uint32_t>> remap(FIELDS);
    for (int fi = 0; fi < FIELDS; fi++) {
        size_t m = interns[fi].vals.size();
        std::vector<uint32_t> ord(m);
        for (size_t i = 0; i < m; i++) ord[i] = (uint32_t)i;
        std::vector<std::u16string> keys(m);
        for (size_t i = 0; i < m; i++) keys[i] = toUtf16(interns[fi].vals[i]);
        std::sort(ord.begin(), ord.end(),
                  [&](uint32_t a, uint32_t b) { return keys[a] < keys[b]; });
        dicts[fi].resize(m);
        remap[fi].resize(m);
        for (size_t rank = 0; rank < m; rank++) {
            uint32_t prov = ord[rank];
            dicts[fi][rank] = interns[fi].vals[prov];
            remap[fi][prov] = (uint32_t)rank;
        }
        std::vector<std::u16string>().swap(keys);
        std::unordered_map<std::string, uint32_t>().swap(interns[fi].id);
        std::vector<std::string>().swap(interns[fi].vals);
    }
    for (auto& r : recs)
        for (int fi = 0; fi < FIELDS; fi++) r.f[fi] = remap[fi][r.f[fi]];

    // Grid-primary order: sort record indices by cell id.
    std::vector<int64_t> cell(n);
    for (size_t i = 0; i < n; i++) cell[i] = cellIdOf(recs[i].latM, recs[i].lonM);
    std::vector<uint32_t> order(n);
    for (size_t i = 0; i < n; i++) order[i] = (uint32_t)i;
    std::sort(order.begin(), order.end(),
              [&](uint32_t a, uint32_t b) { return cell[a] < cell[b]; });

    fprintf(stderr, "Writing %s (%zu records)...\n", outPath, n);
    FILE* f = fopen(outPath, "wb");
    if (!f) die(std::string("open output: ") + outPath);
    setvbuf(f, nullptr, _IOFBF, 1 << 22);

    std::string hdr;
    be32(hdr, MAGIC); be32(hdr, VERSION); be32(hdr, (uint32_t)n);
    if (fwrite(hdr.data(), 1, hdr.size(), f) != hdr.size()) die("write header");

    for (int fi = 0; fi < FIELDS; fi++) writeSection(f, encodeDict(dicts[fi]));

    std::vector<int32_t> col(n);
    auto emitCol = [&](int which, bool delta) {
        for (size_t i = 0; i < n; i++) {
            const Rec& r = recs[order[i]];
            switch (which) {
                case 0: col[i] = r.latM; break;
                case 1: col[i] = r.lonM; break;
                default: col[i] = (int32_t)r.f[which - 2]; break; // house..postcode
            }
        }
        writeSection(f, encodeColumn(col, delta));
    };
    emitCol(0, true); emitCol(1, true);                 // lat, lon
    for (int c = 2; c < 8; c++) emitCol(c, false);      // house..postcode

    {   // Grid directory: (cellId, startRec) per non-empty cell, ascending cell order.
        std::string grid, body;
        uint32_t count = 0;
        int64_t prevCell = INT64_MIN;
        for (size_t i = 0; i < n; i++) {
            int64_t c = cell[order[i]];
            if (c != prevCell) { be64(body, (uint64_t)c); be32(body, (uint32_t)i); count++; prevCell = c; }
        }
        be32(grid, count);
        grid += body;
        writeSection(f, grid);
    }

    {   // Forward ordering: positions sorted by (country,state,city,street,house), delta column.
        std::vector<int32_t> fwd(n);
        for (size_t i = 0; i < n; i++) fwd[i] = (int32_t)i;
        std::sort(fwd.begin(), fwd.end(), [&](int32_t a, int32_t b) {
            const Rec& ra = recs[order[a]];
            const Rec& rb = recs[order[b]];
            if (ra.f[F_COUNTRY] != rb.f[F_COUNTRY]) return ra.f[F_COUNTRY] < rb.f[F_COUNTRY];
            if (ra.f[F_STATE] != rb.f[F_STATE]) return ra.f[F_STATE] < rb.f[F_STATE];
            if (ra.f[F_CITY] != rb.f[F_CITY]) return ra.f[F_CITY] < rb.f[F_CITY];
            if (ra.f[F_STREET] != rb.f[F_STREET]) return ra.f[F_STREET] < rb.f[F_STREET];
            return ra.f[F_HOUSE] < rb.f[F_HOUSE];
        });
        writeSection(f, encodeColumn(fwd, true));
    }

    if (fclose(f) != 0) die("close output");

    struct stat ost{};
    stat(outPath, &ost);
    fprintf(stderr, "Done: %s = %lld bytes (%.3f GB), %.2f B/record.\n",
            outPath, (long long)ost.st_size, ost.st_size / (double)(1LL << 30),
            (double)ost.st_size / (double)n);

    fprintf(stderr, "Self-verifying...\n");
    verify(outPath);
}

// ------------------------------------------------------------------ verify (mirrors GeoDbReader)
struct MappedDb {
    const uint8_t* p = nullptr;
    size_t size = 0;
    int fd = -1;
    void openFile(const char* path) {
        fd = open(path, O_RDONLY);
        if (fd < 0) die(std::string("open db: ") + path);
        struct stat st{}; fstat(fd, &st); size = (size_t)st.st_size;
        void* m = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
        if (m == MAP_FAILED) die("mmap db");
        p = (const uint8_t*)m;
    }
    ~MappedDb() { if (p) munmap((void*)p, size); if (fd >= 0) close(fd); }
};

// A single delta/raw column, decoding one block on demand (as GeoDbReader.Column does).
struct ColReader {
    const uint8_t* base = nullptr;
    int n = 0, blocks = 0;
    bool delta = false;
    std::vector<int> rawLens, compLens;
    std::vector<size_t> blockOff;
    int cachedBlock = -1;
    std::vector<int32_t> cachedVals;
    void init(const uint8_t* fileBase, size_t bodyOff, bool d) {
        delta = d;
        const uint8_t* q = fileBase + bodyOff;
        n = (int)rd32(q); q += 4;
        blocks = (int)rd32(q); q += 4;
        rawLens.resize(blocks); compLens.resize(blocks); blockOff.resize(blocks);
        for (int b = 0; b < blocks; b++) { rawLens[b] = (int)rd32(q); q += 4; compLens[b] = (int)rd32(q); q += 4; }
        size_t off = (size_t)(q - fileBase);
        for (int b = 0; b < blocks; b++) { blockOff[b] = off; off += compLens[b]; }
        base = fileBase;
    }
    void decode(int block) {
        std::string raw = zstdDecompress(base + blockOff[block], compLens[block], rawLens[block]);
        int count = std::min(BLOCK, n - block * BLOCK);
        cachedVals.assign(count, 0);
        const uint8_t* r = (const uint8_t*)raw.data();
        size_t rp = 0; int32_t prev = 0;
        for (int k = 0; k < count; k++) {
            uint32_t v = 0; int shift = 0;
            while (true) { uint8_t b = r[rp++]; v |= (uint32_t)(b & 0x7F) << shift; if (!(b & 0x80)) break; shift += 7; }
            int32_t dv = unzigzag(v);
            int32_t actual = delta ? prev + dv : dv;
            cachedVals[k] = actual; prev = actual;
        }
        cachedBlock = block;
    }
    int32_t get(int i) {
        int block = i / BLOCK;
        if (block != cachedBlock) decode(block);
        return cachedVals[i - block * BLOCK];
    }
};

static std::vector<std::string> decodeDict(const uint8_t* section) {
    uint32_t rawSize = rd32(section), compSize = rd32(section + 4);
    std::string raw = zstdDecompress(section + 8, compSize, rawSize);
    const uint8_t* r = (const uint8_t*)raw.data();
    uint32_t count = rd32(r); r += 4;
    std::vector<std::string> out; out.reserve(count);
    for (uint32_t i = 0; i < count; i++) { uint32_t len = rd32(r); r += 4; out.emplace_back((const char*)r, len); r += len; }
    return out;
}

static void verify(const char* dbPath) {
    MappedDb db; db.openFile(dbPath);
    const uint8_t* p = db.p;
    if (rd32(p) != MAGIC) die("verify: bad magic");
    if (rd32(p + 4) != VERSION) die("verify: bad version");
    int n = (int)rd32(p + 8);
    size_t cursor = 12;
    auto readSectionOff = [&](size_t& bodyOff) {
        uint32_t sz = rd32(p + cursor); cursor += 4; bodyOff = cursor; cursor += sz;
    };

    std::vector<std::vector<std::string>> dict(FIELDS);
    for (int fi = 0; fi < FIELDS; fi++) { size_t off; readSectionOff(off); dict[fi] = decodeDict(p + off); }
    ColReader cols[8];
    bool delta[8] = {true, true, false, false, false, false, false, false};
    for (int i = 0; i < 8; i++) { size_t off; readSectionOff(off); cols[i].init(p, off, delta[i]); }

    size_t gridOff; readSectionOff(gridOff);
    const uint8_t* g = p + gridOff;
    uint32_t cellCount = rd32(g); g += 4;
    std::vector<int64_t> cellIds(cellCount);
    std::vector<int32_t> cellStarts(cellCount);
    for (uint32_t i = 0; i < cellCount; i++) { cellIds[i] = (int64_t)rd64(g); g += 8; cellStarts[i] = (int32_t)rd32(g); g += 4; }

    size_t fwdOff; readSectionOff(fwdOff);
    ColReader fwd; fwd.init(p, fwdOff, true);
    if (cursor != db.size) die("verify: trailing bytes / size mismatch");

    // Force-decode every block of every column to catch any zstd/varint/framing error.
    for (int c = 0; c < 8; c++) for (int i = 0; i < n; i += BLOCK) (void)cols[c].get(i);
    for (int i = 0; i < n; i += BLOCK) (void)fwd.get(i);

    // Reverse: nearest stored record to a coordinate (mirrors GeoDbReader.reverse).
    auto reverse = [&](double lat, double lon) -> int {
        int qLatM = toMicro(lat), qLonM = toMicro(lon);
        int row = (qLatM - MIN_LAT_MICRO) / CELL_MICRO;
        int colc = (qLonM - MIN_LON_MICRO) / CELL_MICRO;
        double lonScale = cos(lat * M_PI / 180.0);
        int bestRec = -1; double bestDist = 1e300;
        for (int radius = 1; bestRec < 0 && radius <= 32; radius++) {
            for (int r = row - radius; r <= row + radius; r++) {
                if (r < 0) continue;
                for (int c = colc - radius; c <= colc + radius; c++) {
                    if (radius > 1 && r > row - radius && r < row + radius && c > colc - radius && c < colc + radius) continue;
                    int64_t cc = ((c % COLS) + COLS) % COLS;
                    int64_t cellv = (int64_t)r * COLS + cc;
                    int lo = 0, hi = (int)cellCount - 1, gi = -1;
                    while (lo <= hi) { int mid = (lo + hi) >> 1; if (cellIds[mid] < cellv) lo = mid + 1; else if (cellIds[mid] > cellv) hi = mid - 1; else { gi = mid; break; } }
                    if (gi < 0) continue;
                    int start = cellStarts[gi];
                    int end = (gi + 1 < (int)cellCount) ? cellStarts[gi + 1] : n;
                    for (int i = start; i < end; i++) {
                        double dLat = cols[0].get(i) - qLatM;
                        double dLon = (cols[1].get(i) - qLonM) * lonScale;
                        double d = dLat * dLat + dLon * dLon;
                        if (d < bestDist) { bestDist = d; bestRec = i; }
                    }
                }
            }
        }
        return bestRec;
    };
    auto compareKey = [&](int rec, int co, int stt, int ci, int str) {
        int c;
        c = cols[6].get(rec) - co; if (c) return c;
        c = cols[5].get(rec) - stt; if (c) return c;
        c = cols[4].get(rec) - ci; if (c) return c;
        return cols[3].get(rec) - str;
    };
    auto resolve = [&](int rec, const char* tag) {
        fprintf(stderr, "  %s rec=%d  %.6f,%.6f  %s %s, %s, %s, %s %s\n", tag, rec,
                cols[0].get(rec) / 1e6, cols[1].get(rec) / 1e6,
                dict[F_HOUSE][cols[2].get(rec)].c_str(), dict[F_STREET][cols[3].get(rec)].c_str(),
                dict[F_CITY][cols[4].get(rec)].c_str(), dict[F_STATE][cols[5].get(rec)].c_str(),
                dict[F_COUNTRY][cols[6].get(rec)].c_str(), dict[F_POSTCODE][cols[7].get(rec)].c_str());
    };

    int samples[] = {0, n / 3, (2 * n) / 3, n - 1};
    int prev = -1;
    for (int s : samples) {
        if (s < 0 || s >= n || s == prev) continue;
        prev = s;
        resolve(s, "sample");
        // Forward round-trip: this record must be findable by its own key.
        int co = cols[6].get(s), stt = cols[5].get(s), ci = cols[4].get(s), str = cols[3].get(s);
        int lo = 0, hi = n;
        while (lo < hi) { int mid = (lo + hi) >> 1; if (compareKey(fwd.get(mid), co, stt, ci, str) < 0) lo = mid + 1; else hi = mid; }
        bool found = false;
        for (int k = lo; k < n; k++) { int rec = fwd.get(k); if (compareKey(rec, co, stt, ci, str) != 0) break; if (rec == s) { found = true; break; } }
        if (!found) die("verify: forward round-trip failed");
        // Reverse round-trip: nearest to the record's own coordinate is at that exact coordinate.
        double lat = cols[0].get(s) / 1e6, lon = cols[1].get(s) / 1e6;
        int rr = reverse(lat, lon);
        if (rr < 0) die("verify: reverse returned nothing");
        if (cols[0].get(rr) != toMicro(lat) || cols[1].get(rr) != toMicro(lon))
            die("verify: reverse nearest not at query coordinate");
    }

    fprintf(stderr, "Verify OK: n=%d, dicts[h/s/c/st/co/pc]=%zu/%zu/%zu/%zu/%zu/%zu, cells=%u, size=%zu bytes.\n",
            n, dict[0].size(), dict[1].size(), dict[2].size(), dict[3].size(), dict[4].size(), dict[5].size(),
            cellCount, db.size);
}

// ------------------------------------------------------------------ main
int main(int argc, char** argv) {
    if (argc >= 4 && strcmp(argv[1], "generate") == 0) { generate(argv[2], argv[3]); return 0; }
    if (argc >= 3 && strcmp(argv[1], "verify") == 0) { verify(argv[2]); return 0; }
    if (argc == 3) { generate(argv[1], argv[2]); return 0; } // shorthand: <in> <out>
    fprintf(stderr,
            "usage:\n"
            "  %s generate <addr.geojsonseq> <geocoder.geodb>\n"
            "  %s verify   <geocoder.geodb>\n",
            argv[0], argv[0]);
    return 2;
}
