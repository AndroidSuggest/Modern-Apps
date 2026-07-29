#version 450
layout(location=0) in vec3 fragWorldPos;
layout(location=1) in float fragDist;
layout(location=2) in vec3 fragNormal;

layout(binding=0) uniform Ubo {
    mat4 viewProj;
    vec3 sunDir;
    float time;
    vec3 fogColor;
    float fogDensity;
    vec4 playerPos;   // .w = underwater flag
    float dayFactor;
    mat4 invViewProj;
    vec3 sunColor;
    float cloudShadow;
    vec3 ambientColor;
    float _pad2;
};

layout(location=0) out vec4 outColor;

void main() {
    vec3 s = normalize(sunDir);
    vec3 viewDir = normalize(playerPos.xyz - fragWorldPos);
    vec3 wp = fragWorldPos;

    // Detailed animated surface normal: coarse wave normal + fine moving ripples for sparkle.
    vec3 n = normalize(fragNormal);
    n.x += 0.06 * sin(wp.x * 2.3 + time * 2.5) + 0.04 * sin(wp.z * 3.1 - time * 1.7);
    n.z += 0.06 * sin(wp.z * 2.1 + time * 2.2) + 0.04 * sin(wp.x * 2.9 + time * 1.3);
    n = normalize(n);

    // Fresnel: near-transparent looking straight down, mirror-like at grazing angles.
    float ndv = max(dot(n, viewDir), 0.0);
    float fres = mix(0.04, 1.0, pow(1.0 - ndv, 4.0));

    // Body colour: teal in the shallows, deep blue when looking straight down into it.
    vec3 shallow = vec3(0.06, 0.30, 0.36);
    vec3 deep = vec3(0.015, 0.08, 0.14);
    vec3 body = mix(shallow, deep, ndv);
    // Cheap sky reflection colour, brighter when the sun is up.
    vec3 skyRefl = mix(vec3(0.30, 0.45, 0.60), vec3(0.55, 0.72, 0.95), clamp(s.y, 0.0, 1.0)) + sunColor * 0.10;
    vec3 col = mix(body, skyRefl, fres);
    // Darken with the day/night cycle so water doesn't glow at night while the terrain is dark.
    // (Sun glint below uses sunColor, which is already ~0 at night, so it stays correct.)
    col *= mix(0.06, 1.0, clamp(dayFactor, 0.0, 1.0));

    // Sharp sun glint + a broad sheen.
    vec3 hlf = normalize(s + viewDir);
    float nh = max(dot(n, hlf), 0.0);
    col += sunColor * (pow(nh, 400.0) * 1.6 + pow(nh, 40.0) * 0.12);

    // More transparent looking down (see the floor), opaque/reflective at grazing angles.
    float alpha = mix(0.55, 0.95, fres);

    // Distance fog toward the horizon so the ocean fades into the sky instead of ending abruptly.
    float fog = clamp(1.0 - exp(-fragDist * fogDensity), 0.0, 0.9);
    col = mix(col, fogColor, fog);
    alpha = mix(alpha, 1.0, fog);

    if (playerPos.w > 0.5) { col = mix(col, vec3(0.05, 0.20, 0.32), 0.6); alpha = 0.85; }

    outColor = vec4(col, alpha); // linear HDR; tonemap in composite
}
