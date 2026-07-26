#include <jni.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <vector>
#include <string>
#include <cmath>
#include <algorithm>
#include <mutex>
#include <set>
#include <map>
#include <android/log.h>
#include <zlib.h>
// sqlite3.c removed – maps router now memory-only. Android SQLite via Room (amenities.db) and in-memory g_traffic_by_square for traffic overlay. See CMakeLists.txt.

#include "scratchpad.h"
#include "radix_heap.h"

#define LOG_TAG "OfflineRouterNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// User-specified Travel Mode mapping
enum TravelMode { DRIVING = 0, PUBLIC_TRANSIT = 1, WALK = 2, BICYCLE = 3 };

// Road types based on OpenStreetMap tags
enum RoadType {
    MOTORWAY = 1, TRUNK = 2, PRIMARY = 3, SECONDARY = 4, TERTIARY = 5,
    UNCLASSIFIED = 6, RESIDENTIAL = 7, SERVICE = 8, LIVING_STREET = 9,
    PEDESTRIAN = 10, TRACK = 11, FOOTWAY = 12, CYCLEWAY = 13, PATH = 14, STEPS = 15
};

#define REVERSE_GEOMETRY_FLAG 0x40
#define TRANSIT_FLAG 0x80

#pragma pack(push, 1)
struct NodeMaster {
    int32_t lat_e7, lon_e7;
    uint64_t edge_ptr;
};

struct Edge {
    uint32_t target;
    uint32_t dist_mm;
    uint32_t name_offset;
    uint8_t type;
    uint8_t speed_limit;
};

struct TransitVoyageCompact {
    uint16_t dep_delta;
    uint16_t duration;
};

struct TransitAttribute {
    uint32_t stop_code_off;
    uint32_t feed_name_off;
};
#pragma pack(pop)

// Sentinel for "no edge" where a u64 global edge index is expected.
static constexpr uint64_t INVALID_EDGE = 0xFFFFFFFFFFFFFFFFULL;

// --- GLOBALS ---
// The routing graph is now a single global whole-world dataset (no zonation).
NodeMaster* g_nodes = nullptr;          // g_node_count real nodes + 1 sentinel
uint32_t g_node_count = 0;              // node IDs stay 32-bit
Edge* g_edges = nullptr;
uint64_t g_edge_count = 0;              // edge indices are global 64-bit
TransitVoyageCompact* g_transit_voyages = nullptr;
uint64_t g_transit_voyage_count = 0;
TransitAttribute* g_transit_attributes = nullptr;

char* g_road_names = nullptr;
size_t g_road_names_size = 0;
std::string g_base_path;

struct LatLon {
    int32_t lat_e7;
    int32_t lon_e7;
};

void* g_intermediate_mmap_ptr = nullptr;
size_t g_intermediate_mmap_size = 0;
uint64_t* g_intermediate_edge_offsets = nullptr;  // length g_edge_count + 1 (u64 byte offsets)
uint8_t*  g_intermediate_data = nullptr;          // delta-encoded coordinate bytes

uint64_t g_time_scale_fixed[4]; // Heuristic scales (10ms units per mm)
uint64_t g_edge_time_multipliers[4][16]; // Speed-to-time conversion factors for WALK/BIKE/DRIVING(fallback)
const double WALK_SPEED_M_S = 4.5 / 3.6;
const double BICYCLE_SPEED_M_S = 16.0 / 3.6;
const double DEG_TO_RAD = M_PI / 180.0;

uint32_t g_lon_to_mm_scale[4096];

static RoutingScratchpad g_scratchpad;
static TrafficPageTable g_traffic;
static RadixHeap g_heap; // Only one heap needed for unidirectional

static std::map<int, std::vector<double>> g_traffic_by_square;
static std::vector<uint32_t> g_requested_squares; // Packed (lat_idx << 16 | lon_idx)
static std::set<std::string> g_present_feeds;
static std::mutex g_traffic_mutex;
// g_db removed – sqlite3.c amalgamation (9MB) dropped. traffic_cache.mbtiles persistence removed; traffic now memory-only via NetworkClient + g_traffic_by_square.

// --- UTILS ---

static std::vector<uint8_t> compress_gzip(const std::vector<uint8_t>& input) {
    if (input.empty()) return {};
    z_stream zs;
    memset(&zs, 0, sizeof(zs));
    if (deflateInit2(&zs, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY) != Z_OK) return {};
    zs.next_in = (Bytef*)input.data();
    zs.avail_in = input.size();
    int ret;
    char buffer[32768];
    std::vector<uint8_t> output;
    do {
        zs.next_out = (Bytef*)buffer;
        zs.avail_out = sizeof(buffer);
        ret = deflate(&zs, Z_FINISH);
        if (output.size() < zs.total_out) {
            output.insert(output.end(), (uint8_t*)buffer, (uint8_t*)buffer + (zs.total_out - output.size()));
        }
    } while (ret == Z_OK);
    deflateEnd(&zs);
    if (ret != Z_STREAM_END) return {};
    return output;
}

inline uint32_t find_node_idx_for_edge(uint64_t edge_idx) {
    // Largest node index whose edge_ptr <= edge_idx (edge_ptr is monotonic).
    int64_t low = 0, high = (int64_t)g_node_count - 1;
    uint32_t res = 0;
    while (low <= high) {
        int64_t mid = low + (high - low) / 2;
        if (g_nodes[mid].edge_ptr <= edge_idx) {
            res = (uint32_t)mid;
            low = mid + 1;
        } else {
            high = mid - 1;
        }
    }
    return res;
}

struct EdgeCoords {
    const LatLon* coords;
    uint32_t count;
    bool is_reversed;
};

// Static decode buffer for delta-encoded intermediate coordinates.
// Edges rarely exceed ~30 shape points; 256 is very safe.
static LatLon s_decoded_coords[256];

// Decode delta-encoded coordinate blob into s_decoded_coords.
// Returns the number of decoded points.
inline uint32_t decode_edge_coords(const uint8_t* data, uint32_t byte_len) {
    if (byte_len < 8) return 0;
    // First point is absolute: int32_t lat_e7, int32_t lon_e7 (8 bytes)
    int32_t lat, lon;
    memcpy(&lat, data, 4);
    memcpy(&lon, data + 4, 4);
    s_decoded_coords[0] = { lat, lon };
    
    uint32_t count = 1;
    uint32_t off = 8;
    while (off + 4 <= byte_len && count < 256) {
        int16_t d_lat, d_lon;
        memcpy(&d_lat, data + off, 2);
        memcpy(&d_lon, data + off + 2, 2);
        lat += d_lat;
        lon += d_lon;
        s_decoded_coords[count++] = { lat, lon };
        off += 4;
    }
    return count;
}

inline EdgeCoords get_edge_coordinates(uint64_t edge_idx) {
    if (!g_edges || edge_idx >= g_edge_count) return {nullptr, 0, false};
    const Edge& e = g_edges[edge_idx];
    
    if (e.type & REVERSE_GEOMETRY_FLAG) {
        uint32_t u_global = find_node_idx_for_edge(edge_idx);
        uint32_t v_global = e.target;
        if (v_global < g_node_count && g_edges) {
            uint64_t s = g_nodes[v_global].edge_ptr;
            uint64_t e_ptr = g_nodes[v_global + 1].edge_ptr;  // sentinel guarantees valid
            for (uint64_t k = s; k < e_ptr; ++k) {
                if (g_edges[k].target == u_global) {
                    if (g_intermediate_edge_offsets != nullptr && g_intermediate_data != nullptr) {
                        uint64_t start_byte = g_intermediate_edge_offsets[k];
                        uint64_t end_byte = g_intermediate_edge_offsets[k + 1];
                        uint32_t cnt = decode_edge_coords(g_intermediate_data + start_byte, (uint32_t)(end_byte - start_byte));
                        return { s_decoded_coords, cnt, true };
                    }
                    break;
                }
            }
        }
    } else {
        if (g_intermediate_edge_offsets != nullptr && g_intermediate_data != nullptr) {
            uint64_t start_byte = g_intermediate_edge_offsets[edge_idx];
            uint64_t end_byte = g_intermediate_edge_offsets[edge_idx + 1];
            uint32_t cnt = decode_edge_coords(g_intermediate_data + start_byte, (uint32_t)(end_byte - start_byte));
            return { s_decoded_coords, cnt, false };
        }
    }
    return {nullptr, 0, false};
}

