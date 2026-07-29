#version 450
layout(location=0) in vec2 fragUv;
layout(location=1) in vec3 fragColor;
layout(location=2) in vec3 fragWorldPos;
layout(location=3) in vec3 fragNormal;

layout(binding=0) uniform Ubo {
    mat4 viewProj;
    vec3 sunDir;
    float time;
    vec3 fogColor;
    float fogDensity;
    vec4 playerPos;
    float dayFactor;
    mat4 invViewProj;
    vec3 sunColor;
    float cloudShadow;
    vec3 ambientColor;
    float _pad2;
    mat4 lightViewProj;
};
layout(binding=3) uniform sampler2D entityTex;

layout(location=0) out vec4 outColor;

void main() {
    vec4 t = texture(entityTex, fragUv);
    if (t.a < 0.5) discard;                       // cut out transparent skin regions
    vec3 n = normalize(fragNormal);
    vec3 s = normalize(sunDir);
    float ndl = max(dot(n, s), 0.0);
    vec3 amb = ambientColor * (0.55 + 0.45 * (0.5 + 0.5 * n.y));
    vec3 lit = t.rgb * fragColor * (sunColor * ndl + amb);
    outColor = vec4(lit, 1.0);                     // linear HDR; tonemapped in composite
}
