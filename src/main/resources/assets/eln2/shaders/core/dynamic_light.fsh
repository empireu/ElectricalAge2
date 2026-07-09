#version 150

/**
 * Deferred flashlight fragment shader with shadow map support.
 *
 * Reconstructs world position and surface normal from the player's depth buffer, then applies
 * a diffuse cone light with distance attenuation and shadow map occlusion testing.
 *
 * The shadow map is rendered from the light's point of view by [ShadowMapRenderer], capturing
 * depth of all solid blocks within range. This handles off-screen occluders that screen-space
 * raymarching cannot detect.
 *
 * Normal reconstruction uses the 5-tap perspective-correct method from atyuwen's article,
 * which extrapolates both candidate surfaces at depth discontinuities and picks the closer
 * extrapolation, avoiding the light-bleeding artifacts of the naive cross(ddx, ddy) method.
 */

out vec4 fragColor;

uniform sampler2D DepthSampler;
uniform sampler2D ShadowMap;
uniform mat4 InvViewProjMat;
uniform mat4 u_lightViewProj;
uniform vec3 u_lightPosition;
uniform vec3 u_lightDirection;
uniform vec3 u_lightColor;
uniform float u_cosHalfAngle;
uniform float u_range;
uniform float u_intensity;
uniform vec2 u_screenSize;

/**
 * Reconstructs world position from a screen UV and depth value.
 * Converts window-space depth [0,1] to NDC [-1,1], then multiplies by InvViewProjMat and perspective-divides.
 */
vec3 worldPosFromDepth(vec2 uv, float depth) {
    vec4 clipPos = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPos = InvViewProjMat * clipPos;
    return worldPos.xyz / worldPos.w;
}

vec3 reconstructWorldPos(vec2 uv) {
    float depth = texture(DepthSampler, uv).r;
    return worldPosFromDepth(uv, depth);
}

/**
 * Reconstructs the surface normal at [uv] using the 5-tap perspective-correct method.
 * Samples 5 depths per axis (| z | x | * | y | w |), extrapolates both candidate surfaces
 * to the center pixel, and picks the closer extrapolation to determine which surface the
 * center pixel belongs to. This avoids artifacts at depth discontinuities (silhouette edges).
 */
