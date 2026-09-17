#version 450

// Moon fragment: LROC color textured by selenographic lon/lat, shaded by the
// LDEM-derived normal with a fixed sun direction.
//
// `inDisc` (0..1 across the disc) maps to the visible hemisphere around the
// camera centre (`color.xy` = centre lon/lat degrees): the hemisphere is the
// set of selenographic points within 90° of the centre, so disc offset (dx, dy)
// is an orthographic projection of the unit-sphere east/north components and
// the lon/lat inverts through the same east/north/up basis the globe math uses
// (`globe_point`/`globe_lonlat`). The color texture is equirectangular
// -180..180/-90..90, sampled directly. The DEM texture (same layout, RG-packed
// uint16 half-metres + 20000) perturbs the normal for relief shading; large
// structure comes from the geometry-side displacement, fine grain from here.
//
// Lighting is a single fixed sun from the east-equatorial direction: enough to
// read relief, deliberately not a day/night terminator (no sun-position input
// exists on this path yet). Limb darkening fakes the spherical falloff the
// flat texture lacks.

layout(location = 0) in vec2 inDisc;
layout(location = 1) in float inZ;
layout(location = 2) in vec3 inNormal;
layout(location = 0) out vec4 outColor;

layout(push_constant) uniform Push {
    mat4 tileToClip;
    // xy: camera centre lon/lat (degrees) — the visible-hemisphere centre.
    vec4 color;
    vec4 line;
    // x: unused (radius implied by the quad); z: DEM relief strength.
    vec4 misc;
    vec4 morph;
} push;
layout(set = 0, binding = 0) uniform sampler2D moonColor;
layout(set = 1, binding = 1) uniform sampler2D moonDem;

const float PI = 3.141592653589793;

void main() {
    if (inZ < 0.0) discard;
    // Disc -> unit-sphere east/north (orthographic): disc spans -1..1.
    vec2 en = (inDisc * 2.0 - 1.0);
    en.y = -en.y; // disc y-down to north-up.
    float z = inZ;
    // Invert the east/north/up basis (mirrors `globe_lonlat`): recover ECEF.
    float cLatR = radians(push.color.y);
    float sy = sin(cLatR);
    float cy = cos(cLatR);
    float x = en.x;
    float y = en.y;
    // Unit-sphere point in basis: (x, y, z). ECEF: ex = x; ey = cy*y + sy*z; ez = -sy*y + cy*z.
    float ey = cy * y + sy * z;
    float ez = -sy * y + cy * z;
    float lat = asin(clamp(ey, -1.0, 1.0)) * 180.0 / PI;
    float lon = push.color.x + atan(x, ez) * 180.0 / PI;
    lon = mod(lon + 180.0, 360.0) - 180.0;
    // Equirectangular sample.
    vec2 uv = vec2((lon + 180.0) / 360.0, (90.0 - lat) / 180.0);
    vec3 albedo = texture(moonColor, uv).rgb;
    // DEM relief: RG-packed uint16, metres = (v - 20000) / 2. Central
    // differences for a gradient in texture space, scaled to a normal nudge.
    vec2 texel = 1.0 / vec2(textureSize(moonColor, 0));
    float hC = (texture(moonDem, uv).r * 255.0 * 256.0 + texture(moonDem, uv).g * 255.0 - 20000.0) / 2.0;
    float hX = (texture(moonDem, uv + vec2(texel.x, 0.0)).r * 255.0 * 256.0 + texture(moonDem, uv + vec2(texel.x, 0.0)).g * 255.0 - 20000.0) / 2.0;
    float hY = (texture(moonDem, uv + vec2(0.0, texel.y)).r * 255.0 * 256.0 + texture(moonDem, uv + vec2(0.0, texel.y)).g * 255.0 - 20000.0) / 2.0;
    float relief = push.misc.z;
    vec3 n = normalize(inNormal + vec3(-(hX - hC) * relief * texel.y / max(texel.x, 1e-9), (hY - hC) * relief, 0.0));
    // Fixed sun from the east, slightly above equatorial: reads relief without
    // needing a sun-position input on this path.
    vec3 sun = normalize(vec3(0.8, 0.25, 0.55));
    float diff = clamp(dot(n, sun), 0.0, 1.0);
    // Limb darkening: the texture has no spherical falloff baked in.
    float limb = clamp(z, 0.0, 1.0);
    float shade = (0.25 + 0.75 * diff) * (0.35 + 0.65 * limb);
    outColor = vec4(albedo * shade, 1.0);
}
