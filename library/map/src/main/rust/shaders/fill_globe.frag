#version 450

// Globe fill fragment: the layer colour with a limb discard and the LOD fade.
//
// `vZ` is the unit-sphere z from the vertex stage: negative (far side and the
// limb-straddling fragments past the edge) discards, so the disc reads as a
// ball rather than a filled square. Otherwise identical to `fill.frag`.

layout(location = 0) in float inZ;
layout(location = 0) out vec4 outColor;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

void main() {
    if (inZ < 0.0) discard;
    // Limb antialias: fade the last sliver so the edge is not a staircase.
    float edge = clamp(inZ * 8.0, 0.0, 1.0);
    outColor = vec4(push.color.rgb, push.color.a * push.morph.x * edge);
}
