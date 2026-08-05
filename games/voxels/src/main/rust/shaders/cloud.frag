#version 450
// Volumetric clouds as a real depth-tested layer at a fixed world-space altitude slab
// [CLOUD_BOT, CLOUD_TOP]. Raymarched from the camera through the slab for ANY view direction, so you
// can stand under them, fly up into them, or look down on them from above. Per-fragment gl_FragDepth
// (entry point of the slab) lets terrain occlude clouds and clouds occlude terrain behind them.
layout(location=0) in vec2 vNdc;

layout(binding=0) uniform Ubo {
    mat4 viewProj;
    vec3 sunDir;
    float time;
    vec3 fogColor;
    float fogDensity;
    vec4 playerPos;
    float dayFactor;
    // Precipitation intensity, 0..1. Occupies what the other shaders leave as implicit std140 padding
    // between dayFactor and the mat4 that follows, so the block layout is identical either way.
    float rain;
    float _padA;
    float _padB;
    mat4 invViewProj;
    vec3 sunColor;
    float cloudShadow;
    vec3 ambientColor;
    float _pad2;
};

layout(location=0) out vec4 outColor;

const float PI = 3.14159265;

// ---- Sky background colour (used as cloud ambient; matches sky.frag) -----------------------------
vec3 skyColor(vec3 d, vec3 s) {
    d = normalize(d);
    s = normalize(s);
    float mu = dot(d, s);
    float h = clamp(d.y, 0.0, 1.0);
    float sunUp = smoothstep(-0.10, 0.22, s.y);
    vec3 betaR = vec3(0.19, 0.45, 1.0);
    float phaseR = 0.75 * (1.0 + mu * mu);
    float viewAir = 1.0 / (h * 0.85 + 0.15);
    float sunAir = 1.0 / (max(s.y, 0.0) * 0.9 + 0.12);
    vec3 sunTransmit = exp(-betaR * sunAir * 0.35);
    vec3 day = betaR * phaseR * (0.10 + 0.30 * viewAir) * sunTransmit;
    day = mix(day, vec3(dot(day, vec3(0.33)) + 0.30), (1.0 - h) * 0.35);
    float low = 1.0 - sunUp;
    vec3 warm = vec3(1.0, 0.42, 0.18);
    float warmMix = low * (0.35 + 0.65 * max(mu, 0.0) * max(mu, 0.0)) * (1.0 - h * 0.6);
    day = mix(day, warm, clamp(warmMix, 0.0, 0.8));
    vec3 night = mix(vec3(0.015, 0.02, 0.05), vec3(0.03, 0.05, 0.10), h);
    float twi = smoothstep(-0.22, 0.02, s.y) * (1.0 - sunUp);
    night += vec3(0.18, 0.08, 0.10) * twi * (1.0 - h * 0.7);
    return max(mix(night, day, sunUp), vec3(0.0));
}

// ---- Noise ---------------------------------------------------------------------------------------
float hash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float vnoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i + vec3(0,0,0)), hash(i + vec3(1,0,0)), f.x),
                   mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
                   mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
}
float fbm2(vec3 p) { return 0.6 * vnoise(p) + 0.4 * vnoise(p * 2.03 + 1.7); }

// ---- Cloud volume --------------------------------------------------------------------------------
const float CLOUD_BOT = 120.0;
const float CLOUD_TOP = 210.0;
const float COVERAGE  = 0.47;
const float SIGMA_V   = 0.12;
const float SIGMA_L   = 0.02;

