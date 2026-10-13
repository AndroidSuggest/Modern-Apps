#version 450

// Globe fill: bends flat tile-local triangles onto the orthographic sphere.
//
// Same inputs as `fill.vert` (position-only tile-local 0..1 in `inPosition.xy`),
// but the matrix is the globe matrix from `Camera::globe_tile_to_clip`, not the
// flat tile matrix: its linear 2x2 carries the globe radius (Dp), its third
// row carries the tile's normalised-world origin + span (ox, oy, span), and its
// translation column carries the camera centre lon/lat (degrees, m[12]/m[13])
// for the sphere basis. The vertex reconstructs its lon/lat from those,
// projects through the CPU-matching `globe_point` basis, and emits clip + a
// real depth (near-side nearer).
//
// `misc`/`morph` keep their flat meaning (tile px in `misc.x`, edge AA in
// `misc.y`, tile fade in `morph.x`, draped-z scale in `morph.z`; `morph.y`
// keeps the dash phase speed the line fragment reads) — the limb discard is
// the only fragment difference from `fill.frag`, so opacity, fade and coverage
// match the flat map exactly. Vertices on the far side (z < 0) are pulled
// are pulled behind the near plane so the triangle clips away; straddling
// triangles clip at the limb per-fragment via the varying. Depth is `1 - z`
// mapped into [0, 1): the sub-sphere centre is deepest, the limb is nearest —
// coarser ancestor tiles drawn first lose the depth test to finer descendants
// drawn over them, which is the same coarsest-first order the flat loop relies
// on. Screen y follows the CPU anchor convention (`halfH - y`, north up).

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
// Translation column (m[12]/m[13]): centre lon/lat in degrees.

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
    // atan(sinh(n)): wy is in [0,1] so n in [-PI, PI] and sinh is bounded.
    float lat = atan((exp(n) - exp(-n)) * 0.5) * 180.0 / PI;
    // Sphere basis from the matrix translation column (CPU-side centre).
    float cLon = push.tileToClip[3][0];
    float cLat = push.tileToClip[3][1];
    float latR = radians(lat);
    float dLon = radians(lon - cLon);
    float cLatR = radians(cLat);
    float x1 = cos(latR) * cos(dLon);
    float z1 = -cos(latR) * sin(dLon);
    float y1 = sin(latR);
    float sy = sin(cLatR);
    float cy = cos(cLatR);
    float x = x1;
    float y = y1 * cy - z1 * sy;
    float z = y1 * sy + z1 * cy;
    // Globe radius (Dp) from the matrix scale: m[0] = kx * r with kx = 2/width;
    // half-viewport (Dp) rides in `morph.xy` on the globe branch exactly as the
    // record path pushes it (see the globe branch in `record.rs`).
    float hw = push.morph.x;
    float hh = push.morph.y;
    float r = push.morph.z;
    float kx = push.tileToClip[0][0] / max(r, 1e-6);
    float ky = push.tileToClip[1][1] / max(r, 1e-6);
    // Screen point in Dp: centre + sphere offset, y-up like the CPU anchor math
    // (`halfH - y`), so north renders up.
    float sx = x * r;
    float sy2 = y * r;
    float cx = kx * sx + (kx * hw - 1.0);
    float cyy = -(ky * sy2) + (ky * hh - 1.0);
    // Depth: limb (z=0) -> 0, sub-camera point (z=1) -> ~1. Nearer (larger z)
    // wins LESS: depth = 1 - z mapped in.
    float depth = clamp(1.0 - z, 0.0, 1.0);
    outZ = z;
    if (z < 0.0) {
        // Far side: emit at far depth so the triangle clips away.
        gl_Position = vec4(cx, cyy, 1.0, 1.0);
    } else {
        gl_Position = vec4(cx, cyy, depth, 1.0);
    }
}
