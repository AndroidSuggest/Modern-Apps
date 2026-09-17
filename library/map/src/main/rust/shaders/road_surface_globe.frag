#version 450
// Globe ribbon fragment: `road_surface.frag` plus the limb discard.
//
// `road_surface.frag` is shared verbatim for the markings math — this file is
// a copy with one varying added (`inZ`, the unit-sphere z) and the two early
// discards. Kept as a separate file rather than a `#ifdef` so the flat
// pipeline compiles bit-identical shaders whether or not the globe exists.
layout(location = 0) in float inT;
layout(location = 1) in float inDistancePx;
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
const float NO_MARKINGS_BELOW = -1.5;
const float MARK_WIDTH_LANES = 0.045;
const float MIN_MARK_HALF_PX = 0.5;
const float MARK_FADE_START_PX = 4.0;
const float MARK_FADE_FULL_PX = 10.0;
const float DASH_ON_LANES = 0.9;
const float DASH_OFF_LANES = 2.6;
const vec3 MARK_WHITE = vec3(0.92, 0.92, 0.90);
const vec3 MARK_YELLOW = vec3(0.95, 0.76, 0.18);

float band(float distancePx, float halfPx) {
    return clamp(halfPx - distancePx + 0.5, 0.0, 1.0);
}

void main() {
    if (inZ < 0.0) discard;
    float wantHalfPx = push.line.x;
    float halfWidthPx = max(wantHalfPx, MIN_HALF_WIDTH_PX);
    float coverage = clamp(wantHalfPx / halfWidthPx, 0.0, 1.0);
    float edgePx = inT * halfWidthPx;
    coverage *= mix(1.0, band(abs(edgePx), halfWidthPx), push.misc.y);
    coverage *= clamp(inZ * 8.0, 0.0, 1.0);
    if (coverage <= 0.0) discard;

    float lanes = max(push.line.y, 1.0);
    float lanePx = 2.0 * halfWidthPx / lanes;
    float legible =
        clamp((lanePx - MARK_FADE_START_PX) / (MARK_FADE_FULL_PX - MARK_FADE_START_PX), 0.0, 1.0);

    vec3 rgb = push.color.rgb;
    if (legible > 0.0 && push.line.z > NO_MARKINGS_BELOW) {
        float markHalfPx = max(lanePx * MARK_WIDTH_LANES * 0.5, MIN_MARK_HALF_PX);
        bool oneway = push.line.w >= 0.5;
        float centreT = push.line.z;

        float lane = (inT * 0.5 + 0.5) * lanes;
        float boundary = round(lane);
        float boundaryT = boundary / lanes * 2.0 - 1.0;
        bool interior = boundary > 0.5 && boundary < lanes - 0.5;
        bool isCentre = !oneway && abs(boundaryT - centreT) < 1.0 / lanes;

        float dashed = 0.0;
        if (interior && !isCentre) {
            float on = DASH_ON_LANES * lanePx;
            float period = on + DASH_OFF_LANES * lanePx;
            float along = mod(inDistancePx, period);
            dashed = clamp(min(along, on - along) + 0.5, 0.0, 1.0);
            dashed *= band(abs(lane - boundary) * lanePx, markHalfPx);
        }
        rgb = mix(rgb, MARK_WHITE, dashed * legible);

        float edgeCentrePx = max(halfWidthPx - markHalfPx, 0.0);
        float edgeLine = band(abs(abs(edgePx) - edgeCentrePx), markHalfPx);
        rgb = mix(rgb, MARK_WHITE, edgeLine * legible);

        if (!oneway) {
            float centreLine = band(abs(edgePx - centreT * halfWidthPx), markHalfPx);
            vec3 centreRgb = push.misc.z >= 0.5 ? MARK_YELLOW : MARK_WHITE;
            rgb = mix(rgb, centreRgb, centreLine * legible);
        }
    }

    outColor = vec4(rgb, push.color.a * coverage);
}