// Coverage is a parameter rather than a constant so rain can thicken the deck into a solid overcast.
float cloudDensity(vec3 p, float cov) {
    vec3 q = p * 0.0045 + vec3(time * 0.06, time * 0.04, time * 0.045);
    q += 0.7 * (fbm2(q * 0.55) - 0.5);
    float base = fbm2(q);
    float d = smoothstep(1.0 - cov, 1.0 - cov * 0.25, base);
    d -= 0.15 * fbm2(q * 3.0 + vec3(0.0, time * 0.2, 0.0) + 4.0);
    float hn = (p.y - CLOUD_BOT) / (CLOUD_TOP - CLOUD_BOT);
    float grad = smoothstep(0.0, 0.28, hn) * smoothstep(1.0, 0.5, hn);
    return clamp(d, 0.0, 1.0) * grad;
}
float cloudDensityLow(vec3 p, float cov) {
    vec3 q = p * 0.0045 + vec3(time * 0.06, time * 0.04, time * 0.045);
    float base = fbm2(q);
    float d = smoothstep(1.0 - cov, 1.0 - cov * 0.25, base);
    float hn = (p.y - CLOUD_BOT) / (CLOUD_TOP - CLOUD_BOT);
    float grad = smoothstep(0.0, 0.28, hn) * smoothstep(1.0, 0.5, hn);
    return clamp(d, 0.0, 1.0) * grad;
}

float hgPhase(float mu, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(max(1.0 + g2 - 2.0 * g * mu, 1e-3), 1.5));
}

void main() {
    vec4 np = invViewProj * vec4(vNdc, 0.0, 1.0);
    vec4 fp = invViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 rd = normalize(fp.xyz / fp.w - np.xyz / np.w);
    vec3 ro = playerPos.xyz;
    vec3 s = normalize(sunDir);

    // Intersect the ray with the fixed altitude slab for ANY direction (up, level, or down).
    float t0, t1;
    if (abs(rd.y) < 1e-4) {
        if (ro.y < CLOUD_BOT || ro.y > CLOUD_TOP) discard; // level ray, outside the slab -> no clouds
        t0 = 0.0; t1 = 4000.0;
    } else {
        float ta = (CLOUD_BOT - ro.y) / rd.y;
        float tb = (CLOUD_TOP - ro.y) / rd.y;
        t0 = max(min(ta, tb), 0.0);
        t1 = max(ta, tb);
        if (t1 <= 0.0) discard; // slab entirely behind the camera
    }
    t1 = min(t1, 6000.0);
    if (t1 <= t0) discard;

    // Depth of the slab entry point -> lets the depth test occlude clouds behind terrain and vice
    // versa. (Writing gl_FragDepth disables early-z, but the discards above skip the cheap cases.)
    vec3 entry = ro + rd * t0;
    vec4 clip = viewProj * vec4(entry, 1.0);
    gl_FragDepth = clamp(clip.z / clip.w, 0.0, 1.0);

    const int STEPS = 14;
    float stepLen = (t1 - t0) / float(STEPS);
    float t = t0 + stepLen * hash(rd * 91.7);
    float cov = clamp(COVERAGE + rain * 0.30, 0.0, 0.95);
    float sigmaV = SIGMA_V * (1.0 + rain * 1.8);
    vec3 sunCol = sunColor * (1.0 - rain * 0.55);
    vec3 skyBg = skyColor(rd, s);
    vec3 ambient = skyBg * 0.5 + ambientColor * 0.4;
    float mu = dot(rd, s);
    float phase = mix(hgPhase(mu, 0.70), hgPhase(mu, -0.20), 0.35);

    float transmittance = 1.0;
    vec3 scattered = vec3(0.0);
    for (int i = 0; i < STEPS; i++) {
        vec3 p = ro + rd * t;
        float dens = cloudDensity(p, cov);
        if (dens > 0.01) {
            float od = 0.0;
            float ls = 45.0;
            for (int j = 0; j < 2; j++) {
                od += cloudDensityLow(p + s * ls * (float(j) + 0.7), cov) * ls;
            }
            float lightT = exp(-od * SIGMA_L);
            float powder = 1.0 - exp(-dens * 2.0);
            vec3 lum = sunCol * lightT * (phase * 2.0 + 0.6) * powder + ambient;
            float att = exp(-dens * stepLen * sigmaV);
            scattered += transmittance * (1.0 - att) * lum;
            transmittance *= att;
            if (transmittance < 0.02) break;
        }
        t += stepLen;
    }

    float alpha = 1.0 - transmittance;
    if (alpha < 0.003) discard; // nothing hit along this ray
    outColor = vec4(scattered, alpha); // premultiplied: blend ONE, ONE_MINUS_SRC_ALPHA over the scene
}
