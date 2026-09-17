#version 450

// Globe symbol vertex: label quads bent onto the sphere at their anchor.
//
// Same inputs as `symbol_billboard.vert` (tile-local position + uv + ground
// anchor). The globe record path sets the billboard flag off and pushes the
// globe matrix + centre + radius in the same slots the billboard path uses, so
// this shader bends the anchor through `globe_point` and hangs the glyph quad
// off it at its screen offset — upright labels on a curved planet. Curved
// (line) labels carry each vertex as its own anchor, so they drape along the
// road on the sphere exactly as they lie flat on the plane.
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
    return vec3(x1, y1 * sy - z1 * cy, y1 * cy + z1 * sy);
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
    float cLon = push.misc.x;
    float cLat = push.misc.y;
    vec3 a = globeOf(alon, alat, cLon, cLat);
    float r = push.morph.z;
    float kx = push.tileToClip[0][0] / max(r, 1e-6);
    float ky = push.tileToClip[1][1] / max(r, 1e-6);
    float hw = push.morph.x;
    float hh = push.morph.y;
    // Screen offset of this corner from its anchor: tile-local delta scaled by
    // the matrix's flat 2x2 (device px per tile unit is not pushed on this path;
    // reconstruct from the globe scale: tile Dp span = span * worldSize, and the
    // matrix maps Dp -> clip, so off = delta_tile * span * worldSize * k).
    float worldSize = 2.0 * r;
    vec2 off = (inPosition - inAnchor) * span * worldSize;
    float ax = kx * (a.x * r) + (kx * hw - 1.0);
    float ay = ky * (a.y * r) + (ky * hh - 1.0);
    float depth = clamp(1.0 - a.z, 0.0, 1.0);
    outZ = a.z;
    if (a.z < 0.0) {
        gl_Position = vec4(ax, ay, 1.0, 1.0);
    } else {
        gl_Position = vec4(ax + kx * off.x, ay + ky * off.y, depth, 1.0);
    }
}