inline LatLon get_pt_at(const EdgeCoords& ec, uint32_t idx) {
    if (ec.is_reversed) {
        return ec.coords[ec.count - 1 - idx];
    } else {
        return ec.coords[idx];
    }
}

static void load_traffic_from_db(int /*packed_square*/) {
    // No-op: sqlite3.c removed. Previously loaded raw_traffic from traffic_cache.mbtiles.
    // Now traffic is memory-only (fetched via NetworkClient in OfflineRouter.kt -> updateTrafficNative).
}

// --- MVT ENCODING UTILS ---
static void write_varint(std::vector<uint8_t>& buf, uint64_t value) {
    while (value >= 0x80) {
        buf.push_back((uint8_t)(value | 0x80));
        value >>= 7;
    }
    buf.push_back((uint8_t)value);
}

static void write_tag(std::vector<uint8_t>& buf, uint32_t field, uint8_t type) {
    write_varint(buf, (field << 3) | type);
}

static void write_string(std::vector<uint8_t>& buf, uint32_t field, const std::string& s) {
    write_tag(buf, field, 2);
    write_varint(buf, s.size());
    buf.insert(buf.end(), s.begin(), s.end());
}

static int32_t zigzag(int32_t n) { return (n << 1) ^ (n >> 31); }

static uint32_t mvt_command(uint32_t cmd, uint32_t count) { return (cmd & 0x7) | (count << 3); }

struct TileProj {
    double n;
    int x, y;
    void wgs84_to_tile_px(double lat, double lon, int32_t& px, int32_t& py) const {
        double lat_rad = lat * M_PI / 180.0;
        double tx = (lon + 180.0) / 360.0 * n;
        double ty = (1.0 - std::log(std::tan(lat_rad) + (1.0 / std::cos(lat_rad))) / M_PI) / 2.0 * n;
        px = (int32_t)((tx - x) * 4096.0);
        py = (int32_t)((ty - y) * 4096.0);
    }
};

uint64_t latlng_to_spatial(double lat, double lon) {
    double x = (lon + 180.0) / 360.0;
    double y = (lat + 90.0) / 180.0;
    auto ix = (uint32_t)(x * 4294967295.0), iy = (uint32_t)(y * 4294967295.0);
    uint64_t res = 0;
    for (int i = 0; i < 32; i++) {
        res |= ((uint64_t)((ix >> i) & 1) << (2 * i));
        res |= ((uint64_t)((iy >> i) & 1) << (2 * i + 1));
    }
    return res;
}

inline uint64_t get_node_spatial_id(const NodeMaster& node) {
    return latlng_to_spatial(node.lat_e7 * 1e-7, node.lon_e7 * 1e-7);
}

inline TransitAttribute get_node_transit_attr(uint32_t node_id) {
    if (g_transit_attributes && node_id < g_node_count) {
        return g_transit_attributes[node_id];
    }
    return { 0xFFFFFFFF, 0xFFFFFFFF };
}

inline uint32_t get_node_feed_name_off(uint32_t node_id) {
    return get_node_transit_attr(node_id).feed_name_off;
}

inline uint32_t get_node_stop_code_off(uint32_t node_id) {
    return get_node_transit_attr(node_id).stop_code_off;
}

inline const NodeMaster& get_node(uint32_t node_id) {
    if (!g_nodes || node_id >= g_node_count) {
        static NodeMaster null_node = {0,0,0};
        return null_node;
    }
    return g_nodes[node_id];
}

void ensure_traffic_loaded(JNIEnv* env, jobject thiz, int32_t lat_e7, int32_t lon_e7, bool force_async) {
    int32_t lat_idx = (int32_t)floor(lat_e7 * 1e-7);
    int32_t lon_idx = (int32_t)floor(lon_e7 * 1e-7);
    uint32_t packed = ((uint32_t)(lat_idx + 360) << 16) | (uint32_t)(lon_idx + 720);

    {
        std::lock_guard<std::mutex> lock(g_traffic_mutex);
        if (std::find(g_requested_squares.begin(), g_requested_squares.end(), packed) != g_requested_squares.end()) {
            return;
        }
        LOGD("ensure_traffic_loaded: REQUESTING square %u", packed);
        g_requested_squares.push_back(packed);
    }

    jclass clazz = env->GetObjectClass(thiz);
    jmethodID method = env->GetMethodID(clazz, "fetchTrafficData", "(DDDDIZ)V");
    if (method) {
        LOGD("ensure_traffic_loaded: CALLING fetchTrafficData for square %u, async=%d", packed, force_async);
        env->CallVoidMethod(thiz, method, (double)lat_idx, (double)lon_idx, (double)lat_idx + 1.0, (double)lon_idx + 1.0, (jint)packed, (jboolean)force_async);
        LOGD("ensure_traffic_loaded: RETURNED from fetchTrafficData for square %u", packed);
    }
}

// --- ROAD PERMISSIONS ---

inline bool is_mode_allowed(uint8_t road_type, int mode) {
    uint8_t type = road_type & 0x7F;
    if (mode == DRIVING) {
        return (type >= MOTORWAY && type <= LIVING_STREET);
    }
    if (mode == PUBLIC_TRANSIT) {
        return (road_type & TRANSIT_FLAG) || (type >= MOTORWAY && type <= STEPS);
    }
    return (type >= MOTORWAY && type <= STEPS);
}

// --- GEOMETRY ---

__attribute__((always_inline)) inline uint32_t fast_dist_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    int64_t dlat = std::abs((int64_t)lat1_e7 - lat2_e7);
    int64_t dlon = std::abs((int64_t)lon1_e7 - lon2_e7);

    // 1 unit (1e-7 deg) latitude is ~11.1139 mm
    int64_t dy_mm = (dlat * 111139) / 10000;

    auto scale_idx = (uint32_t)(((lat1_e7 + lat2_e7) / 2 >> 19) + 2048);
    uint32_t scale = g_lon_to_mm_scale[scale_idx & 4095];
    int64_t dx_mm = (dlon * scale) >> 10;

    uint64_t max_v = (dx_mm > dy_mm) ? dx_mm : dy_mm;
    uint64_t min_v = (dx_mm > dy_mm) ? dy_mm : dx_mm;
    return (uint32_t)(max_v - (max_v >> 5) + (min_v >> 1) - (min_v >> 3));
}

inline uint32_t accurate_dist_mm(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7) {
    const double R = 6371000800.0;
    double phi1 = (lat1_e7 * 1e-7) * DEG_TO_RAD;
    double phi2 = (lat2_e7 * 1e-7) * DEG_TO_RAD;
    double delta_phi = (lat2_e7 - lat1_e7) * 1e-7 * DEG_TO_RAD;
    double delta_lambda = (lon2_e7 - lon1_e7) * 1e-7 * DEG_TO_RAD;
    double s_dphi = sin(delta_phi / 2.0);
    double s_dlamb = sin(delta_lambda / 2.0);
    double a = s_dphi * s_dphi + cos(phi1) * cos(phi2) * s_dlamb * s_dlamb;
    double dist = R * 2.0 * atan2(sqrt(a), sqrt(1.0 - a));
    // Saturate instead of truncating: uint32_t maxes out at ~4 294 km,
    // and cross-country transit edges can exceed that. A wraparound
    // turns a 5 000 km segment into a 705 km segment in the cost
    // function and silently breaks routing; saturating preserves the
    // "this edge is enormous" signal even if it's no longer exact.
    if (dist >= (double)UINT32_MAX) return UINT32_MAX;
    if (dist < 0.0) return 0;
    return (uint32_t)dist;
}

