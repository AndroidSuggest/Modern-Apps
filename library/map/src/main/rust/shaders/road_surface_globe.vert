#version 450
// Globe ribbon: `road_surface.vert` extrusion, then the globe bend.
//
// Same contract as `line_globe.vert` (which see for the push layout): the
// carriageway width/offsets are screen measurements resolved in tile-local
// space exactly as the flat ribbon does, and only the final transform bends
// onto the sphere. Markings readouts (`outT`, `outDistancePx`) pass through
// untouched — `road_surface.frag` is shared verbatim.
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inNormal;
layout(location = 2) in float inT;
layout(location = 3) in float inDistance;
layout(location = 4) in float inZ;
layout(location = 0) out float outT;
layout(location = 1) out float outDistancePx;
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
    float tilePx = max(push.misc.x, 1.0);
    float halfWidthPx = max(wantHalfPx, MIN_HALF_WIDTH_PX);
    vec2 offsetTile = inNormal * (inT * halfWidthPx / tilePx);
    vec2 bent = inPosition + offsetTile;

    float ox = push.tileToClip[2][0];
    float oy = push.tileToClip[2][1];
    float span = push.tileToClip[2][2];
    float wx = ox + bent.x * span;
    float wy = oy + bent.y * span;
    float lon = wx * 360.0 - 180.0;
    float n = PI - 2.0 * PI * wy;
    float lat = atan((exp(n) - exp(-n)) * 0.5) * 180.0 / PI;
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
    float r = push.morph.z;
    float kx = push.tileToClip[0][0] / max(r, 1e-6);
    float ky = push.tileToClip[1][1] / max(r, 1e-6);
    float hw = push.morph.x;
    float hh = push.morph.y;
    float cx = kx * (x * r) + (kx * hw - 1.0);
    float cyy = ky * (y * r) + (ky * hh - 1.0);
    float depth = clamp(1.0 - z, 0.0, 1.0);
    outT = inT;
    outZ = z;
    outDistancePx = inDistance * tilePx;
    if (z < 0.0) {
        gl_Position = vec4(cx, cyy, 1.0, 1.0);
    } else {
        gl_Position = vec4(cx, cyy, depth, 1.0);
    }
}
