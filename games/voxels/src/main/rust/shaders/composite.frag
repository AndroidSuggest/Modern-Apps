#version 450
// Composite: FXAA on the HDR image, add bloom, tonemap+gamma to the swapchain.
// pc.param.xy = texel size (1/width, 1/height), pc.param.z = bloom intensity.
layout(location=0) in vec2 vUv;
layout(binding=0) uniform sampler2D hdrTex;
layout(binding=1) uniform sampler2D bloomTex;
layout(push_constant) uniform PC { vec4 param; } pc;
layout(location=0) out vec4 outColor;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 texel = pc.param.xy;
    vec3 c  = texture(hdrTex, vUv).rgb;
    vec3 nw = texture(hdrTex, vUv + vec2(-1.0, -1.0) * texel).rgb;
    vec3 ne = texture(hdrTex, vUv + vec2( 1.0, -1.0) * texel).rgb;
    vec3 sw = texture(hdrTex, vUv + vec2(-1.0,  1.0) * texel).rgb;
    vec3 se = texture(hdrTex, vUv + vec2( 1.0,  1.0) * texel).rgb;
    float lM = luma(c), lNW = luma(nw), lNE = luma(ne), lSW = luma(sw), lSE = luma(se);
    float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
    float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));

    vec3 aa = c;
    if (lMax - lMin > 0.05 * lMax + 0.02) { // only on edges
        vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), ((lNW + lSW) - (lNE + lSE)));
        float reduce = max((lNW + lNE + lSW + lSE) * 0.25 * 0.125, 1.0 / 128.0);
        float rcp = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
        dir = clamp(dir * rcp, -8.0, 8.0) * texel;
        vec3 rA = 0.5 * (texture(hdrTex, vUv + dir * (1.0/3.0 - 0.5)).rgb + texture(hdrTex, vUv + dir * (2.0/3.0 - 0.5)).rgb);
        vec3 rB = rA * 0.5 + 0.25 * (texture(hdrTex, vUv + dir * -0.5).rgb + texture(hdrTex, vUv + dir * 0.5).rgb);
        float lB = luma(rB);
        aa = (lB < lMin || lB > lMax) ? rA : rB;
    }

    vec3 col = aa + texture(bloomTex, vUv).rgb * pc.param.z;
    col = col / (col + vec3(0.55)); // tonemap
    col = pow(col, vec3(0.85));     // gamma lift
    outColor = vec4(col, 1.0);
}