inline uint32_t get_edge_time_10ms(uint64_t edge_id, uint32_t dist_mm, uint8_t type, uint8_t limit, int mode) {
    if (mode == DRIVING) {
        uint8_t traffic_speed = (edge_id != INVALID_EDGE) ? g_traffic.get_speed(edge_id) : 0;
        uint8_t effective_limit = (traffic_speed > 0) ? traffic_speed : limit;
        if (effective_limit > 0) {
            double speed_m_s = (double)effective_limit / 3.6;
            return (uint32_t)((double)dist_mm / (speed_m_s * 10.0));
        }
    }
    uint64_t multiplier = g_edge_time_multipliers[mode & 0x3][(type & 0x7F) & 0xF];
    return (uint32_t)(((uint64_t)dist_mm * multiplier) >> 32);
}

inline uint32_t get_transit_edge_time_10ms(const Edge& edge, uint32_t current_time_from_start, uint32_t start_time_abs, uint32_t feed_off, bool is_boarding_or_transfer, uint32_t& wait_out, uint32_t& travel_out) {
    wait_out = 0; travel_out = 0;
    if (!g_transit_voyages) return 0xFFFFFFFF;

    uint32_t voyage_offset = edge.dist_mm;
    uint32_t voyage_count = edge.speed_limit;
    if (voyage_count == 0) return 0xFFFFFFFF;

    const char* route_name = (edge.name_offset < g_road_names_size) ? (g_road_names + edge.name_offset) : "Unknown";

    TransitVoyageCompact* base = g_transit_voyages + voyage_offset;

    if (feed_off != 0xFFFFFFFF && feed_off < g_road_names_size) {
        const char* feed_name = g_road_names + feed_off;
        if (g_present_feeds.find(feed_name) == g_present_feeds.end()) {
            wait_out = 0; 
            travel_out = base[1].dep_delta; // duration of voyage 0
            return travel_out;
        }
    }

    const uint32_t BOARDING_PENALTY = 6000; // 1 minute to platform
    uint32_t abs_now = (start_time_abs + current_time_from_start) % (24 * 3600 * 100);
    uint32_t arrival_at_platform = (abs_now + (is_boarding_or_transfer ? BOARDING_PENALTY : 0)) % (24 * 3600 * 100);
    const uint32_t DAY_10MS = 24 * 3600 * 100;

    uint32_t best_wait = 0xFFFFFFFF;
    uint32_t best_travel = 0;

    // Voyage 0
    uint32_t dep = *(uint32_t*)base;
    uint32_t duration = base[1].dep_delta;
    {
        uint32_t dep_mod = dep % DAY_10MS;
        uint32_t wait = 0;
        if (dep_mod >= arrival_at_platform) {
            wait = dep_mod - arrival_at_platform;
        } else {
            wait = (DAY_10MS - arrival_at_platform) + dep_mod;
        }
        if (wait < best_wait) {
            best_wait = wait;
            best_travel = duration;
        }
    }

    // Voyages 1+
    uint32_t dep_prev = dep;
    for (uint32_t i = 1; i < voyage_count; ++i) {
        TransitVoyageCompact compact = base[1 + i];
        dep = dep_prev + (uint32_t)compact.dep_delta * 100;
        dep_prev = dep;
        
        uint32_t dep_mod = dep % DAY_10MS;
        uint32_t wait = 0;
        if (dep_mod >= arrival_at_platform) {
            wait = dep_mod - arrival_at_platform;
        } else {
            wait = (DAY_10MS - arrival_at_platform) + dep_mod;
        }
        if (wait < best_wait) {
            best_wait = wait;
            best_travel = compact.duration;
        }
    }

    if (best_wait == 0xFFFFFFFF) return 0xFFFFFFFF;

    wait_out = best_wait; travel_out = best_travel;
    return (is_boarding_or_transfer ? BOARDING_PENALTY : 0) + best_wait + best_travel;
}

inline uint32_t heuristic_time_10ms(int32_t lat1, int32_t lon1, int32_t lat2, int32_t lon2, int mode) {
    uint32_t dist_mm = fast_dist_mm(lat1, lon1, lat2, lon2);
    uint64_t scaled_time = (uint64_t)dist_mm * g_time_scale_fixed[mode & 0x3];
    return (uint32_t)(scaled_time >> 32);
}

double get_bearing(int32_t lat1, int32_t lon1, int32_t lat2, int32_t lon2) {
    double f1 = (lat1 / 1e7) * DEG_TO_RAD;
    double f2 = (lat2 / 1e7) * DEG_TO_RAD;
    double dl = ((lon2 - lon1) / 1e7) * DEG_TO_RAD;
    double y = sin(dl) * cos(f2);
    double x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl);
    return atan2(y, x) * (180.0 / M_PI);
}

int get_maneuver(double prev_bearing, double next_bearing) {
    double angle_diff = next_bearing - prev_bearing;
    while (angle_diff < -180) angle_diff += 360;
    while (angle_diff > 180) angle_diff -= 360;
    if (angle_diff > 155 || angle_diff < -155) return 3;
    if (angle_diff < -100) return 2;
    if (angle_diff < -45) return 4;
    if (angle_diff < -10) return 1;
    if (angle_diff < 10) return 9;
    if (angle_diff < 45) return 5;
    if (angle_diff < 100) return 8;
    return 6;
}

// --- CORE ROUTING ---

struct SnappedEdge {
    uint32_t nodeA, nodeB;
    int32_t proj_lat, proj_lon;
    uint32_t distA_mm, distB_mm;
    uint8_t type;
    uint8_t speed_limit;
    uint32_t name_offset;
    uint64_t edge_idx = INVALID_EDGE;
    uint32_t segment_idx = 0;
};

struct RoutingContext {
    SnappedEdge start, end;
    uint32_t target_node = 0xFFFFFFFF;
    int iterations = 0;
    uint32_t startTime = 0;
};

struct Projection { int32_t lat_e7, lon_e7; uint32_t dist_mm; };

Projection get_projection(int32_t px, int32_t py, int32_t x1, int32_t y1, int32_t x2, int32_t y2) {
    double lat_avg = (x1 + x2) / 2.0 * 1e-7;
    double lon_scale = cos(lat_avg * DEG_TO_RAD);

    double dx = (double)x2 - x1;
    double dy = ((double)y2 - y1) * lon_scale;
    double dpx = (double)px - x1;
    double dpy = ((double)py - y1) * lon_scale;

    double mag_sq = dx * dx + dy * dy;
    double t = (mag_sq == 0) ? 0 : (dpx * dx + dpy * dy) / mag_sq;
    t = std::max(0.0, std::min(1.0, t));

    int32_t proj_lat = (int32_t)(x1 + t * (x2 - x1));
    int32_t proj_lon = (int32_t)(y1 + t * (y2 - y1));
    return {proj_lat, proj_lon, fast_dist_mm(px, py, proj_lat, proj_lon)};
}

