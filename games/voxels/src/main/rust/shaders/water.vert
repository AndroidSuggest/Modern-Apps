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
    vec4 playerPos;   // .w = underwater flag
    float dayFactor;
};

layout(location=0) out vec3 fragWorldPos;
layout(location=1) out float fragDist;
layout(location=2) out vec3 fragNormal;

void main() {
    // Keep the surface geometrically flat (uniform sink below the block top) so greedy-mesh
    // T-junctions don't crack; the wave lives entirely in the shading normal (+ frag ripples).
    vec3 p = inPos;
    p.y -= 0.1;
    // Analytic wave normal from a virtual height field (shading only, no vertex displacement).
    float dhx = 0.6 * cos(inPos.x * 0.6 + time * 1.5) * 0.06 + 0.3 * cos((inPos.x + inPos.z) * 0.3 + time * 0.7) * 0.04;
    float dhz = 0.5 * cos(inPos.z * 0.5 + time * 1.1) * 0.06 + 0.3 * cos((inPos.x + inPos.z) * 0.3 + time * 0.7) * 0.04;
    fragNormal = normalize(vec3(-dhx, 1.0, -dhz));
    fragWorldPos = p;
    fragDist = length(p - playerPos.xyz);
    gl_Position = viewProj * vec4(p, 1.0);
}
