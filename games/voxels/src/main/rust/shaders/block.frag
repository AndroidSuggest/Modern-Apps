#version 450
layout(location=0) in vec2 fragUv;
layout(location=1) in vec3 fragColor;
layout(location=2) in float fragAo;
layout(location=3) in float fragDist;
layout(location=4) in float fragLight;
layout(location=5) in float fragTile;

layout(binding=0) uniform Ubo {
    mat4 viewProj;
    vec3 sunDir;
    float time;
    vec3 fogColor;
    float fogDensity;
    vec4 playerPos;
    float dayFactor;
};

layout(binding=1) uniform sampler2D atlasTex;

layout(location=0) out vec4 outColor;

void main() {
    vec4 texColor = texture(atlasTex, fragUv);
    if (texColor.a < 0.05) discard;
    float aoFactor = 0.65 + 0.35 * fragAo;
    vec3 base = texColor.rgb * fragColor * fragLight * aoFactor;
    float fog = 1.0 - exp(-fragDist * fogDensity);
    fog = clamp(fog, 0.0, 0.85);
    vec3 dayTint = mix(vec3(0.05,0.05,0.15), fogColor, dayFactor);
    vec3 withFog = mix(base, dayTint, fog);
    withFog *= mix(0.42, 1.0, dayFactor);
    outColor = vec4(withFog, texColor.a);
}
