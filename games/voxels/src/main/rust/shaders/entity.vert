#version 450
layout(location=0) in vec3 inPos;
layout(location=1) in vec2 inUv;
layout(location=2) in vec3 inColor;
layout(location=3) in float inAo;
layout(location=4) in float inTileIdx;
layout(location=5) in vec3 inNormal;

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

layout(location=0) out vec2 fragUv;
layout(location=1) out vec3 fragColor;
layout(location=2) out vec3 fragWorldPos;
layout(location=3) out vec3 fragNormal;

void main() {
    gl_Position = viewProj * vec4(inPos, 1.0);
    fragUv = inUv;
    fragColor = inColor;
    fragWorldPos = inPos;
    fragNormal = inNormal;
}
