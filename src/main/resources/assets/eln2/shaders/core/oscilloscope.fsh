#version 150

in vec2 v_UV;

// FUCK YOU MOJANG YOU DIDN'T PACK THE BUFFER CORRECTLY FUCK YOU!

uniform sampler2D Sampler0;
uniform float u_writeX;
uniform float u_count;
uniform float u_thickness;
uniform float u_alpha;
uniform float u_channelColors[16];
uniform float u_axisInfo[7];

out vec4 fragColor;

float unpackFloatFromRGBA(vec4 rgba) {
    float s1 = dot(rgba, vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0));

    return -1.0 + s1 * 2.0;
}

float catmullRom(float p0, float p1, float p2, float p3, float t) {
    float t2 = t * t;
    float t3 = t2 * t;
    return 0.5 * ((2.0 * p1) +(-p0 + p2) * t +(2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +(-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
}

float catmullRomDerivative(float p0, float p1, float p2, float p3, float t) {
    float a = -p0 + p2;
    float b = 2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3;
    float c = -p0 + 3.0 * p1 - 3.0 * p2 + p3;

    return 0.5 * (a + 2.0 * b * t + 3.0 * c * t * t);
}

vec4 gridColor() {
    float horizontalCuts = u_axisInfo[0];
    float aspectRatio = u_axisInfo[1];
    float axisThickness = u_axisInfo[2];

    float verticalCuts = ceil(aspectRatio * horizontalCuts);

    float cellsX = (1.0 + verticalCuts);
    float cellsY = (1.0 + horizontalCuts);

    vec2 uvCell = v_UV * vec2(cellsX, cellsY);

    float fracX = fract(uvCell.x);
    float fracY = fract(uvCell.y);

    float distanceX = min(fracX, 1.0 - fracX) / cellsX;
    float distanceY = min(fracY, 1.0 - fracY) / cellsY;

    float distance = min(distanceX, distanceY);

    float weight = 1.0 - smoothstep(0.0, axisThickness, distance);
    int include = int(weight > 0.85);

    return include * vec4(u_axisInfo[3], u_axisInfo[4], u_axisInfo[5], u_axisInfo[6] * weight);
}

void main() {
    ivec2 texSize = textureSize(Sampler0, 0);
    int columns = texSize.x;
    int channelCount = texSize.y;

    // Current number of samples in the buffer (when full, it is equal to the number of columns):
    int sampleCount = int(u_count);

    // In-buffer index of the oldest sample:
    int oldestIdx = (int(u_writeX) - sampleCount + columns) % columns;

    // UV progress from the edge of the scope, in number of columns (0 at the start, ~columns at the end):
    // The small difference is added for the floor.
    float uvColumns = min(v_UV.x, 0.999999) * columns;

    // The texel falls between two columns. Let's call the one on the left midA, and the one on the right midB.
    int midAOffset = int(floor(uvColumns));
    int midAIdx = (oldestIdx + midAOffset) % columns;

    // If no midB is present, discard.
    bool hasMidB = ((midAIdx - oldestIdx + columns) % columns) < sampleCount - 1;

    vec4 grid = gridColor();

    if(!hasMidB) {
        // Just the axis color now:
        fragColor = grid;
        return;
    }

    int midBOffset = min(midAOffset + 1, sampleCount - 1);
    int midBIdx = (oldestIdx + midBOffset) % columns;

    // Right now, we have the two texels that surround our current abscissa. We can construct a line.
    // But if we have the texels left of midA and right of midB, we can construct a spline.

    // Let's call the texel to the left of midA left, and the texel to the right of midB right:
    int leftOffset = max(midAOffset - 1, 0);
    int rightOffset = min(midBOffset + 1, sampleCount - 1);
    int leftIdx = (oldestIdx + leftOffset) % columns;
    int rightIdx = (oldestIdx + rightOffset) % columns;

    // Partial column is [0, 1], so we use it directly as an interpolating parameter.
    float partialColumn = uvColumns - midAOffset;

    // Then calculate the waveform contributions:

    // We need to calculate the distance from the fragment to each wave.
    // The intensity of each texel corresponds to the wave's Y in a [-1, 1] range.
    // Our UV landed between two columns, so, for each channel, we can construct the line that represents the local approximation of the waveform.
    // Then, based on this, we can calculate the distance from the fragment to the line, and then smoothly calculate the intensity of the wave in this fragment, based on the thickness parameter

    vec3 colorSum = vec3(0.0);
    float weightSum = 0.0;
    int contributedColors = 0;

    int gridContributed = int(grid.w > 0.0);
    colorSum += gridContributed * grid.xyz;
    weightSum += gridContributed * grid.w;
    contributedColors += gridContributed;

    for(int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
        // Fetches the Y of the waveform in the texels.
        // We map from [-1, 1] to [0, 1], so it's in UV space:
        float yLeft = 0.5 * (unpackFloatFromRGBA(texelFetch(Sampler0, ivec2(leftIdx, channelIndex), 0)) + 1.0);
        float yMidA = 0.5 * (unpackFloatFromRGBA(texelFetch(Sampler0, ivec2(midAIdx, channelIndex), 0)) + 1.0);
        float yMidB = 0.5 * (unpackFloatFromRGBA(texelFetch(Sampler0, ivec2(midBIdx, channelIndex), 0)) + 1.0);
        float yRight = 0.5 * (unpackFloatFromRGBA(texelFetch(Sampler0, ivec2(rightIdx, channelIndex), 0)) + 1.0);

        // The height of the waveform:
        float y = catmullRom(yLeft, yMidA, yMidB, yRight, partialColumn);

        // We linearize the curve over the short segment to calculate the distance to the fragment.
        // It is an approximation, but it is sure fast.
        float dydx = catmullRomDerivative(yLeft, yMidA, yMidB, yRight, partialColumn) / float(columns);
        float minDistance = abs(v_UV.y - y) / sqrt(dydx * dydx + 1.0);

        // Then we can calculate a smooth contribution of the channel to the fragment, based on the thickness:
        float weight = 1.0 - smoothstep(0.0, u_thickness, minDistance);

        int j = channelIndex * 4;
        vec4 channelColorRGBA = vec4(u_channelColors[j + 0], u_channelColors[j + 1], u_channelColors[j + 2], u_channelColors[j + 3]);

        // Include per-channel alpha. Also used to discard channels that are not connected.
        weight *= channelColorRGBA.w;

        // Cutoff per-channel.
        // If we do cutoff at the final stage, the fuzz from one channel will over-inflate the result.
        int shouldInclude = int(weight > 0.75);

        // Blending:
        colorSum += shouldInclude * channelColorRGBA.xyz * weight;
        weightSum += shouldInclude * weight;
        contributedColors += shouldInclude;
    }

    if(contributedColors == 0) {
        discard;
    }

    fragColor = vec4(colorSum / weightSum, weightSum / contributedColors * u_alpha);
}
