#version 150

/**
 * Deferred flashlight fragment shader with shadow map support.
 *
 * Reconstructs world position and surface normal from the player's depth buffer, then applies
 * a cone spotlight with distance attenuation and shadow map occlusion testing.
 *
 * Surface albedo is recovered from the already-rendered scene color buffer via chromaticity
 * extraction: dividing the scene color by its peak channel normalizes out the vanilla lighting
 * intensity while preserving the surface hue. In very dark areas where the signal is unreliable,
 * a confidence weight blends toward neutral gray (average Minecraft block reflectance).
 *
 * Lighting uses a wrapped-diffuse model with Blinn-Phong specular and Fresnel rim for a natural
 * look that avoids the flat, plastic appearance of a pure Lambertian + ambient model.
 * The cone has a hotspot/spill pattern that simulates a real flashlight reflector.
 *
 * The shadow map is rendered from the light's point of view by [ShadowMapRenderer].
 * Shadow edges are softened with 3x3 PCF, and a slope-based bias prevents acne.
 */

out vec4 fragColor;

uniform sampler2D DepthSampler;
uniform sampler2D ShadowMap;
uniform sampler2D SceneColorSampler;
uniform mat4 InvViewProjMat;
uniform mat4 u_lightViewProj;
uniform vec3 u_lightPosition;
uniform vec3 u_lightDirection;
uniform vec3 u_lightColor;
uniform float u_cosHalfAngle;
uniform float u_range;
uniform float u_intensity;
uniform vec2 u_screenSize;

vec3 worldPosFromDepth(vec2 uv, float depth) {
    vec4 clipPos = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPos = InvViewProjMat * clipPos;
    return worldPos.xyz / worldPos.w;
}

vec3 reconstructWorldPos(vec2 uv) {
    float depth = texture(DepthSampler, uv).r;
    return worldPosFromDepth(uv, depth);
}

vec3 reconstructNormal(vec2 uv) {
    vec2 texel = 1.0 / u_screenSize;
    float centerDepth = texture(DepthSampler, uv).r;

    float zL = texture(DepthSampler, uv - vec2(2.0 * texel.x, 0.0)).r;
    float xL = texture(DepthSampler, uv - vec2(texel.x, 0.0)).r;
    float yR = texture(DepthSampler, uv + vec2(texel.x, 0.0)).r;
    float wR = texture(DepthSampler, uv + vec2(2.0 * texel.x, 0.0)).r;

    vec2 he = abs(vec2(xL, yR) * vec2(wR, zL) / (2.0 * vec2(wR, zL) - vec2(xL, yR)) - centerDepth);
    vec3 hDeriv = (he.x > he.y)
        ? reconstructWorldPos(uv) - reconstructWorldPos(uv - vec2(texel.x, 0.0))
        : reconstructWorldPos(uv + vec2(texel.x, 0.0)) - reconstructWorldPos(uv);

    float zD = texture(DepthSampler, uv - vec2(0.0, 2.0 * texel.y)).r;
    float xD = texture(DepthSampler, uv - vec2(0.0, texel.y)).r;
    float yU = texture(DepthSampler, uv + vec2(0.0, texel.y)).r;
    float wU = texture(DepthSampler, uv + vec2(0.0, 2.0 * texel.y)).r;

    vec2 ve = abs(vec2(xD, yU) * vec2(wU, zD) / (2.0 * vec2(wU, zD) - vec2(xD, yU)) - centerDepth);
    vec3 vDeriv = (ve.x > ve.y)
        ? reconstructWorldPos(uv) - reconstructWorldPos(uv - vec2(0.0, texel.y))
        : reconstructWorldPos(uv + vec2(0.0, texel.y)) - reconstructWorldPos(uv);

    return normalize(cross(hDeriv, vDeriv));
}

float coneAttenuation(vec3 toLight) {
    vec3 dir = normalize(u_lightDirection);
    float cosTheta = dot(-normalize(toLight), dir);
    float edge = u_cosHalfAngle;
    float spill = smoothstep(edge - 0.05, edge + 0.05, cosTheta);
    float hotspot = smoothstep(edge, 1.0, cosTheta);
    return spill * (0.3 + 0.7 * hotspot);
}

float distanceAttenuation(float dist) {
    float r = u_range;
    float nearRolloff = smoothstep(0.0, 2.0, dist);
    float falloff = 1.0 / (1.0 + dist * dist * 0.02);
    float linearAtten = clamp(1.0 - (dist / r), 0.0, 1.0);
    return nearRolloff * falloff * linearAtten;
}

float shadowMapLookup(vec3 worldPos, vec3 normal) {
    vec4 lightClipPos = u_lightViewProj * vec4(worldPos, 1.0);
    vec3 lightNDC = lightClipPos.xyz / lightClipPos.w;

    if (lightNDC.x < -1.0 || lightNDC.x > 1.0 || lightNDC.y < -1.0 || lightNDC.y > 1.0 || lightNDC.z > 1.0) {
        return 1.0;
    }

    vec2 shadowUV = lightNDC.xy * 0.5 + 0.5;
    float pixelDepth = lightNDC.z * 0.5 + 0.5;

    vec3 lightDir = normalize(u_lightPosition - worldPos);
    float slope = clamp(1.0 - dot(normal, lightDir), 0.0, 1.0);
    float bias = 0.001 + 0.01 * slope;

    vec2 texelSize = vec2(1.0 / 1024.0);
    float pcfRadius = 4.0;
    float shadow = 0.0;

    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 offset = vec2(float(x), float(y)) * pcfRadius * texelSize;
            vec2 sampleUV = clamp(shadowUV + offset, vec2(0.0), vec2(1.0));
            float shadowDepth = texture(ShadowMap, sampleUV).r;
            shadow += (shadowDepth < pixelDepth - bias) ? 0.0 : 1.0;
        }
    }

    return shadow / 9.0;
}

void main() {
    vec2 uv = gl_FragCoord.xy / u_screenSize;
    float depth = texture(DepthSampler, uv).r;

    vec3 worldPos = worldPosFromDepth(uv, depth);
    vec3 normal = reconstructNormal(uv);

    vec3 toLight = u_lightPosition - worldPos;
    float dist = length(toLight);
    vec3 lightDir = toLight / max(dist, 0.001);

    float cone = coneAttenuation(toLight);
    float distAtten = distanceAttenuation(dist);
    float shadow = shadowMapLookup(worldPos, normal);

    vec4 nearClip = vec4(uv * 2.0 - 1.0, -1.0, 1.0);
    vec4 nearWorld = InvViewProjMat * nearClip;
    vec3 viewDir = normalize(nearWorld.xyz / nearWorld.w - worldPos);
    vec3 halfVec = normalize(lightDir + viewDir);

    float wrap = dot(normal, lightDir) * 0.5 + 0.5;
    float diffuse = wrap * wrap;

    float specAngle = max(dot(normal, halfVec), 0.0);
    float specular = pow(specAngle, 16.0) * 0.12;

    float fresnel = pow(1.0 - max(dot(normal, viewDir), 0.0), 3.0);
    float rim = fresnel * max(dot(normal, lightDir), 0.0) * 0.15;

    float light = (diffuse + specular + rim) * cone * distAtten * shadow;

    vec3 sceneColor = texture(SceneColorSampler, uv).rgb;
    float peak = max(max(sceneColor.r, sceneColor.g), sceneColor.b);
    float confidence = smoothstep(0.0, 0.04, peak);
    vec3 albedo = mix(vec3(0.5), sceneColor / max(peak, 0.001), confidence);

    fragColor = vec4(u_lightColor * light * albedo * u_intensity, 1.0);
}
