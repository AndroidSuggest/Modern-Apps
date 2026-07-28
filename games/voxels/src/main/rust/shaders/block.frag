#version 450
layout(location=0) in vec2 fragUv;      // per-block tile counts (0..w, 0..h)
layout(location=1) in vec3 fragColor;
layout(location=2) in float fragAo;
layout(location=3) in float fragDist;
layout(location=4) in vec3 fragWorldPos;
layout(location=5) in vec3 fragNormal;
layout(location=6) in float fragTile;

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
};

layout(binding=1) uniform sampler2D atlasTex;

layout(location=0) out vec4 outColor;

// Atlas is 4 cols x 8 rows.
const vec2 TILE_SPAN = vec2(1.0/4.0, 1.0/8.0);

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
    vec3 q = cp*0.0045 + vec3(time*0.010, 0.0, time*0.006);
    float d = smoothstep(1.0-COVERAGE, 1.0, fbm2(q));
    return 1.0 - clamp(d * cloudShadow, 0.0, 0.8);
}

void main() {
    // Wrap the per-block uv into this face's atlas tile so greedy-merged faces tile (no stretch).
    float tile = fragTile;
    vec2 origin = vec2(mod(tile, 4.0) * TILE_SPAN.x, floor(tile / 4.0 + 0.001) * TILE_SPAN.y);
    vec2 auv = origin + fract(fragUv) * TILE_SPAN;
    vec4 texColor = texture(atlasTex, auv);
    if (texColor.a < 0.05) discard;

    vec3 albedo = texColor.rgb * fragColor;
    vec3 n = normalize(fragNormal);      // true per-face normal (stable regardless of camera)
    vec3 s = normalize(sunDir);

    float NdotL = max(dot(n, s), 0.0);
    float shadow = cloudShadowFactor(fragWorldPos, s);
    // Hemisphere ambient: sky above is brighter than the ground bounce below.
    float hemi = 0.5 + 0.5 * n.y;
    vec3 ambient = ambientColor * mix(0.55, 1.0, hemi);
    float aoFactor = 0.7 + 0.3 * fragAo;

    vec3 lit = albedo * (sunColor * NdotL * shadow + ambient) * aoFactor;

    float fog = 1.0 - exp(-fragDist * fogDensity);
    fog = clamp(fog, 0.0, 0.85);
    vec3 col = mix(lit, fogColor, fog);

    col = col / (col + vec3(0.55));  // same tonemap as the sky pass
    col = pow(col, vec3(0.85));
    outColor = vec4(col, texColor.a);
}
