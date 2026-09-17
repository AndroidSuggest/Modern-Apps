#version 450
// Globe line fragment: `line.frag` plus the limb discard.
//
// `inZ` is the unit-sphere z from the vertex stage; past the limb the road has
// bent to the far side and must not draw. Otherwise the edge coverage and dash
// pattern are exactly `line.frag`'s.
layout(location = 0) in float inDistancePx;
layout(location = 1) in float inEdgePx;
layout(location = 2) in float inZ;
layout(location = 0) out vec4 outColor;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

const float MIN_HALF_WIDTH_PX = 0.5;

void main() {
    if (inZ < 0.0) discard;
    float wantHalfPx = push.line.x;
    float halfWidthPx = max(wantHalfPx, MIN_HALF_WIDTH_PX);
    float coverage = clamp(wantHalfPx / halfWidthPx, 0.0, 1.0);
    float box = clamp(halfWidthPx - abs(inEdgePx) + 0.5, 0.0, 1.0);
    coverage *= mix(1.0, box, push.misc.y);
    // Limb fade, matching the fill: roads thin out at the edge, not to a wall.
    coverage *= clamp(inZ * 8.0, 0.0, 1.0);
    if (coverage <= 0.0) discard;

    float dashOn = push.line.z;
    float dashOff = push.line.w;
    if (dashOff > 0.0) {
        float width = max(halfWidthPx * 2.0, 1.0);
        float on = dashOn * width;
        float off = dashOff * width;
        float period = on + off;
        float phase = push.misc.w * push.morph.y;
        if (period > 0.0 && mod(inDistancePx - phase, period) > on) discard;
    }
    outColor = vec4(push.color.rgb, push.color.a * coverage);
}
