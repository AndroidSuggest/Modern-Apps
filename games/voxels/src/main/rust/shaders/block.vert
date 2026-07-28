#version 450
layout(location=0) in vec3 inPos;
layout(location=1) in vec2 inUv;
layout(location=2) in vec3 inColor;
layout(location=3) in float inAo;
layout(location=4) in float inTileIdx;

layout(binding=0) uniform Ubo {
    mat4 viewProj;
    vec3 sunDir;
    float time;
    vec3 fogColor;
    float fogDensity;
    vec4 playerPos;
    float dayFactor;
};

layout(location=0) out vec2 fragUv;
layout(location=1) out vec3 fragColor;
layout(location=2) out float fragAo;
layout(location=3) out float fragDist;
layout(location=4) out float fragLight;
layout(location=5) out float fragTile;

void main() {
    gl_Position = viewProj * vec4(inPos, 1.0);
    fragUv = inUv;
    fragColor = inColor;
    fragAo = inAo;
    fragTile = inTileIdx;
    vec3 wp = inPos;
    fragDist = length(wp - playerPos.xyz);
    float NdotL = max(dot(normalize(vec3(0.2,1.0,0.3)), -sunDir), 0.0);
    fragLight = 0.55 + 0.45 * NdotL;
}