SnappedEdge find_nearest_edge(double lat, double lon, int mode) {
    uint64_t target_spatial = latlng_to_spatial(lat, lon);
    int32_t pLat = lat * 1e7, pLon = lon * 1e7;
    SnappedEdge best;
    best.nodeA = 0xFFFFFFFF;
    best.nodeB = 0xFFFFFFFF;
    best.proj_lat = pLat;
    best.proj_lon = pLon;
    best.distA_mm = 0;
    best.distB_mm = 0;
    best.type = 0;
    best.speed_limit = 0;
    best.name_offset = 0xFFFFFFFF;
    best.edge_idx = INVALID_EDGE;
    best.segment_idx = 0;

    if (!g_nodes || g_node_count == 0) return best;

    uint32_t minSnapDist = 0xFFFFFFFF;

    // Binary search the single globally spatial-sorted node array for the node
    // nearest (just below) the target Morton code.
    uint32_t low = 0, high = g_node_count - 1, local_center = 0;
    while (low <= high) {
        uint32_t mid = low + (high - low) / 2;
        if (get_node_spatial_id(g_nodes[mid]) < target_spatial) {
            local_center = mid;
            low = mid + 1;
        } else {
            if (mid == 0) break;
            high = mid - 1;
        }
    }

    int window = 800;
    for (int i = std::max(0, (int)local_center - window); i <= std::min((int)g_node_count - 1, (int)local_center + window); ++i) {
        uint32_t u_global = (uint32_t)i;
        const auto& node_u = g_nodes[i];
        uint64_t e_ptr = g_nodes[i + 1].edge_ptr;  // sentinel guarantees valid
        for (uint64_t j = node_u.edge_ptr; j < e_ptr; ++j) {
            Edge& e = g_edges[j];
            if (e.type & TRANSIT_FLAG) continue;
            if (!is_mode_allowed(e.type, mode)) continue;
            if (e.target >= g_node_count) continue;

            EdgeCoords ec = get_edge_coordinates(j);
            if (ec.coords != nullptr && ec.count >= 2) {
                uint32_t num_pts = ec.count;
                uint32_t current_dist_from_start_mm = 0;
                for (uint32_t p = 0; p < num_pts - 1; ++p) {
                    const auto p1 = get_pt_at(ec, p);
                    const auto p2 = get_pt_at(ec, p + 1);

                    Projection proj = get_projection(pLat, pLon, p1.lat_e7, p1.lon_e7, p2.lat_e7, p2.lon_e7);
                    if (proj.dist_mm < minSnapDist) {
                        minSnapDist = proj.dist_mm;
                        best.nodeA = u_global;
                        best.nodeB = e.target;
                        best.proj_lat = proj.lat_e7;
                        best.proj_lon = proj.lon_e7;
                        best.type = e.type;
                        best.speed_limit = e.speed_limit;
                        best.name_offset = e.name_offset;
                        best.edge_idx = j;
                        best.segment_idx = p;

                        uint32_t dist_to_proj_seg_mm = fast_dist_mm(p1.lat_e7, p1.lon_e7, proj.lat_e7, proj.lon_e7);
                        best.distA_mm = current_dist_from_start_mm + dist_to_proj_seg_mm;
                        best.distB_mm = (e.dist_mm > best.distA_mm) ? (e.dist_mm - best.distA_mm) : 0;
                    }
                    current_dist_from_start_mm += fast_dist_mm(p1.lat_e7, p1.lon_e7, p2.lat_e7, p2.lon_e7);
                }
            } else {
                const auto& node_v = g_nodes[e.target];
                Projection p = get_projection(pLat, pLon, node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7);
                if (p.dist_mm < minSnapDist) {
                    minSnapDist = p.dist_mm;
                    best.nodeA = u_global; best.nodeB = e.target; best.proj_lat = p.lat_e7; best.proj_lon = p.lon_e7;
                    best.distA_mm = fast_dist_mm(p.lat_e7, p.lon_e7, node_u.lat_e7, node_u.lon_e7);
                    best.distB_mm = fast_dist_mm(p.lat_e7, p.lon_e7, node_v.lat_e7, node_v.lon_e7);
                    best.type = e.type; best.speed_limit = e.speed_limit; best.name_offset = e.name_offset;
                    best.edge_idx = j; best.segment_idx = 0;
                }
            }
        }
    }
    return best;
}

bool prepare_routing(JNIEnv* env, jobject thiz, double sLat, double sLon, double eLat, double eLon, int mode, uint32_t startTime, RoutingContext& ctx) {
    LOGD("prepare_routing: mode=%d, start=(%f,%f), end=(%f,%f)", mode, sLat, sLon, eLat, eLon);
    g_scratchpad.reset(); g_heap.clear();
    ctx.iterations = 0; ctx.target_node = 0xFFFFFFFF; ctx.startTime = startTime;
    ctx.start = find_nearest_edge(sLat, sLon, mode);
    ctx.end = find_nearest_edge(eLat, eLon, mode);
    if (ctx.start.nodeA == 0xFFFFFFFF) {
        LOGE("prepare_routing: failed to find start edge");
        return false;
    }
    if (ctx.end.nodeA == 0xFFFFFFFF) {
        LOGE("prepare_routing: failed to find end edge");
        return false;
    }
    LOGD("prepare_routing: start snapped to nodeA=%u, nodeB=%u", ctx.start.nodeA, ctx.start.nodeB);
    if (mode == DRIVING) ensure_traffic_loaded(env, thiz, ctx.start.proj_lat, ctx.start.proj_lon, false);
    auto push = [&](uint32_t node, uint32_t travel_dist_mm) {
        uint32_t t_actual = get_edge_time_10ms(INVALID_EDGE, travel_dist_mm, ctx.start.type, ctx.start.speed_limit, mode);
        auto& entry = g_scratchpad.get_entry(node, 0);
        entry.g_fwd = t_actual;
        entry.g_bwd = t_actual;
        entry.last_type = ctx.start.type;
        const auto& n_data = get_node(node);
        uint32_t h = heuristic_time_10ms(n_data.lat_e7, n_data.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode);
        g_heap.push(t_actual + h, node << 1); // Start in state 0
    };
    push(ctx.start.nodeA, ctx.start.distA_mm); push(ctx.start.nodeB, ctx.start.distB_mm);
    return true;
}

void perform_search_loop(JNIEnv* env, jobject thiz, int mode, RoutingContext& ctx) {
    LOGD("perform_search_loop: starting search");
    while (!g_heap.empty() && ctx.iterations < 1000000) {
        ctx.iterations++;
        uint32_t u_idx = g_heap.pop();
        uint32_t u = u_idx >> 1;
        int u_state = u_idx & 1;

        if ((u == ctx.end.nodeA || u == ctx.end.nodeB) && (mode != PUBLIC_TRANSIT || u_state == 1)) {
            ctx.target_node = u_idx;
            LOGD("perform_search_loop: target found in %d iterations", ctx.iterations);
            break;
        }

        auto& entry_u = g_scratchpad.get_entry(u, u_state);
        uint32_t u_cost = entry_u.g_fwd;
        uint32_t u_actual_time = entry_u.g_bwd;
        if (!g_nodes || u >= g_node_count) continue;
        const auto& n_u = g_nodes[u];

        if (mode == DRIVING) ensure_traffic_loaded(env, thiz, n_u.lat_e7, n_u.lon_e7, false);
        uint64_t s = n_u.edge_ptr;
        uint64_t e_ptr = g_nodes[u + 1].edge_ptr;  // sentinel guarantees valid

        for (uint64_t i = s; i < e_ptr; ++i) {
            Edge& edge = g_edges[i];
            if (!is_mode_allowed(edge.type, mode)) continue;

            uint32_t travel_time;
            int v_state = (mode == PUBLIC_TRANSIT && (edge.type & TRANSIT_FLAG)) ? 1 : 0;
            if (v_state == 1) {
                bool is_boarding = !(entry_u.last_type & TRANSIT_FLAG);
                bool is_transfer = !is_boarding && (edge.name_offset != entry_u.last_name_off);

                TransitAttribute ta_u = get_node_transit_attr(u);
                // FIX: Only allow boarding or transfers at recognized transit stops
                if ((is_boarding || is_transfer) && ta_u.stop_code_off == 0xFFFFFFFF) continue;

                uint32_t wait, transit_move;
                travel_time = get_transit_edge_time_10ms(edge, u_actual_time, ctx.startTime, ta_u.feed_name_off, is_boarding || is_transfer, wait, transit_move);
                if (travel_time == 0xFFFFFFFF) continue;
            } else {
                // FIX: Only allow alighting from transit at recognized transit stops
                if (mode == PUBLIC_TRANSIT && (entry_u.last_type & TRANSIT_FLAG)) {
                    TransitAttribute ta_u = get_node_transit_attr(u);
                    if (ta_u.stop_code_off == 0xFFFFFFFF) continue;
                }
                travel_time = get_edge_time_10ms(i, edge.dist_mm, edge.type, edge.speed_limit, mode);
            }

            uint32_t v = edge.target;
            uint32_t new_g = u_cost + travel_time;
            auto& entry_v = g_scratchpad.get_entry(v, v_state);
            if (new_g < entry_v.g_fwd) {
                entry_v.g_fwd = new_g;
                entry_v.g_bwd = new_g;
                entry_v.p_fwd = u_idx;
                entry_v.last_type = edge.type;
                entry_v.last_name_off = edge.name_offset;
                const auto& n_v = get_node(v);
                uint32_t h = heuristic_time_10ms(n_v.lat_e7, n_v.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode);
                g_heap.push(new_g + h, (v << 1) | v_state);
            }
        }
    }
}

