#version 150

out vec4 fragColor;

uniform sampler2D DepthSampler;
uniform mat4 InvViewProjMat;
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
    return smoothstep(edge, edge + 0.05, cosTheta);
}

float distanceAttenuation(float dist) {
    float r = u_range;
    return clamp(1.0 - (dist / r), 0.0, 1.0);
}

float screenSpaceShadow(vec3 worldPos) {
    vec3 toLight = u_lightPosition - worldPos;
    float distToLight = length(toLight);
    vec3 dir = toLight / distToLight;

    float maxDist = min(distToLight, u_range);
    int steps = 12;
    float result = 1.0;

    for (int i = 1; i <= steps; i++) {
        float t = (float(i) / float(steps)) * maxDist;
        vec3 samplePos = worldPos + dir * t;

        vec4 clipPos = InvViewProjMat * vec4(samplePos, 1.0);
        vec3 ndc = clipPos.xyz / clipPos.w;
        vec2 sampleUV = ndc.xy * 0.5 + 0.5;

        if (sampleUV.x < 0.0 || sampleUV.x > 1.0 || sampleUV.y < 0.0 || sampleUV.y > 1.0) {
            break;
        }

        float sampleDepth = texture(DepthSampler, sampleUV).r;
        float bias = 0.002;
        if (sampleDepth < ndc.z - bias) {
            result = 0.0;
            break;
        }
    }

    return result;
}

void main() {
    vec2 uv = gl_FragCoord.xy / u_screenSize;
    float depth = texture(DepthSampler, uv).r;

    if (depth >= 1.0) {
        discard;
    }

    vec3 worldPos = worldPosFromDepth(uv, depth);
    vec3 normal = reconstructNormal(uv);

    vec3 toLight = u_lightPosition - worldPos;
    float dist = length(toLight);
    vec3 lightDir = toLight / max(dist, 0.001);

    float cone = coneAttenuation(toLight);
    float distAtten = distanceAttenuation(dist);
    float diffuse = max(dot(normal, lightDir), 0.0);
    float shadow = screenSpaceShadow(worldPos);

    float intensity = cone * distAtten * diffuse * shadow * u_intensity;

    fragColor = vec4(u_lightColor * intensity, 1.0);
}
