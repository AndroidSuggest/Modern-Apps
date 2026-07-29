#version 450
// Depth-only pass: rasterize terrain from the sun's point of view into the shadow map.
layout(location=0) in vec3 inPos;

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

void main() {
    gl_Position = lightViewProj * vec4(inPos, 1.0);
}
