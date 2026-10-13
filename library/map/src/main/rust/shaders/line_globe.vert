#version 450
// Globe line: same screen-space extrusion as `line.vert`, then the globe bend.
//
// The offset math (casing bands, lateral shift, sub-pixel floor) is verbatim
// from `line.vert` — a width is a screen measurement either way. The difference
// is the final transform: instead of the flat tile matrix, the extruded
// tile-local point is bent onto the orthographic sphere by the same
// lon/lat -> unit-sphere -> clip derivation `fill_globe.vert` uses. Draped
// height (`inZ`) is ignored on the globe: relief at globe zoom is sub-pixel.
//
// Push layout: identical block to the flat path. `tileToClip` is the globe
// matrix (2x2 = globe scale, third row = tile ox/oy/span, translation column =
// centre lon/lat degrees), `misc.xyzw` keep their flat meaning (tile px,
// edge AA, lateral shift, clock), and `morph` keeps its flat meaning too
// (fade in x, dash phase speed in y, draped-z scale in z). The half-viewport
// (Dp) rides in `morph.xy` on the globe branch exactly as `record.rs` pushes
// it — note the shaders read the radius from `morph.z`, not from `.xy`.
// edge AA, lateral shift, clock), and `morph` keeps its flat meaning too
// (fade in x, draped-z scale in z). The half-viewport (Dp) rides in
// `morph.xy` on the globe branch exactly as `record.rs` pushes it.
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inNormal;
layout(location = 2) in vec2 inExtrude;
layout(location = 3) in float inDistance;
layout(location = 4) in float inZ;
layout(location = 0) out float outDistancePx;
layout(location = 1) out float outEdgePx;
layout(location = 2) out float outZ;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

const float MIN_HALF_WIDTH_PX = 0.5;
const float PI = 3.141592653589793;

void main() {
    float wantHalfPx = push.line.x;
    float gapHalfPx = push.line.y;
    float tilePx = max(push.misc.x, 1.0);
    float lateralPx = push.misc.z;
    float halfWidthPx = max(wantHalfPx, MIN_HALF_WIDTH_PX);
    float offsetPx = inExtrude.x * gapHalfPx + inExtrude.y * halfWidthPx + lateralPx;
    float centrePx = inExtrude.x * gapHalfPx + sign(inExtrude.x) * halfWidthPx + lateralPx;
    outEdgePx = offsetPx - centrePx;
    vec2 offsetTile = inNormal * (offsetPx / tilePx);
    vec2 bent = inPosition + offsetTile;

    float ox = push.tileToClip[2][0];
    float oy = push.tileToClip[2][1];
    float span = push.tileToClip[2][2];
    float wx = ox + bent.x * span;
    float wy = oy + bent.y * span;
    float lon = wx * 360.0 - 180.0;
    float n = PI - 2.0 * PI * wy;
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
    float r = push.morph.z;
    float kx = push.tileToClip[0][0] / max(r, 1e-6);
    float ky = push.tileToClip[1][1] / max(r, 1e-6);
    float hw = push.morph.x;
    float hh = push.morph.y;
    // Screen point y-up like the CPU anchor math (`halfH - y`): north renders up.
    float cx = kx * (x * r) + (kx * hw - 1.0);
    float cyy = -(ky * (y * r)) + (ky * hh - 1.0);
    float depth = clamp(1.0 - z, 0.0, 1.0);
    outZ = z;
    outDistancePx = inDistance * tilePx;
    if (z < 0.0) {
        gl_Position = vec4(cx, cyy, 1.0, 1.0);
    } else {
        gl_Position = vec4(cx, cyy, depth, 1.0);
    }
}
