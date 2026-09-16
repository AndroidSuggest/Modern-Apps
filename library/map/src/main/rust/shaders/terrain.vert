#version 450

// Terrain: the DEM-displaced ground grid (WS-G, 3D terrain relief).
//
// Like the building vertex, a terrain vertex carries a height and a normal — but no per-vertex
// colour: the whole ground is one style colour (the `earth` layer's), pushed per draw, and the
// relief is conveyed by shading that colour against the normal. See `tess::terrain` for the layout.
//
// The incoming height is **tile-normalised** (a fraction of the tile's ground width, the same unit
// x/y are) so the grid is zoom-independent like every other tile mesh. `line.x` carries the tile's
// world-px span for this frame; multiplying the two recovers the real world-px height the WS0
// perspective matrix expects in its `z` input. At pitch 0 that matrix ignores z for x/y, so the
// grid reads as the flat tile footprint.
//
// Lighting runs per fragment: the normal goes out on `vNormal` and the raw tile-local position
// on `vTilePos`, so terrain.frag can shade the Lambert term and the Blinn-Phong specular against
// the tile-local eye `misc.xyz` pushes per draw — no per-vertex scalar, so ridgelines stay sharp
// however the grid is tessellated.

layout(location = 0) in vec3 inPosition; // tile-local (u, v, height); u,v in 0..1
layout(location = 1) in vec3 inNormal;   // surface normal in the same tile-local space

layout(push_constant) uniform Push {
    // Tile-local (u, v, height, 1) to clip space — the WS0 matrix. `height` is world-px.
    mat4 tileToClip;
    vec4 color;
    // x: the tile's world-px span this frame, the tile-norm-height -> world-px scale (WS-G).
    // y, z, w: unused by the terrain path.
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

// Placed just short of the far plane so, at pitch 0, the ground sits behind every building (which
// write depths in [0, 0.99]) rather than in front of them — see the pitch-0 branch below.
const float ORTHO_GROUND_DEPTH = 0.9995;

layout(location = 0) out vec3 vNormal;
layout(location = 1) out vec3 vTilePos;

void main() {
    float worldHeight = inPosition.z * push.line.x;
    // The height column's w term (tileToClip[2][3]) is 0 on the ortho fast-path (pitch 0) and
    // -cos(pitch) once tilted — the same test the building shader uses to tell the two apart.
    bool ortho = abs(push.tileToClip[2][3]) < 1e-6;

    // The normal in tile-local space, forwarded to the fragment stage for per-fragment Lambert
    // (mirroring building.frag). Shared by both branches so relief shades at pitch 0 too —
    // previously the ortho branch forced vShade = 1.0 and hills were invisible top-down.
    vNormal = inNormal;
    // The raw tile-local position, forwarded alongside the normal so the fragment stage can
    // build the view vector against the pushed tile-local eye for the specular term.
    vTilePos = inPosition;

    if (ortho) {
        // Pitch 0: the ortho matrix never lets height touch x/y, so the grid already reads as the
        // flat tile footprint. Feed a fixed near-far depth instead of the terrain height so the
        // ground never wins the depth test against the buildings drawn over it (they write small
        // depths near the front); the flat 2D layers are depth-off and paint over it regardless.
        gl_Position = push.tileToClip * vec4(inPosition.xy, 0.0, 1.0);
        gl_Position.z = ORTHO_GROUND_DEPTH * gl_Position.w;
    } else {
        // Tilted: the full perspective maps a real world-px height to both the projected rise and a
        // depth inside the near/far range, so hills rise, occlude what is behind them, and let
        // buildings on the far side of a ridge be hidden by it.
        gl_Position = push.tileToClip * vec4(inPosition.xy, worldHeight, 1.0);
    }
}
