#version 450

// Globe symbol vertex: label quads bent onto the sphere at their anchor.
//
// Same inputs as `symbol_billboard.vert` (tile-local position + uv + ground
// anchor). The globe record path pushes the globe matrix (translation column =
// centre lon/lat, third row = tile ox/oy/span) with `misc`/`morph` keeping
// their flat meaning: `misc.x` is the tile's world-px span (Dp), `misc.yzw`
// the halo rgb exactly as on the flat path, `line` the text size/halo/sdf/
// billboard flag, and `morph` the globe half-viewport + radius (see above).
// This shader bends the anchor through the CPU-matching `globe_point` basis
// and hangs the glyph quad off it at its screen offset — upright labels on a
// curved planet, with halo intact. Curved (line) labels carry each vertex as
// sphere exactly as they lie flat on the plane. Screen y follows the CPU
// anchor convention (`halfH - y`, north up).
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec2 inAnchor;
layout(location = 3) in float inAnchorH;
layout(location = 0) out vec2 outUv;
layout(location = 1) out float outZ;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

const float PI = 3.141592653589793;

vec3 globeOf(float lon, float lat, float cLon, float cLat) {
    float latR = radians(lat);
    float dLon = radians(lon - cLon);
    float cLatR = radians(cLat);
    float x1 = cos(latR) * cos(dLon);
    float z1 = -cos(latR) * sin(dLon);
    float y1 = sin(latR);
    float sy = sin(cLatR);
    float cy = cos(cLatR);
    return vec3(x1, y1 * cy - z1 * sy, y1 * sy + z1 * cy);
}

void main() {
    outUv = inUv;
    float ox = push.tileToClip[2][0];
    float oy = push.tileToClip[2][1];
    float span = push.tileToClip[2][2];
    // Anchor tile-local -> lon/lat.
    float awx = ox + inAnchor.x * span;
    float awy = oy + inAnchor.y * span;
    float alon = awx * 360.0 - 180.0;
    float an = PI - 2.0 * PI * awy;
    float alat = atan((exp(an) - exp(-an)) * 0.5) * 180.0 / PI;
    // Sphere basis from the matrix translation column (CPU-side centre).
    float cLon = push.tileToClip[3][0];
    float cLat = push.tileToClip[3][1];
    vec3 a = globeOf(alon, alat, cLon, cLat);
    float r = push.morph.z;
    float kx = push.tileToClip[0][0] / max(r, 1e-6);
    float ky = push.tileToClip[1][1] / max(r, 1e-6);
    float hw = push.morph.x;
    float hh = push.morph.y;
    // Screen offset of this corner from its anchor: tile-local delta scaled by
    // the matrix's flat 2x2. The flat linear part maps tile units to clip; on
    // the globe branch that 2x2 is the globe scale (kx*r per tile-span unit),
    // so reuse it directly: off_clip = flat2x2 * delta_tile.
    float m00 = push.tileToClip[0][0];
    float m01 = push.tileToClip[0][1];
    float m10 = push.tileToClip[1][0];
    float m11 = push.tileToClip[1][1];
    // The 2x2 maps normalised-world delta (span-scaled tile delta) to clip:
    // delta_world = delta_tile * span, clip = 2x2 * delta_world. With the globe
    // matrix the 2x2 already includes r, so scale by span only.
    vec2 dTile = (inPosition - inAnchor) * span;
    vec2 off = vec2(m00 * dTile.x + m10 * dTile.y, m01 * dTile.x + m11 * dTile.y);
    float ax = kx * (a.x * r) + (kx * hw - 1.0);
    float ay = -(ky * (a.y * r)) + (ky * hh - 1.0);
    float depth = clamp(1.0 - a.z, 0.0, 1.0);
    outZ = a.z;
    if (a.z < 0.0) {
        gl_Position = vec4(ax, ay, 1.0, 1.0);
    } else {
        gl_Position = vec4(ax + off.x, ay - off.y, depth, 1.0);
    }
}