vec3 reconstructNormal(vec2 uv) {
    vec2 texel = 1.0 / u_screenSize;
    float centerDepth = texture(DepthSampler, uv).r;

    float zL = texture(DepthSampler, uv - vec2(2.0 * texel.x, 0.0)).r;
    float xL = texture(DepthSampler, uv - vec2(texel.x, 0.0)).r;
    float yR = texture(DepthSampler, uv + vec2(texel.x, 0.0)).r;
    float wR = texture(DepthSampler, uv + vec2(2.0 * texel.x, 0.0)).r;

    // Perspective-correct extrapolation test: which segment does the center belong to?
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

/**
 * Cone spotlight attenuation. toLight points from surface to light, so we negate it
 * to get the light-to-surface direction, then compare against the light's aim direction.
 */
float coneAttenuation(vec3 toLight) {
    vec3 dir = normalize(u_lightDirection);
    float cosTheta = dot(-normalize(toLight), dir);
    float edge = u_cosHalfAngle;
    return smoothstep(edge, edge + 0.05, cosTheta);
}

float distanceAttenuation(float dist) {
    float r = u_range;
    return clamp(1.0 - (dist / r), 0.0, 1.0);
}

/**
 * Shadow map lookup. Transforms [worldPos] into the light's clip space, projects to shadow map UV,
 * and compares the pixel's depth against the shadow map depth. If the shadow map has geometry closer
 * to the light than the pixel, the pixel is shadowed.
 */
float shadowMapLookup(vec3 worldPos) {
    vec4 lightClipPos = u_lightViewProj * vec4(worldPos, 1.0);
    vec3 lightNDC = lightClipPos.xyz / lightClipPos.w;

    // If the pixel is outside the light's frustum, it's not shadowed (and not lit by the cone either).
    if (lightNDC.x < -1.0 || lightNDC.x > 1.0 || lightNDC.y < -1.0 || lightNDC.y > 1.0 || lightNDC.z > 1.0) {
        return 1.0;
    }

    vec2 shadowUV = lightNDC.xy * 0.5 + 0.5;
    float shadowDepth = texture(ShadowMap, shadowUV).r;
    float pixelDepth = lightNDC.z * 0.5 + 0.5;

    float bias = 0.002;
    if (shadowDepth < pixelDepth - bias) {
        return 0.0;
    }

    return 1.0;
}

// DEBUG: cycle through debug modes by changing DEBUG_MODE
// 0 = original flashlight (normal operation)
// 1 = solid red (proves the shader executes at all)
// 2 = depth visualization (proves depth copy works)
// 3 = shadow map visualization (proves shadow map texture is valid)
// 4 = world position (proves InvViewProjMat works)
// 5 = normal visualization (proves normal reconstruction works)
// 6 = cone attenuation only
// 7 = shadow only
// 8 = show whether shadow pass ran (uses shadow map as a screen texture)
#define DEBUG_MODE 0

void main() {
    vec2 uv = gl_FragCoord.xy / u_screenSize;
    float depth = texture(DepthSampler, uv).r;
#if DEBUG_MODE == 1
    // Solid red on EVERY pixel (no discard): if black, shader isn't executing or framebuffer is wrong.
    // If red, the discard was filtering everything (depth copy broken).
    fragColor = vec4(1.0, 0.0, 0.0, 1.0);
    return;
#endif
#if DEBUG_MODE == 2
    // Depth: proves the depth copy texture is valid.
    if (depth >= 1.0) discard;
    fragColor = vec4(vec3(depth), 1.0);
    return;
#endif

#if DEBUG_MODE == 3
    // Shadow map: sample shadow map at screen UV, proves the shadow texture is valid.
    if (depth >= 1.0) discard;
    float shadowDepth = texture(ShadowMap, uv).r;
    fragColor = vec4(vec3(shadowDepth), 1.0);
    return;
#endif

#if DEBUG_MODE == 4
    // World position as color: proves InvViewProjMat reconstruction works.
    if (depth >= 1.0) discard;
    vec3 worldPos = worldPosFromDepth(uv, depth);
    fragColor = vec4(fract(worldPos * 0.1), 1.0);
    return;
#endif

#if DEBUG_MODE == 5
    // Normal as color: proves normal reconstruction works.
    if (depth >= 1.0) discard;
    vec3 normal = reconstructNormal(uv);
    fragColor = vec4(normal * 0.5 + 0.5, 1.0);
    return;
#endif

#if DEBUG_MODE == 6
    // Cone attenuation only.
    if (depth >= 1.0) discard;
    vec3 worldPos = worldPosFromDepth(uv, depth);
    vec3 toLight = u_lightPosition - worldPos;
    float cone = coneAttenuation(toLight);
    fragColor = vec4(vec3(cone), 1.0);
    return;
#endif

#if DEBUG_MODE == 7
    // Shadow only.
    if (depth >= 1.0) discard;
    vec3 worldPos = worldPosFromDepth(uv, depth);
    float shadow = shadowMapLookup(worldPos);
    fragColor = vec4(vec3(shadow), 1.0);
    return;
#endif

#if DEBUG_MODE == 8
    // Shadow map as screen texture: maps the shadow map directly onto the screen.
    // If this shows the shadow map content, the texture is valid. If black, the texture is broken.
    if (depth >= 1.0) discard;
    float shadowDepth = texture(ShadowMap, uv).r;
    if (shadowDepth >= 1.0) {
        fragColor = vec4(0.0, 0.0, 1.0, 1.0); // blue = empty (far)
    } else {
        fragColor = vec4(1.0 - shadowDepth, 0.0, 0.0, 1.0); // red gradient = depth
    }
    return;
#endif

#if DEBUG_MODE == 0
    // Original flashlight.
    vec3 worldPos = worldPosFromDepth(uv, depth);
    vec3 normal = reconstructNormal(uv);

    vec3 toLight = u_lightPosition - worldPos;
    float dist = length(toLight);
    vec3 lightDir = toLight / max(dist, 0.001);

    float cone = coneAttenuation(toLight);
    float distAtten = distanceAttenuation(dist);
    float diffuse = max(dot(normal, lightDir), 0.0);
    float shadow = shadowMapLookup(worldPos);

    float intensity = cone * distAtten * diffuse * shadow * u_intensity;

    fragColor = vec4(u_lightColor * intensity, 1.0);
    return;
#endif
}
