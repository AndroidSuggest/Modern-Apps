#version 450

// Globe fill: bends flat tile-local triangles onto the orthographic sphere.
//
// Same inputs as `fill.vert` (position-only tile-local 0..1 in `inPosition.xy`),
// but the matrix is the globe matrix from `Camera::globe_tile_to_clip`, not the
// flat tile matrix: its linear 2x2 carries the globe radius (Dp), and its third
// row carries the tile's normalised-world origin + span (ox, oy, span). The
// vertex reconstructs its lon/lat from those, projects through `globe_point`
// (mirroring the CPU), and emits clip + a real depth (near-side nearer).
//
// Vertices on the far side (z < 0) are pulled behind the near plane (w forced
// negative-side) so the triangle clips away; straddling triangles clip at the
// limb per-fragment via the varying. Depth is `1 - z` mapped into [0, 1): the
// sub-sphere centre is deepest, the limb is nearest — coarser ancestor tiles
// drawn first lose the depth test to finer descendants drawn over them, which
// is the same coarsest-first order the flat loop relies on.

layout(location = 0) in vec3 inPosition;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

layout(location = 0) out float outZ;

const float PI = 3.141592653589793;

// `tileToClip[2]` third row: (ox, oy, span) packed by `globe_tile_to_clip`.
// Column-major: m[2] = row0-col2, m[6] = row1-col2, m[10] = row2-col2.

void main() {
    float ox = push.tileToClip[2][0];
    float oy = push.tileToClip[2][1];
    float span = push.tileToClip[2][2];
    // Tile-local (u, v) -> normalised world (0..1 across the planet).
    float wx = ox + inPosition.x * span;
    float wy = oy + inPosition.y * span;
    // Normalised world -> lon/lat (Web Mercator inverse, y down).
    float lon = wx * 360.0 - 180.0;
    float n = PI - 2.0 * PI * wy;
    // atan(sinh(n)): sinh can overflow for extreme wy; wy is in [0,1] so n in
    // [-PI, PI] and sinh is bounded — safe.
    float lat = atan((exp(n) - exp(-n)) * 0.5) * 180.0 / PI;
    // Centre-facing basis is baked CPU-side: the globe matrix's translation is
    // zero and its 2x2 is (kx*r, ky*r), so we reconstruct the unit-sphere point
    // relative to the centre here. The centre lon/lat ride in `misc.xy`
    // (degrees) — pushed per draw by the globe record path.
    float cLon = push.misc.x;
    float cLat = push.misc.y;
    float latR = radians(lat);
    float dLon = radians(lon - cLon);
    float cLatR = radians(cLat);
    float x1 = cos(latR) * cos(dLon);
    float z1 = -cos(latR) * sin(dLon);
    float y1 = sin(latR);
    float sy = sin(cLatR);
    float cy = cos(cLatR);
    float x = x1;
    float y = y1 * sy - z1 * cy;
    float z = y1 * cy + z1 * sy;
    // Globe radius in Dp is recovered from the matrix scale: m[0] = kx * r with
    // kx = 2/widthDp — but widthDp is not pushed. Instead the record path pushes
    // r (Dp) in `morph.z` (unused by the fill path otherwise).
    float r = push.morph.z;
    float sx = x * r;
    float sy2 = y * r;
    // Flat clip through the matrix 2x2 + viewport centre: clip = kx * (sx) - 0
    // with the matrix translation column zeroed, plus centre offset. The record
    // path bakes (kx, ky) into m[0]/m[5] and pushes half-viewport (Dp) in
    // `morph.xy`, so: clip = m * (sx, sy) + (kx*hw - 1, ky*hh - 1).
    float kx = push.tileToClip[0][0] / max(r, 1e-6);
    float ky = push.tileToClip[1][1] / max(r, 1e-6);
    float hw = push.morph.x;
    float hh = push.morph.y;
    float cx = kx * sx + (kx * hw - 1.0);
    float cyy = ky * sy2 + (ky * hh - 1.0);
    // Vulkan clip y is down-positive like Mercator, but NDC y-up convention in
    // the flat matrices negates through ky sign — keep the same sign here.
    // Depth: limb (z=0) -> 0, sub-camera point (z=1) -> ~1. Reversed so nearer
    // (larger z) wins LESS: depth = 1 - z mapped in.
    float depth = clamp(1.0 - z, 0.0, 1.0);
    outZ = z;
    if (z < 0.0) {
        // Far side: emit behind the camera so the triangle clips. w negative
        // flips; use a degenerate far-depth point instead.
        gl_Position = vec4(cx, cyy, 1.0, 1.0);
    } else {
        gl_Position = vec4(cx, cyy, depth, 1.0);
    }
}
