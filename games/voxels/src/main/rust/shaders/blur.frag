#version 450
// Separable 5-tap Gaussian blur. `pc.param.xy` = uv step along the blur axis.
layout(location=0) in vec2 vUv;
layout(binding=0) uniform sampler2D src;
layout(binding=1) uniform sampler2D unused;
layout(push_constant) uniform PC { vec4 param; } pc;
layout(location=0) out vec4 outColor;
void main() {
    vec2 d = pc.param.xy;
    vec3 s = texture(src, vUv).rgb * 0.227027;
    vec2 o1 = d * 1.3846153846;
    vec2 o2 = d * 3.2307692308;
    s += texture(src, vUv + o1).rgb * 0.3162162162;
    s += texture(src, vUv - o1).rgb * 0.3162162162;
    s += texture(src, vUv + o2).rgb * 0.0702702703;
    s += texture(src, vUv - o2).rgb * 0.0702702703;
    outColor = vec4(s, 1.0);
}
