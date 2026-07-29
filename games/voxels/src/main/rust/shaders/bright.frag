#version 450
// Bright-pass: keep only HDR values above a threshold (feeds the bloom blur). Runs at half res, so the
// bilinear sample also downsamples.
layout(location=0) in vec2 vUv;
layout(binding=0) uniform sampler2D src;
layout(binding=1) uniform sampler2D unused;
layout(location=0) out vec4 outColor;
void main() {
    vec3 c = texture(src, vUv).rgb;
    float b = max(max(c.r, c.g), c.b);
    const float threshold = 1.05;
    float k = clamp((b - threshold) / max(b, 1e-4), 0.0, 1.0);
    outColor = vec4(c * k, 1.0);
}
