#version 450

// Terrain fragment: the ground colour, slope-shaded per fragment (WS-G, 3D terrain relief).
//
// The base colour is the `earth` style layer's, pushed per draw (linear RGBA, palette- and
// opacity-resolved by the renderer). The vertex stage forwards the surface normal and the
// tile-local position; lighting runs here against the same fixed north-west light as
// building.frag, so ridgelines shade sharply instead of interpolating a per-vertex scalar —
// and relief reads at pitch 0 too, where the old path forced vShade = 1.0.
//
// Over the Lambert term sits a Blinn-Phong specular: the half-vector between the view direction
// (fragment to the pushed tile-local eye in `misc.xyz`) and the light, raised to a shininess of
// 48 and scaled by 0.20. Tilted views gain a soft sun glint off facing slopes; at pitch 0 the
// specular is skipped outright (the ortho fast-path test, same as the vertex stage's), so the
// overhead map is byte-identical to the Lambert-only output. The total is clamped at 1.0 so the
// highlight tints rather than blowing out to white. Blending is the pipeline's job, so the
// colour passes through with its own alpha.

layout(location = 0) in vec3 vNormal;
layout(location = 1) in vec3 vTilePos;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color; // the earth layer's colour, linear RGBA
    vec4 line;
    // xyz: the tile-local eye position (u, v in 0..1, height in tile-norm units), pushed per
    // terrain draw; w: the per-frame clock, unread on this path.
    vec4 misc;
    vec4 morph;
} push;

layout(location = 0) out vec4 outColor;

void main() {
    // A fixed light from above and the north-west, in tile-local space (x east, y south, z up),
    // so slopes facing it read brighter and the far sides of hills sit a shade darker.
    vec3 n = normalize(vNormal);
    vec3 lightDir = normalize(vec3(-0.4, -0.4, 1.0));
    float diffuse = max(dot(n, lightDir), 0.0);
    // Generous ambient so a shadowed slope stays legible rather than going black.
    float light = 0.55 + 0.45 * diffuse;
    // Tilted only: the ortho fast-path test (the height column's w term is 0 there). At pitch
    // 0 the eye sits far above the tile and the highlight would be a flat lift, so skip it and
    // keep the overhead map exactly the Lambert output.
    if (abs(push.tileToClip[2][3]) >= 1e-6) {
        vec3 viewDir = normalize(push.misc.xyz - vTilePos);
        vec3 h = normalize(viewDir + lightDir);
        float spec = pow(max(dot(n, h), 0.0), 48.0) * 0.20;
        light = min(light + spec, 1.0);
    }
    outColor = vec4(push.color.rgb * light, push.color.a);
}
