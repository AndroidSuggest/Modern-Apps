#version 450
layout(location=0) in vec2 fragUv;      // per-block tile counts (0..w, 0..h)
layout(location=1) in vec3 fragColor;
layout(location=2) in float fragAo;
layout(location=3) in float fragDist;
layout(location=4) in vec3 fragWorldPos;
layout(location=5) in vec3 fragNormal;
layout(location=6) in float fragTile;
layout(location=7) in vec4 fragLightPos;
layout(location=8) in float fragLight;   // packed skylight + blocklight*16

layout(binding=0) uniform Ubo {
    mat4 viewProj;
    vec3 sunDir;
    float time;
    vec3 fogColor;
    float fogDensity;
    vec4 playerPos;
    float dayFactor;
    mat4 invViewProj;
    vec3 sunColor;
    float cloudShadow;
    vec3 ambientColor;
    float _pad2;
    mat4 lightViewProj;
};

layout(binding=1) uniform sampler2D atlasTex;
layout(binding=2) uniform sampler2D shadowMap;

layout(location=0) out vec4 outColor;

// Atlas is 16 cols x 16 rows.
const vec2 TILE_SPAN = vec2(1.0/16.0, 1.0/16.0);
const float ATLAS_COLS = 16.0;

// --- Cloud coverage (matches sky.frag) for cloud shadows on the ground. ---
const float CLOUD_MID = 165.0;
const float COVERAGE  = 0.47;
float hash(vec3 p){ p=fract(p*0.3183099+0.1); p*=17.0; return fract(p.x*p.y*p.z*(p.x+p.y+p.z)); }
float vnoise(vec3 x){
    vec3 i=floor(x); vec3 f=fract(x); f=f*f*(3.0-2.0*f);
    return mix(mix(mix(hash(i+vec3(0,0,0)),hash(i+vec3(1,0,0)),f.x),
                   mix(hash(i+vec3(0,1,0)),hash(i+vec3(1,1,0)),f.x),f.y),
               mix(mix(hash(i+vec3(0,0,1)),hash(i+vec3(1,0,1)),f.x),
                   mix(hash(i+vec3(0,1,1)),hash(i+vec3(1,1,1)),f.x),f.y),f.z);
}
float fbm2(vec3 p){ return 0.6*vnoise(p) + 0.4*vnoise(p*2.03+1.7); }
float cloudShadowFactor(vec3 wp, vec3 s){
    if (s.y <= 0.05) return 1.0;
    float t = (CLOUD_MID - wp.y) / s.y;
    if (t <= 0.0) return 1.0;
    vec3 cp = wp + s * t;
    vec3 q = cp*0.0045 + vec3(time*0.06, time*0.04, time*0.045); // match sky.frag cloud animation
    q += 0.7*(fbm2(q*0.55) - 0.5);                                 // approx domain warp (match sky)
    float d = smoothstep(1.0-COVERAGE, 1.0-COVERAGE*0.25, fbm2(q));
    return 1.0 - clamp(d * cloudShadow, 0.0, 0.8);
}

// Sun shadow map: 1 = lit, 0 = fully shadowed. 3x3 PCF with slope-scaled bias.
const float SHADOW_TEXEL = 1.0 / 2048.0;
float sunShadow(vec4 lightPos, float ndotl) {
    vec3 lp = lightPos.xyz / lightPos.w;      // Vulkan clip: xy in [-1,1], z in [0,1]
    vec2 uv = lp.xy * 0.5 + 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0 || lp.z > 1.0 || lp.z < 0.0) return 1.0;
    float bias = max(0.0016 * (1.0 - ndotl), 0.0004);
    float cur = lp.z - bias;
    float sum = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float d = texture(shadowMap, uv + vec2(x, y) * SHADOW_TEXEL).r;
            sum += cur <= d ? 1.0 : 0.0;
        }
    }
    return sum / 9.0;
}

void main() {
    // Wrap the per-block uv into this face's atlas tile so greedy-merged faces tile (no stretch).
    vec2 fuv = fract(fragUv);
    float tile = fragTile;
    vec4 texColor;
    vec3 albedo;
    if (tile > 999.0) {
        // Grass side: dirt base + the tinted grass_block_side_overlay fringe, composited in one quad
        // (perfectly aligned with the top face, no z-fight offset).
        vec2 dirtUV = vec2(mod(1.0, ATLAS_COLS) * TILE_SPAN.x, floor(1.0 / ATLAS_COLS + 0.001) * TILE_SPAN.y) + fuv * TILE_SPAN;
        vec2 ovUV   = vec2(mod(16.0, ATLAS_COLS) * TILE_SPAN.x, floor(16.0 / ATLAS_COLS + 0.001) * TILE_SPAN.y) + fuv * TILE_SPAN;
        vec3 dirt = texture(atlasTex, dirtUV).rgb;
        vec4 ov = texture(atlasTex, ovUV);
        albedo = mix(dirt, ov.rgb * fragColor, ov.a);
        texColor = vec4(albedo, 1.0);
    } else {
        vec2 origin = vec2(mod(tile, ATLAS_COLS) * TILE_SPAN.x, floor(tile / ATLAS_COLS + 0.001) * TILE_SPAN.y);
        texColor = texture(atlasTex, origin + fuv * TILE_SPAN);
        if (texColor.a < 0.05) discard;
        albedo = texColor.rgb * fragColor;
    }

    vec3 n = normalize(fragNormal);      // true per-face normal (stable regardless of camera)
    vec3 s = normalize(sunDir);

    float NdotL = max(dot(n, s), 0.0);
    // Sun visibility = cloud shadow * shadow-map (terrain self/occluder shadows).
    float shadow = cloudShadowFactor(fragWorldPos, s) * sunShadow(fragLightPos, NdotL);
    // Hemisphere ambient: sky above is brighter than the ground bounce below.
    float hemi = 0.5 + 0.5 * n.y;
    vec3 ambient = ambientColor * mix(0.55, 1.0, hemi);
    float aoFactor = mix(0.5, 1.0, fragAo); // per-corner ambient occlusion

    // Dynamic lighting: sky exposure gates sun+ambient (so caves/overhangs go dark), block light is
    // additive emissive (torches/glowstone glow warm). Tiny floor keeps unlit areas barely visible.
    float sky = mod(fragLight, 16.0) / 15.0;
    float blockL = floor(fragLight / 16.0) / 15.0;
    vec3 blockLightCol = vec3(1.0, 0.82, 0.5) * (blockL * blockL);
    vec3 lit = albedo * ((sunColor * NdotL * shadow + ambient) * sky + blockLightCol + vec3(0.02)) * aoFactor;
    // Night Vision (_pad2): lift dark areas to a visible floor.
    if (_pad2 > 0.5) { lit = max(lit, albedo * 0.62 * aoFactor); }

    float fog = 1.0 - exp(-fragDist * fogDensity);
    fog = clamp(fog, 0.0, 0.85);
    vec3 col = mix(lit, fogColor, fog);

    // Underwater: tint deep blue and darken with distance.
    if (playerPos.w > 0.5) {
        float uw = clamp(1.0 - exp(-fragDist * 0.05), 0.0, 0.85);
        col = mix(col, vec3(0.05, 0.20, 0.32), uw) * 0.75;
    }

    outColor = vec4(col, texColor.a); // linear HDR; tonemap happens in the composite pass
}
