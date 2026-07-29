#version 450
// Fullscreen triangle with 0..1 uv (uv.y=0 at the top, matching Vulkan framebuffer/texture origin).
layout(location=0) out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
