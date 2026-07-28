#version 450
layout(location=0) in vec2 vNdc;

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

layout(location=0) out vec4 outColor;

const float PI = 3.14159265;

// ---- Sky (Rayleigh) ---------------------------------------------------------
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

    float glow = pow(max(mu, 0.0), 8.0) * 0.5 + pow(max(mu, 0.0), 350.0) * 4.0;
    vec3 sunTint = mix(vec3(1.0, 0.5, 0.2), vec3(1.0, 0.96, 0.9), sunUp);
    day += sunTint * glow;

    vec3 night = mix(vec3(0.015, 0.02, 0.05), vec3(0.03, 0.05, 0.10), h);
    float twi = smoothstep(-0.22, 0.02, s.y) * (1.0 - sunUp);
    night += vec3(0.18, 0.08, 0.10) * twi * (1.0 - h * 0.7);

    return max(mix(night, day, sunUp), vec3(0.0));
}

// ---- Noise (2-octave value noise fBm) ---------------------------------------
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

// ---- Volumetric clouds ------------------------------------------------------
const float CLOUD_BOT = 120.0;
const float CLOUD_TOP = 210.0;
const float COVERAGE  = 0.47; // 0..1
const float SIGMA_V   = 0.12; // view-ray extinction per density-length
const float SIGMA_L   = 0.02; // light-ray extinction per density-length

float cloudDensity(vec3 p) {
    vec3 q = p * 0.0045 + vec3(time * 0.010, 0.0, time * 0.006); // wind drift
    float base = fbm2(q);
    float d = smoothstep(1.0 - COVERAGE, 1.0, base);
    d -= 0.20 * fbm2(q * 3.1 + 4.0);          // erode edges
    float hn = (p.y - CLOUD_BOT) / (CLOUD_TOP - CLOUD_BOT);
    float grad = smoothstep(0.0, 0.18, hn) * smoothstep(1.0, 0.6, hn); // feather top/bottom
    return clamp(d, 0.0, 1.0) * grad;
}

float hgPhase(float mu, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(max(1.0 + g2 - 2.0 * g * mu, 1e-3), 1.5));
}

// rgb = in-scattered light, a = view-ray transmittance.
vec4 renderClouds(vec3 ro, vec3 rd, vec3 s, vec3 skyBg, float sunUp) {
    if (rd.y <= 0.02) return vec4(0.0, 0.0, 0.0, 1.0);
    float t0 = (CLOUD_BOT - ro.y) / rd.y;
    float t1 = (CLOUD_TOP - ro.y) / rd.y;
    if (t1 < t0) { float tmp = t0; t0 = t1; t1 = tmp; }
    t0 = max(t0, 0.0);
    t1 = min(t1, 7000.0);
    if (t1 <= t0) return vec4(0.0, 0.0, 0.0, 1.0);

    const int STEPS = 14;
    float stepLen = (t1 - t0) / float(STEPS);
    float t = t0 + stepLen * hash(rd * 91.7); // jitter to hide banding

    vec3 sunCol = sunColor;                            // shared with terrain lighting
    vec3 ambient = skyBg * 0.5 + ambientColor * 0.4;   // sky backdrop + shared ambient
    float mu = dot(normalize(rd), s);
    float phase = mix(hgPhase(mu, 0.70), hgPhase(mu, -0.20), 0.35); // forward glow + soft back lobe

    float transmittance = 1.0;
    vec3 scattered = vec3(0.0);

    for (int i = 0; i < STEPS; i++) {
        vec3 p = ro + rd * t;
        float dens = cloudDensity(p);
        if (dens > 0.01) {
            // Light march toward the sun for self-shadowing.
            float od = 0.0;
            float ls = 45.0;
            for (int j = 0; j < 2; j++) {
                od += cloudDensity(p + s * ls * (float(j) + 0.7)) * ls;
            }
            float lightT = exp(-od * SIGMA_L);
            float powder = 1.0 - exp(-dens * 2.0);
            // Direct sun (phase glow + multiscatter baseline) + sky ambient.
            vec3 lum = sunCol * lightT * (phase * 2.0 + 0.6) * powder + ambient;
            float att = exp(-dens * stepLen * SIGMA_V);
            scattered += transmittance * (1.0 - att) * lum;
            transmittance *= att;
            if (transmittance < 0.02) break;
        }
        t += stepLen;
    }
    return vec4(scattered, transmittance);
}

void main() {
    vec4 np = invViewProj * vec4(vNdc, 0.0, 1.0);
    vec4 fp = invViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 dir = normalize(fp.xyz / fp.w - np.xyz / np.w);
    vec3 s = normalize(sunDir);
    float sunUp = smoothstep(-0.10, 0.22, s.y);

    vec3 col = skyColor(dir, s);
    vec4 clouds = renderClouds(playerPos.xyz, dir, s, col, sunUp);
    col = col * clouds.a + clouds.rgb; // composite clouds over sky

    col = col / (col + vec3(0.55)); // gentle tonemap
    col = pow(col, vec3(0.85));
    outColor = vec4(col, 1.0);
}
