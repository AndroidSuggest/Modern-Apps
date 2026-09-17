#version 450

// Moon vertex: a full-screen disc quad bent onto the lunar sphere.
//
// The quad arrives in clip space already (the record path pushes an identity
// matrix); this shader interprets its local -1..1 as disc coordinates, keeps
// the inside of the unit disc, and bends it onto the orthographic sphere with
// LDEM relief: the DEM sample displaces the radius outward before projection,
// so craters and maria rims read as relief under the terminator shading in the
// fragment stage. Outside the disc is clipped away.
//
// Push layout (the stock `Push` block):
//   `tileToClip` is IDENTITY (unused; positions derive from `inLocal` + push).
//   `color` carries the camera centre lon/lat (degrees) in xy (the sphere basis)
//     — the Moon has no "camera target moves the texture": the texture is fixed
//     to selenographic lon/lat, and the camera centre only sets the basis.
//   `line` is unused (zeros).
//   `misc.xy` is the globe radius in Dp; `misc.z` the DEM relief scale
//     (Dp per unit-packed-height); `misc.w` the frame clock (unused here).
//   `morph.xy` is the half-viewport in Dp; `morph.zw` unused.
layout(location = 0) in vec2 inLocal;
layout(location = 0) out vec2 outDisc;
layout(location = 1) out float outZ;
layout(location = 2) out vec3 outNormal;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

void main() {
    // Disc coords: -1..1, y down (Vulkan clip). Radius 1 is the limb.
    float r2 = dot(inLocal, inLocal);
    outDisc = inLocal * 0.5 + 0.5;
    if (r2 > 1.0) {
        // Outside the disc: clip away.
        gl_Position = vec4(2.0, 2.0, 1.0, 1.0);
        outZ = -1.0;
        outNormal = vec3(0.0, 0.0, -1.0);
        return;
    }
    float z = sqrt(max(0.0, 1.0 - r2));
    outZ = z;
    // Sphere normal in view space (viewer looks down -z at the +z hemisphere;
    // Vulkan y-down, so flip y to keep normals right-handed with the texture).
    outNormal = normalize(vec3(inLocal.x, -inLocal.y, z));
    gl_Position = vec4(inLocal, 1.0 - z * 0.5, 1.0);
}
