#version 450

// Fill: solid triangles from the earcut tessellator.
//
// Vertices carry position plus the draped ground height, in tile-local 0..1. The
// tile's placement and the camera both live in the transform, and the layer's
// colour is a constant, so a fill vertex is 12 bytes. `z` is 0.0 straight out of
// the tessellator and filled in by the drape pass where the tile carries a
// heightmap; at pitch 0 it is ignored for x/y.
//
// The incoming height is **tile-normalised** (metres over the tile's ground width, the
// same unit x/y are) so the mesh is zoom-independent. `morph.z` carries the tile's
// world-px span for this frame (Dp, matching what buildings/terrain push as `line.x`);
// multiplying the two recovers the real world-px height the perspective matrix expects
// in its `z` input — exactly as `building.vert`/`terrain.vert` scale their heights.
//
// Everything per-draw arrives in a **push constant** block rather than a uniform
// buffer. 112 bytes is inside the 128 the spec guarantees, and it means the
// renderer needs no descriptor sets, no descriptor pool, and no per-tile uniform
// buffers to keep in sync with the camera.

layout(location = 0) in vec3 inPosition;

layout(push_constant) uniform Push {
    // Tile-local (u, v, height, 1) to clip space. `height` (the vertex z) is a
    // world-px height once scaled — see below.
    mat4 tileToClip;
    vec4 color;
    // x: half stroke width in px, y: half the casing gap in px,
    // z: dash length, w: gap length (both in line widths).
    vec4 line;
    // x: the screen size of one tile in px, which is what converts a pixel width
    // into tile-local units. y: edge-AA flag. z: lane lateral offset px. w: the
    // per-frame clock in seconds (WS0), read by the animated line paths.
    vec4 misc;
    // x: per-tile opacity/morph factor (1.0 = fully present), reserved for WS-D's
    // LOD cross-fade. y: dash phase speed (line paths). z: the tile's world-px span
    // (Dp) for this frame, the tile-norm-height -> world-px scale. w reserved.
    vec4 morph;
} push;

void main() {
    float worldHeight = inPosition.z * push.morph.z;
    // The height column's w term (tileToClip[2][3]) is 0 on the ortho fast-path (pitch 0)
    // and -cos(pitch) once tilted — the same test the building shader uses. At pitch 0
    // feed 0.0 exactly as before, so the flat map is byte-identical with or without a DEM;
    // tilted, feed the world-px height so the layer drapes onto the relief.
    if (abs(push.tileToClip[2][3]) < 1e-6) {
        gl_Position = push.tileToClip * vec4(inPosition.xy, 0.0, 1.0);
    } else {
        gl_Position = push.tileToClip * vec4(inPosition.xy, worldHeight, 1.0);
    }
}