jobjectArray reconstruct_path(JNIEnv* env, int mode, const RoutingContext& ctx) {
    LOGD("reconstruct_path: mode=%d, target_idx=%u", mode, ctx.target_node);
    std::vector<uint32_t> path_indices;
    uint32_t curr = ctx.target_node;
    uint32_t safety = 0;
    while (curr != 0xFFFFFFFF && safety < 1000000) {
        path_indices.push_back(curr);
        curr = g_scratchpad.get_entry(curr >> 1, curr & 1).p_fwd;
        safety++;
    }
    std::reverse(path_indices.begin(), path_indices.end());
    if (path_indices.empty()) return nullptr;

    std::vector<uint32_t> path_nodes;
    for (uint32_t idx : path_indices) path_nodes.push_back(idx >> 1);

    struct StepData {
        uint32_t name_off; uint64_t dist_mm = 0; uint64_t time_10ms = 0;
        std::vector<double> coords; int maneuver = 0;
        double speed_ratio = 1.0;
        bool is_transit = false;
        uint32_t feed_off = 0xFFFFFFFF;
        uint32_t code_off = 0xFFFFFFFF;
        uint32_t end_code_off = 0xFFFFFFFF;
        int stop_count = 0;
    };
    std::vector<StepData> steps; double last_bearing = 0;
    uint32_t current_elapsed_10ms = 0;
    bool last_was_transit = false;

    auto add_segment = [&](double lat1, double lon1, double lat2, double lon2, uint32_t name_off, uint8_t type, uint8_t limit, uint32_t dist_mm, uint64_t edge_idx, uint32_t feed_off, uint32_t code_off, uint32_t target_code_off) {
        double ratio = 1.0;
        uint8_t traffic_speed = 0;
        if (edge_idx != INVALID_EDGE) {
            traffic_speed = g_traffic.get_speed(edge_idx);
            if (traffic_speed > 0 && limit > 0) ratio = (double)traffic_speed / limit;
        }

        uint32_t time_10ms;
        bool is_transit = false;
        if (mode == PUBLIC_TRANSIT && (type & TRANSIT_FLAG)) {
            is_transit = true;
            if (edge_idx != INVALID_EDGE) {
                bool is_boarding = !last_was_transit;
                bool is_transfer = last_was_transit && (name_off != steps.back().name_off);
                uint32_t wait, transit_move;
                time_10ms = get_transit_edge_time_10ms(g_edges[edge_idx], current_elapsed_10ms, ctx.startTime, feed_off, is_boarding || is_transfer, wait, transit_move);
                if (time_10ms == 0xFFFFFFFF) { time_10ms = 0; wait = 0; transit_move = 0; }

                if ((is_boarding || is_transfer)) {
                    uint32_t penalty = 6000;
                    steps.push_back({name_off, 0, wait + penalty, {lon1, lat1}, 21, 1.0, true, feed_off, code_off});
                    current_elapsed_10ms += (wait + penalty);
                    time_10ms = transit_move;
                } else {
                    time_10ms = transit_move;
                }
            } else {
                time_10ms = 0;
            }
        } else {
            time_10ms = get_edge_time_10ms(edge_idx, dist_mm, type, limit, mode);
        }
        current_elapsed_10ms += time_10ms;
        last_was_transit = (type & TRANSIT_FLAG);
        double bearing = get_bearing((int32_t)(lat1 * 1e7), (int32_t)(lon1 * 1e7), (int32_t)(lat2 * 1e7), (int32_t)(lon2 * 1e7));

        auto get_ratio_cat = [](double r) {
            if (r < 0.5) return 0;
            if (r < 0.9) return 1;
            return 2;
        };

        int maneuver = steps.empty() ? 0 : get_maneuver(last_bearing, bearing);
        if (is_transit && !steps.empty() && steps.back().maneuver != 21) {
            maneuver = 23; // RIDE
        }

        if (steps.empty() || name_off != steps.back().name_off || get_ratio_cat(ratio) != get_ratio_cat(steps.back().speed_ratio) || is_transit != steps.back().is_transit || (maneuver != 9 && maneuver != 0 && maneuver != 23) || steps.back().maneuver == 21) {
            steps.push_back({name_off, 0, 0, {lon1, lat1}, maneuver, ratio, is_transit, feed_off, code_off, 0xFFFFFFFF, 0});
        }
        steps.back().dist_mm += dist_mm;
        steps.back().time_10ms += time_10ms;
        steps.back().coords.push_back(lon2);
        steps.back().coords.push_back(lat2);
        if (is_transit && steps.back().maneuver != 21) {
            steps.back().end_code_off = target_code_off;
            steps.back().stop_count++;
        }
        last_bearing = bearing;
    };

    // 1. Start stub: proj_s -> path_nodes[0]
    {
        uint32_t n0 = path_nodes[0];
        const auto& node0 = get_node(n0);
        uint64_t j = ctx.start.edge_idx;
        
        EdgeCoords ec = get_edge_coordinates(j);
        if (j != INVALID_EDGE && ec.coords != nullptr && ec.count >= 2) {
            uint32_t num_pts = ec.count;
            uint32_t seg_idx = ctx.start.segment_idx;
            
            if (n0 == ctx.start.nodeA) {
                // Snapped point to P_seg_idx
                const auto p_next = get_pt_at(ec, seg_idx);
                uint32_t d1 = fast_dist_mm(ctx.start.proj_lat, ctx.start.proj_lon, p_next.lat_e7, p_next.lon_e7);
                add_segment(ctx.start.proj_lat * 1e-7, ctx.start.proj_lon * 1e-7, p_next.lat_e7 * 1e-7, p_next.lon_e7 * 1e-7,
                            ctx.start.name_offset, ctx.start.type, ctx.start.speed_limit, d1, j, get_node_feed_name_off(n0), get_node_stop_code_off(n0), get_node_stop_code_off(n0));
                
                // Then trace backwards: P_p -> P_{p-1}
                for (int p = (int)seg_idx; p >= 1; --p) {
                    const auto p_from = get_pt_at(ec, p);
                    const auto p_to = get_pt_at(ec, p - 1);
                    uint32_t d_seg = fast_dist_mm(p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    add_segment(p_from.lat_e7 * 1e-7, p_from.lon_e7 * 1e-7, p_to.lat_e7 * 1e-7, p_to.lon_e7 * 1e-7,
                                ctx.start.name_offset, ctx.start.type, ctx.start.speed_limit, d_seg, j, get_node_feed_name_off(n0), get_node_stop_code_off(n0), get_node_stop_code_off(n0));
                }
            } else {
                // Snapped point to P_{seg_idx + 1}
                const auto p_next = get_pt_at(ec, seg_idx + 1);
                uint32_t d1 = fast_dist_mm(ctx.start.proj_lat, ctx.start.proj_lon, p_next.lat_e7, p_next.lon_e7);
                add_segment(ctx.start.proj_lat * 1e-7, ctx.start.proj_lon * 1e-7, p_next.lat_e7 * 1e-7, p_next.lon_e7 * 1e-7,
                            ctx.start.name_offset, ctx.start.type, ctx.start.speed_limit, d1, j, get_node_feed_name_off(n0), get_node_stop_code_off(n0), get_node_stop_code_off(n0));
                
                // Then trace forwards: P_p -> P_{p+1}
                for (uint32_t p = seg_idx + 1; p < num_pts - 1; ++p) {
                    const auto p_from = get_pt_at(ec, p);
                    const auto p_to = get_pt_at(ec, p + 1);
                    uint32_t d_seg = fast_dist_mm(p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    add_segment(p_from.lat_e7 * 1e-7, p_from.lon_e7 * 1e-7, p_to.lat_e7 * 1e-7, p_to.lon_e7 * 1e-7,
                                ctx.start.name_offset, ctx.start.type, ctx.start.speed_limit, d_seg, j, get_node_feed_name_off(n0), get_node_stop_code_off(n0), get_node_stop_code_off(n0));
                }
            }
        } else {
            uint32_t dist = (n0 == ctx.start.nodeA) ? ctx.start.distA_mm : ctx.start.distB_mm;
            add_segment(ctx.start.proj_lat * 1e-7, ctx.start.proj_lon * 1e-7, node0.lat_e7 * 1e-7, node0.lon_e7 * 1e-7,
                        ctx.start.name_offset, ctx.start.type, ctx.start.speed_limit, dist, INVALID_EDGE, get_node_feed_name_off(n0), get_node_stop_code_off(n0), get_node_stop_code_off(n0));
        }
    }

    // 2. Main path segments
    for (size_t i = 0; i < path_nodes.size() - 1; ++i) {
        uint32_t u = path_nodes[i], v = path_nodes[i+1];
        if (!g_nodes || u >= g_node_count) continue;
        const auto& node_u = g_nodes[u];
        const auto& node_v = get_node(v);

        uint64_t s = node_u.edge_ptr;
        uint64_t e_ptr = g_nodes[u + 1].edge_ptr;  // sentinel guarantees valid

        uint64_t best_e_idx = INVALID_EDGE;
        for (uint64_t k = s; k < e_ptr; ++k) {
            if (g_edges[k].target == v) {
                best_e_idx = k; break;
            }
        }
        if (best_e_idx == INVALID_EDGE) continue;

        const Edge& e = g_edges[best_e_idx];
        uint32_t d = e.dist_mm;
        if (d == 0 || (e.type & TRANSIT_FLAG)) {
            d = accurate_dist_mm(node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7);
        }

        EdgeCoords ec = get_edge_coordinates(best_e_idx);
        if (ec.coords != nullptr && ec.count >= 2) {
            uint32_t num_pts = ec.count;
            for (uint32_t p = 0; p < num_pts - 1; ++p) {
                const auto p1 = get_pt_at(ec, p);
                const auto p2 = get_pt_at(ec, p + 1);
                uint32_t seg_dist = fast_dist_mm(p1.lat_e7, p1.lon_e7, p2.lat_e7, p2.lon_e7);
                add_segment(p1.lat_e7 * 1e-7, p1.lon_e7 * 1e-7, p2.lat_e7 * 1e-7, p2.lon_e7 * 1e-7,
                            e.name_offset, e.type, e.speed_limit, seg_dist, best_e_idx, get_node_feed_name_off(u), get_node_stop_code_off(u), get_node_stop_code_off(v));
            }
        } else {
            add_segment(node_u.lat_e7 * 1e-7, node_u.lon_e7 * 1e-7, node_v.lat_e7 * 1e-7, node_v.lon_e7 * 1e-7,
                        e.name_offset, e.type, e.speed_limit, d, best_e_idx, get_node_feed_name_off(u), get_node_stop_code_off(u), get_node_stop_code_off(v));
        }
    }

    // 3. End stub: path_nodes.back() -> proj_e
    {
        uint32_t nk = path_nodes.back();
        const auto& nodek = get_node(nk);
        uint64_t j = ctx.end.edge_idx;
        
        EdgeCoords ec = get_edge_coordinates(j);
        if (j != INVALID_EDGE && ec.coords != nullptr && ec.count >= 2) {
            uint32_t num_pts = ec.count;
            uint32_t seg_idx = ctx.end.segment_idx;
            
            if (nk == ctx.end.nodeA) {
                // Trace forwards: P_p -> P_{p+1} up to seg_idx
                for (uint32_t p = 0; p < seg_idx; ++p) {
                    const auto p_from = get_pt_at(ec, p);
                    const auto p_to = get_pt_at(ec, p + 1);
                    uint32_t d_seg = fast_dist_mm(p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    add_segment(p_from.lat_e7 * 1e-7, p_from.lon_e7 * 1e-7, p_to.lat_e7 * 1e-7, p_to.lon_e7 * 1e-7,
                                ctx.end.name_offset, ctx.end.type, ctx.end.speed_limit, d_seg, j, get_node_feed_name_off(nk), get_node_stop_code_off(nk), 0xFFFFFFFF);
                }
                
                // Then final segment: P_seg_idx -> Proj
                const auto p_last = get_pt_at(ec, seg_idx);
                uint32_t d2 = fast_dist_mm(p_last.lat_e7, p_last.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon);
                add_segment(p_last.lat_e7 * 1e-7, p_last.lon_e7 * 1e-7, ctx.end.proj_lat * 1e-7, ctx.end.proj_lon * 1e-7,
                            ctx.end.name_offset, ctx.end.type, ctx.end.speed_limit, d2, j, get_node_feed_name_off(nk), get_node_stop_code_off(nk), 0xFFFFFFFF);
            } else {
                // Trace backwards: P_p -> P_{p-1} down to seg_idx + 1
                for (int p = (int)num_pts - 1; p > (int)seg_idx + 1; --p) {
                    const auto p_from = get_pt_at(ec, p);
                    const auto p_to = get_pt_at(ec, p - 1);
                    uint32_t d_seg = fast_dist_mm(p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    add_segment(p_from.lat_e7 * 1e-7, p_from.lon_e7 * 1e-7, p_to.lat_e7 * 1e-7, p_to.lon_e7 * 1e-7,
                                ctx.end.name_offset, ctx.end.type, ctx.end.speed_limit, d_seg, j, get_node_feed_name_off(nk), get_node_stop_code_off(nk), 0xFFFFFFFF);
                }
                
                // Then final segment: P_{seg_idx + 1} -> Proj
                const auto p_last = get_pt_at(ec, seg_idx + 1);
                uint32_t d2 = fast_dist_mm(p_last.lat_e7, p_last.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon);
                add_segment(p_last.lat_e7 * 1e-7, p_last.lon_e7 * 1e-7, ctx.end.proj_lat * 1e-7, ctx.end.proj_lon * 1e-7,
                            ctx.end.name_offset, ctx.end.type, ctx.end.speed_limit, d2, j, get_node_feed_name_off(nk), get_node_stop_code_off(nk), 0xFFFFFFFF);
            }
        } else {
            uint32_t dist = (nk == ctx.end.nodeA) ? ctx.end.distA_mm : ctx.end.distB_mm;
            add_segment(nodek.lat_e7 * 1e-7, nodek.lon_e7 * 1e-7, ctx.end.proj_lat * 1e-7, ctx.end.proj_lon * 1e-7,
                        ctx.end.name_offset, ctx.end.type, ctx.end.speed_limit, dist, INVALID_EDGE, get_node_feed_name_off(nk), get_node_stop_code_off(nk), 0xFFFFFFFF);
        }
    }

    jclass stepClass = env->FindClass("com/vayunmathur/maps/util/OfflineRouter$RawStep");
    if (!stepClass) {
        LOGE("reconstruct_path: could not find RawStep class");
        return nullptr;
    }
    jmethodID stepCtor = env->GetMethodID(stepClass, "<init>", "(ILjava/lang/String;JJ[DDZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    if (!stepCtor) {
        LOGE("reconstruct_path: could not find RawStep constructor");
        return nullptr;
    }
    LOGD("reconstruct_path: creating array of size %zu", steps.size());
    jobjectArray res = env->NewObjectArray(steps.size(), stepClass, nullptr);
    for (size_t i = 0; i < steps.size(); ++i) {
        const char* name_ptr = (steps[i].name_off < g_road_names_size) ? (g_road_names + steps[i].name_off) : "Unknown Road";
        jstring jName = env->NewStringUTF(name_ptr); jdoubleArray jGeom = env->NewDoubleArray(steps[i].coords.size());
        env->SetDoubleArrayRegion(jGeom, 0, steps[i].coords.size(), steps[i].coords.data());

        jstring jFeed = nullptr;
        if (steps[i].feed_off != 0xFFFFFFFF && steps[i].feed_off < g_road_names_size) {
            jFeed = env->NewStringUTF(g_road_names + steps[i].feed_off);
        }
        jstring jCode = nullptr;
        if (steps[i].code_off != 0xFFFFFFFF && steps[i].code_off < g_road_names_size) {
            jCode = env->NewStringUTF(g_road_names + steps[i].code_off);
        }
        jstring jEndCode = nullptr;
        if (steps[i].end_code_off != 0xFFFFFFFF && steps[i].end_code_off < g_road_names_size) {
            jEndCode = env->NewStringUTF(g_road_names + steps[i].end_code_off);
        }

        jobject stepObj = env->NewObject(stepClass, stepCtor, (jint)steps[i].maneuver, jName, (jlong)steps[i].dist_mm, (jlong)steps[i].time_10ms, jGeom, (jdouble)steps[i].speed_ratio, (jboolean)steps[i].is_transit, jFeed, jCode, jEndCode, (jint)steps[i].stop_count);
        env->SetObjectArrayElement(res, i, stepObj);
        env->DeleteLocalRef(jName); env->DeleteLocalRef(jGeom); env->DeleteLocalRef(stepObj);
        if (jFeed) env->DeleteLocalRef(jFeed);
        if (jCode) env->DeleteLocalRef(jCode);
        if (jEndCode) env->DeleteLocalRef(jEndCode);
    }
    return res;
}

// --- JNI INTERFACE ---

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_init(JNIEnv* env, jobject thiz, jstring base_path, jobjectArray present_feeds) {
    const char* path_raw = env->GetStringUTFChars(base_path, nullptr); std::string base(path_raw);
    LOGD("Native init started with path: %s", path_raw);
    if (!base.empty() && base.back() != '/') base += "/";
    g_base_path = base;

    g_present_feeds.clear();
    if (present_feeds) {
        int len = env->GetArrayLength(present_feeds);
        for (int i = 0; i < len; ++i) {
            jstring s = (jstring)env->GetObjectArrayElement(present_feeds, i);
            const char* raw = env->GetStringUTFChars(s, nullptr);
            g_present_feeds.insert(raw);
            env->ReleaseStringUTFChars(s, raw);
            env->DeleteLocalRef(s);
        }
    }

    auto m_file = [&](const std::string& p, size_t& s) -> void* {
        s = 0; int fd = open(p.c_str(), O_RDONLY); if (fd < 0) return nullptr;
        off_t end = lseek(fd, 0, SEEK_END);
        if (end <= 0) { close(fd); return nullptr; }   // empty / unreadable file
        s = (size_t)end;
        void* a = mmap(nullptr, s, PROT_READ, MAP_SHARED, fd, 0); close(fd);
        return (a == MAP_FAILED) ? nullptr : a;
    };
    // metadata.bin is now a single u64 node_count.
    size_t s_meta; uint64_t* meta = (uint64_t*)m_file(base + "metadata.bin", s_meta);
    if (!meta || s_meta < sizeof(uint64_t)) {
        if (meta) munmap(meta, s_meta);
        env->ReleaseStringUTFChars(base_path, path_raw);
        return false;
    }
    g_node_count = (uint32_t)meta[0];
    munmap(meta, s_meta);

    size_t s_n, s_e, s_t, s_ta;
    g_nodes = (NodeMaster*)m_file(base + "nodes.bin", s_n);
    g_edges = (Edge*)m_file(base + "edges.bin", s_e);
    g_edge_count = s_e / sizeof(Edge);
    g_transit_voyages = (TransitVoyageCompact*)m_file(base + "transit_voyages.bin", s_t);
    g_transit_voyage_count = s_t / sizeof(TransitVoyageCompact);
    g_transit_attributes = (TransitAttribute*)m_file(base + "transit_attributes.bin", s_ta);
    if (!g_nodes || !g_edges) {
        env->ReleaseStringUTFChars(base_path, path_raw);
        return false;
    }

    // intermediate.bin: [ u64 edge_offsets[g_edge_count + 1] ][ coord blob ].
    g_intermediate_mmap_ptr = m_file(base + "intermediate.bin", g_intermediate_mmap_size);
    if (g_intermediate_mmap_ptr) {
        g_intermediate_edge_offsets = (uint64_t*)g_intermediate_mmap_ptr;
        g_intermediate_data = (uint8_t*)((char*)g_intermediate_mmap_ptr + (g_edge_count + 1) * sizeof(uint64_t));
    }

    size_t s_r; g_road_names = (char*)m_file(base + "road_names.bin", s_r); g_road_names_size = s_r;
    for (int i = 0; i < 4096; ++i) {
        double lat_deg = ((double)((int64_t)(i - 2048) << 19)) / 1e7;
        g_lon_to_mm_scale[i] = (uint32_t)((111139000.0 / 1e7) * cos(lat_deg * DEG_TO_RAD) * 1024.0);
    }
    auto calc_scale = [](double speed_m_s) { return (uint64_t)((100.0 / (speed_m_s * 1000.0)) * 4294967296.0); };

    // HEURISTIC SCALES FIX: We increase transit speed bounds (using a highly optimistic 80 km/h)
    // to ensure the heuristic remains optimistic/admissible, keeping search components directed
    // without over-penalizing transit extensions and yielding flat routes instead of jagged ones.
    g_time_scale_fixed[WALK] = calc_scale(WALK_SPEED_M_S);
    g_time_scale_fixed[BICYCLE] = calc_scale(BICYCLE_SPEED_M_S);
    g_time_scale_fixed[DRIVING] = calc_scale(105.0 / 3.6);
    g_time_scale_fixed[PUBLIC_TRANSIT] = calc_scale(80.0 / 3.6); // Optimistic 80 km/h transit velocity

    for (int m = 0; m < 4; ++m) {
        for (int r = 0; r < 16; ++r) {
            double speed_m_s = 1.0;
            if (m == DRIVING) {
                switch(r) {
                    case MOTORWAY: speed_m_s = 105.0/3.6; break; case TRUNK: speed_m_s = 85.0/3.6; break;
                    case PRIMARY: speed_m_s = 65.0/3.6; break; case SECONDARY: speed_m_s = 55.0/3.6; break;
                    case TERTIARY: speed_m_s = 45.0/3.6; break; default: speed_m_s = 30.0/3.6;
                }
            } else { speed_m_s = (m == BICYCLE) ? BICYCLE_SPEED_M_S : WALK_SPEED_M_S; }
            g_edge_time_multipliers[m][r] = (uint64_t)((100.0 / (speed_m_s * 1000.0)) * 4294967296.0);
        }
    }
    // sqlite3.c removed (9MB) – maps now memory-only, no traffic_cache.mbtiles
    // Android SQLite remains via Room for amenities.db (androidx.sqlite)
    env->ReleaseStringUTFChars(base_path, path_raw); return true;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_findRouteNative(JNIEnv* env, jobject thiz, jdouble sLat, jdouble sLon, jdouble eLat, jdouble eLon, jint mode, jlong startTime) {
    LOGD("findRouteNative: sLat=%f, sLon=%f, eLat=%f, eLon=%f, mode=%d", sLat, sLon, eLat, eLon, mode);
    RoutingContext ctx; if (!prepare_routing(env, thiz, sLat, sLon, eLat, eLon, mode, (uint32_t)startTime, ctx)) return nullptr;
    perform_search_loop(env, thiz, mode, ctx);
    if (ctx.target_node == 0xFFFFFFFF) {
        LOGE("findRouteNative: no path found after %d iterations", ctx.iterations);
        return nullptr;
    }
    LOGD("findRouteNative: path found, reconstructing...");
    return reconstruct_path(env, mode, ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_updateTrafficNative(JNIEnv* env, jobject thiz, jlongArray edge_ids, jbyteArray speeds, jint packed_square) {
    jsize len = env->GetArrayLength(edge_ids); jlong* ids_ptr = env->GetLongArrayElements(edge_ids, nullptr);
    jbyte* speeds_ptr = env->GetByteArrayElements(speeds, nullptr);
    std::lock_guard<std::mutex> lock(g_traffic_mutex);
auto& segments = g_traffic_by_square[packed_square];
    segments.clear();
    for (jsize i = 0; i < len; i++) {
        uint64_t edge_id = (uint64_t)ids_ptr[i]; uint8_t speed = (uint8_t)speeds_ptr[i];
        if (g_edges && edge_id < g_edge_count) {
            const Edge& edge = g_edges[edge_id];
            if (speed < 255) {
                g_traffic.set_speed(edge_id, speed);
                const NodeMaster& node_u = g_nodes[find_node_idx_for_edge(edge_id)];
                if (edge.target < g_node_count) {
                    const NodeMaster& node_v = g_nodes[edge.target];
                    double ratio = (edge.speed_limit > 0) ? (double)speed / edge.speed_limit : 1.0;
                    segments.push_back(node_u.lat_e7 * 1e-7); segments.push_back(node_u.lon_e7 * 1e-7);
                    segments.push_back(node_v.lat_e7 * 1e-7); segments.push_back(node_v.lon_e7 * 1e-7);
                    segments.push_back(ratio);
                }
            }
        }
    }
    env->ReleaseLongArrayElements(edge_ids, ids_ptr, JNI_ABORT); env->ReleaseByteArrayElements(speeds, speeds_ptr, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_notifyTrafficFetchFinishedNative(JNIEnv* env, jobject thiz, jint packed_square) {
    LOGD("notifyTrafficFetchFinishedNative: packed %u", (uint32_t)packed_square);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_getTrafficSegmentsNative(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_traffic_mutex);
    std::vector<double> flattened;
    for (auto const& [square, segments] : g_traffic_by_square) {
        flattened.insert(flattened.end(), segments.begin(), segments.end());
        if (flattened.size() > 50000) break;
    }
    size_t count = flattened.size();
    if (count > 50000) count = 50000;
    jdoubleArray jRes = env->NewDoubleArray(count);
    if (count > 0) env->SetDoubleArrayRegion(jRes, 0, count, flattened.data());
    return jRes;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_getTrafficTileNative(JNIEnv* env, jobject thiz, jint z, jint x, jint y) {
    std::lock_guard<std::mutex> lock(g_traffic_mutex);
    int tms_y = (1 << z) - 1 - y;
    (void)tms_y; // previously used for MBTiles row flip, now tiles generated on-the-fly

    double n = std::pow(2.0, z);
    double lon_min = (double)x / n * 360.0 - 180.0;
    double lon_max = (double)(x + 1) / n * 360.0 - 180.0;
    double lat_max = std::atan(std::sinh(M_PI * (1 - 2 * (double)y / n))) * 180.0 / M_PI;
    double lat_min = std::atan(std::sinh(M_PI * (1 - 2 * (double)(y + 1) / n))) * 180.0 / M_PI;

    TileProj proj{n, x, y};
    std::vector<uint8_t> layer_buf;
    write_string(layer_buf, 1, "traffic");
    write_tag(layer_buf, 15, 0); write_varint(layer_buf, 2);
    write_tag(layer_buf, 5, 0); write_varint(layer_buf, 4096);

    int min_lat_idx = (int)std::floor(lat_min); int max_lat_idx = (int)std::floor(lat_max);
    int min_lon_idx = (int)std::floor(lon_min); int max_lon_idx = (int)std::floor(lon_max);

    std::vector<std::string> keys = {"color"};
    std::vector<std::string> values;
    std::map<std::string, uint32_t> val_map;
    auto get_val_idx = [&](const std::string& v) {
        if (val_map.find(v) == val_map.end()) { val_map[v] = values.size(); values.push_back(v); }
        return val_map[v];
    };

    for (int lat_i = min_lat_idx; lat_i <= max_lat_idx; ++lat_i) {
        for (int lon_i = min_lon_idx; lon_i <= max_lon_idx; ++lon_i) {
            uint32_t square_id = ((uint32_t)(lat_i + 360) << 16) | (uint32_t)(lon_i + 720);
            if (g_traffic_by_square.find(square_id) == g_traffic_by_square.end()) load_traffic_from_db(square_id);
            auto it = g_traffic_by_square.find(square_id);
            if (it == g_traffic_by_square.end()) continue;

            const auto& data = it->second;
            for (size_t i = 0; i + 4 < data.size(); i += 5) {
                double lat1 = data[i], lon1 = data[i+1], lat2 = data[i+2], lon2 = data[i+3], speed_ratio = data[i+4];
                if (speed_ratio <= 0.0) continue;
                int32_t px1, py1, px2, py2;
                proj.wgs84_to_tile_px(lat1, lon1, px1, py1); proj.wgs84_to_tile_px(lat2, lon2, px2, py2);
                if (std::max(px1, px2) < -512 || std::min(px1, px2) > 4608 || std::max(py1, py2) < -512 || std::min(py1, py2) > 4608) continue;

                std::vector<uint8_t> feat_buf;
                write_varint(feat_buf, (3 << 3) | 0); write_varint(feat_buf, 2);
                std::vector<uint8_t> geom_buf;
                write_varint(geom_buf, mvt_command(1, 1)); write_varint(geom_buf, zigzag(px1)); write_varint(geom_buf, zigzag(py1));
                write_varint(geom_buf, mvt_command(2, 1)); write_varint(geom_buf, zigzag(px2 - px1)); write_varint(geom_buf, zigzag(py2 - py1));
                write_tag(feat_buf, 4, 2); write_varint(feat_buf, geom_buf.size());
                feat_buf.insert(feat_buf.end(), geom_buf.begin(), geom_buf.end());

                std::string color = "#1B5E20";
                if (speed_ratio < 0.5) color = "#B71C1C"; else if (speed_ratio < 0.9) color = "#E65100";
                write_tag(feat_buf, 2, 2); std::vector<uint8_t> tag_buf;
                write_varint(tag_buf, 0); write_varint(tag_buf, get_val_idx(color));
                write_varint(feat_buf, tag_buf.size()); feat_buf.insert(feat_buf.end(), tag_buf.begin(), tag_buf.end());
                write_tag(layer_buf, 2, 2); write_varint(layer_buf, feat_buf.size());
                layer_buf.insert(layer_buf.end(), feat_buf.begin(), feat_buf.end());
            }
        }
    }
    for (const auto& k : keys) write_string(layer_buf, 3, k);
    for (const auto& v : values) {
        write_tag(layer_buf, 4, 2); std::vector<uint8_t> v_buf; write_string(v_buf, 1, v);
        write_varint(layer_buf, v_buf.size()); layer_buf.insert(layer_buf.end(), v_buf.begin(), v_buf.end());
    }
    std::vector<uint8_t> tile_buf;
    write_tag(tile_buf, 3, 2); write_varint(tile_buf, layer_buf.size());
    tile_buf.insert(tile_buf.end(), layer_buf.begin(), layer_buf.end());
    std::vector<uint8_t> compressed = compress_gzip(tile_buf);

    // If gzip somehow failed, we MUST NOT return the raw tile because the
    // HTTP wrapper in Kotlin unconditionally tags the body
    // `Content-Encoding: gzip`. MapLibre would then try to gunzip plain
    // protobuf and fail to render. Returning null here makes the server
    // respond with 204 (no tile) and MapLibre falls back to no traffic
    // overlay for that cell instead of breaking the render.
    if (compressed.empty()) {
        return nullptr;
    }
    jbyteArray res = env->NewByteArray(compressed.size());
    env->SetByteArrayRegion(res, 0, compressed.size(), (jbyte*)compressed.data());
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_vayunmathur_maps_util_OfflineRouter_ensureTrafficLoadedNative(JNIEnv* env, jobject thiz, jdouble lat, jdouble lon, jboolean force_async) {
    ensure_traffic_loaded(env, thiz, (int32_t)(lat * 1e7), (int32_t)(lon * 1e7), force_async);
}
