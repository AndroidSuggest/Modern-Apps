#version 450
// Fullscreen triangle; passes clip-space NDC to the fragment shader for ray reconstruction.
layout(location=0) out vec2 vNdc;
void main() {
    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    vec2 ndc = p * 2.0 - 1.0; // (-1,-1), (3,-1), (-1,3) covers the screen
    vNdc = ndc;
    // z = 1.0 (far): the sky is drawn after terrain with a LEQUAL depth test, so it only fills
    // pixels with no terrain (depth still at the cleared far value) — clouds skip occluded pixels.
    gl_Position = vec4(ndc, 1.0, 1.0);
}
