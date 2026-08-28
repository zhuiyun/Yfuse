#version 450

layout(set = 0, binding = 0) uniform sampler2D decodedFrame;
layout(location = 0) in vec2 textureCoordinate;
layout(location = 0) out vec4 outputColor;

layout(push_constant) uniform FrameParameters {
    int sourceTransfer;       // 0 SDR, 1 ST-2084/PQ, 2 HLG
    int outputTransfer;       // 0 SDR, 1 ST-2084/PQ, 2 HLG
    int sourcePrimaries;      // 0 BT.709, 1 BT.2020, 2 Display-P3
    int outputPrimaries;
    int scalingFilter;        // 0 bilinear, 1 bicubic, 2 Lanczos2
    int sourceBitDepth;
    int frameIndex;
    int toneMapEnabled;
    float sourcePeakNits;
    float displayPeakNits;
    float paperWhiteNits;
    float debandStrength;
    float ditherStrength;
    float outputWidth;
    float outputHeight;
    int sourceRange;          // 1 limited, 2 full
    int ycbcrMatrix;          // 1 BT.601, 2 BT.709, 3 BT.2020, 4 identity
    int chromaLocation;
    int rotationDegrees;
    float pixelAspectRatio;
    float cropLeft;
    float cropTop;
    float cropRight;
    float cropBottom;
    int dynamicMetadataEnabled;
    int dynamicAnchorCount;
    float dynamicTargetNits;
    float dynamicScenePeakNits;
    float dynamicAverageNits;
    float dynamicKneeX;
    float dynamicKneeY;
    float dynamicAnchorMean;
} parameters;

const float PI = 3.14159265358979323846;

float pqEotf(float encoded) {
    const float m1 = 2610.0 / 16384.0;
    const float m2 = 2523.0 / 32.0;
    const float c1 = 3424.0 / 4096.0;
    const float c2 = 2413.0 / 128.0;
    const float c3 = 2392.0 / 128.0;
    float p = pow(max(encoded, 0.0), 1.0 / m2);
    return 10000.0 * pow(max(p - c1, 0.0) / max(c2 - c3 * p, 1e-6), 1.0 / m1);
}

float pqOetf(float nits) {
    const float m1 = 2610.0 / 16384.0;
    const float m2 = 2523.0 / 32.0;
    const float c1 = 3424.0 / 4096.0;
    const float c2 = 2413.0 / 128.0;
    const float c3 = 2392.0 / 128.0;
    float p = pow(clamp(nits / 10000.0, 0.0, 1.0), m1);
    return pow((c1 + c2 * p) / (1.0 + c3 * p), m2);
}

float hlgEotf(float encoded) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    float scene = encoded <= 0.5
        ? encoded * encoded / 3.0
        : (exp((encoded - c) / a) + b) / 12.0;
    return 1000.0 * pow(max(scene, 0.0), 1.2);
}

float hlgOetf(float nits) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    float scene = pow(clamp(nits / 1000.0, 0.0, 1.0), 1.0 / 1.2);
    return scene <= (1.0 / 12.0)
        ? sqrt(3.0 * scene)
        : a * log(12.0 * scene - b) + c;
}

vec3 decodeTransfer(vec3 encoded) {
    if (parameters.sourceTransfer == 1) {
        return vec3(pqEotf(encoded.r), pqEotf(encoded.g), pqEotf(encoded.b));
    }
    if (parameters.sourceTransfer == 2) {
        return vec3(hlgEotf(encoded.r), hlgEotf(encoded.g), hlgEotf(encoded.b));
    }
    return pow(max(encoded, vec3(0.0)), vec3(2.2)) * parameters.paperWhiteNits;
}

vec3 encodeTransfer(vec3 nits) {
    if (parameters.outputTransfer == 1) {
        return vec3(pqOetf(nits.r), pqOetf(nits.g), pqOetf(nits.b));
    }
    if (parameters.outputTransfer == 2) {
        return vec3(hlgOetf(nits.r), hlgOetf(nits.g), hlgOetf(nits.b));
    }
    return pow(clamp(nits / max(parameters.paperWhiteNits, 1.0), 0.0, 1.0), vec3(1.0 / 2.2));
}

vec3 convertPrimaries(vec3 rgb) {
    if (parameters.sourcePrimaries == parameters.outputPrimaries) return rgb;
    if (parameters.sourcePrimaries == 1 && parameters.outputPrimaries == 0) {
        return mat3(
             1.660491, -0.124550, -0.018151,
            -0.587641,  1.132900, -0.100579,
            -0.072850, -0.008349,  1.118730
        ) * rgb;
    }
    if (parameters.sourcePrimaries == 1 && parameters.outputPrimaries == 2) {
        return mat3(
             1.343578, -0.065298,  0.002822,
            -0.282180,  1.075788, -0.019599,
            -0.061399, -0.010490,  1.016777
        ) * rgb;
    }
    return rgb;
}

// BT.2390 EETF Hermite knee in normalized PQ code space. It preserves the unit slope below the
// knee and reaches the target display peak with a zero terminal slope.
float bt2390(float valueNits) {
    float sourcePeak = parameters.dynamicMetadataEnabled != 0 && parameters.dynamicScenePeakNits > 0.0
        ? max(parameters.dynamicScenePeakNits, parameters.paperWhiteNits)
        : max(parameters.sourcePeakNits, parameters.paperWhiteNits);
    float targetPeak = max(parameters.displayPeakNits, parameters.paperWhiteNits);
    if (sourcePeak <= targetPeak) return min(valueNits, targetPeak);
    float sourceCode = max(pqOetf(sourcePeak), 1e-5);
    float targetCode = clamp(pqOetf(targetPeak) / sourceCode, 0.0, 1.0);
    float inputCode = clamp(pqOetf(valueNits) / sourceCode, 0.0, 1.0);
    float knee = parameters.dynamicMetadataEnabled != 0 && parameters.dynamicKneeX > 0.0
        ? clamp(parameters.dynamicKneeX, 0.0, targetCode)
        : clamp(1.5 * targetCode - 0.5, 0.0, targetCode);
    if (inputCode <= knee) return valueNits;
    float interval = max(1.0 - knee, 1e-5);
    float t = clamp((inputCode - knee) / interval, 0.0, 1.0);
    float h00 = 2.0 * t * t * t - 3.0 * t * t + 1.0;
    float h10 = t * t * t - 2.0 * t * t + t;
    float h01 = -2.0 * t * t * t + 3.0 * t * t;
    float kneeOutput = parameters.dynamicMetadataEnabled != 0 && parameters.dynamicKneeY > 0.0
        ? min(parameters.dynamicKneeY, targetCode) : knee;
    float anchorBias = parameters.dynamicAnchorCount > 0
        ? clamp(parameters.dynamicAnchorMean, 0.0, 1.0) : 0.5;
    float dynamicT = mix(t, smoothstep(0.0, 1.0, t), anchorBias);
    h00 = 2.0 * dynamicT * dynamicT * dynamicT - 3.0 * dynamicT * dynamicT + 1.0;
    h10 = dynamicT * dynamicT * dynamicT - 2.0 * dynamicT * dynamicT + dynamicT;
    h01 = -2.0 * dynamicT * dynamicT * dynamicT + 3.0 * dynamicT * dynamicT;
    float mappedCode = h00 * kneeOutput + h10 * interval + h01 * targetCode;
    return pqEotf(clamp(mappedCode * sourceCode, 0.0, 1.0));
}

float cubicWeight(float x) {
    x = abs(x);
    if (x <= 1.0) return 1.5 * x * x * x - 2.5 * x * x + 1.0;
    if (x < 2.0) return -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0;
    return 0.0;
}

float sinc(float x) {
    if (abs(x) < 1e-5) return 1.0;
    float p = PI * x;
    return sin(p) / p;
}

vec3 filteredSample(vec2 uv) {
    if (parameters.scalingFilter == 0) return texture(decodedFrame, uv).rgb;
    vec2 size = vec2(textureSize(decodedFrame, 0));
    vec2 position = uv * size - 0.5;
    vec2 base = floor(position);
    vec2 fraction = position - base;
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    for (int y = -1; y <= 2; ++y) {
        for (int x = -1; x <= 2; ++x) {
            vec2 offset = vec2(x, y);
            vec2 delta = offset - fraction;
            float weight = parameters.scalingFilter == 1
                ? cubicWeight(delta.x) * cubicWeight(delta.y)
                : sinc(delta.x) * sinc(delta.x / 2.0) * sinc(delta.y) * sinc(delta.y / 2.0);
            sum += texture(decodedFrame, (base + offset + 0.5) / size).rgb * weight;
            weightSum += weight;
        }
    }
    return sum / max(weightSum, 1e-5);
}

vec2 aspectCorrectedCoordinate(vec2 uv, out bool inside) {
    vec2 inputSize = vec2(textureSize(decodedFrame, 0));
    vec2 cropMinimum = vec2(parameters.cropLeft, parameters.cropTop) / inputSize;
    vec2 cropMaximum = vec2(1.0) - vec2(parameters.cropRight, parameters.cropBottom) / inputSize;
    vec2 visibleSize = max((cropMaximum - cropMinimum) * inputSize, vec2(1.0));
    float inputAspect = visibleSize.x * max(parameters.pixelAspectRatio, 0.01) / visibleSize.y;
    int rotation = ((parameters.rotationDegrees % 360) + 360) % 360;
    if (rotation == 90 || rotation == 270) inputAspect = 1.0 / max(inputAspect, 0.0001);
    float outputAspect = parameters.outputWidth / max(parameters.outputHeight, 1.0);
    vec2 corrected = uv;
    if (outputAspect > inputAspect) {
        corrected.x = (uv.x - 0.5) * (outputAspect / inputAspect) + 0.5;
    } else {
        corrected.y = (uv.y - 0.5) * (inputAspect / outputAspect) + 0.5;
    }
    inside = all(greaterThanEqual(corrected, vec2(0.0))) && all(lessThanEqual(corrected, vec2(1.0)));
    vec2 rotated = corrected;
    if (rotation == 90) rotated = vec2(corrected.y, 1.0 - corrected.x);
    else if (rotation == 180) rotated = vec2(1.0) - corrected;
    else if (rotation == 270) rotated = vec2(1.0 - corrected.y, corrected.x);
    return mix(cropMinimum, cropMaximum, rotated);
}

vec3 debandedSample(vec2 uv) {
    vec3 center = filteredSample(uv);
    vec2 texel = 1.0 / vec2(textureSize(decodedFrame, 0));
    vec3 neighbors = (
        texture(decodedFrame, uv + vec2(texel.x, 0.0)).rgb +
        texture(decodedFrame, uv - vec2(texel.x, 0.0)).rgb +
        texture(decodedFrame, uv + vec2(0.0, texel.y)).rgb +
        texture(decodedFrame, uv - vec2(0.0, texel.y)).rgb
    ) * 0.25;
    float maximumDelta = max(max(abs(center.r - neighbors.r), abs(center.g - neighbors.g)), abs(center.b - neighbors.b));
    float sourceLevels = float((1 << min(parameters.sourceBitDepth, 16)) - 1);
    float threshold = 3.0 / max(sourceLevels, 255.0);
    float flatRegion = 1.0 - smoothstep(threshold, threshold * 4.0, maximumDelta);
    return mix(center, neighbors, clamp(parameters.debandStrength * flatRegion, 0.0, 0.45));
}

vec3 gamutMap(vec3 rgbNits) {
    float targetPeak = parameters.outputTransfer == 0
        ? parameters.paperWhiteNits : parameters.displayPeakNits;
    vec3 lumaCoefficients = parameters.outputPrimaries == 0
        ? vec3(0.2126, 0.7152, 0.0722) : vec3(0.2627, 0.6780, 0.0593);
    float neutral = clamp(dot(rgbNits, lumaCoefficients), 0.0, targetPeak);
    vec3 chroma = rgbNits - vec3(neutral);
    float scale = 1.0;
    for (int channel = 0; channel < 3; ++channel) {
        if (chroma[channel] > 0.0) {
            scale = min(scale, (targetPeak - neutral) / chroma[channel]);
        } else if (chroma[channel] < 0.0) {
            scale = min(scale, neutral / -chroma[channel]);
        }
    }
    return clamp(vec3(neutral) + chroma * clamp(scale, 0.0, 1.0), 0.0, targetPeak);
}

float noise(vec2 coordinate) {
    return fract(52.9829189 * fract(dot(coordinate + float(parameters.frameIndex), vec2(0.06711056, 0.00583715))));
}

void main() {
    bool inside;
    vec2 videoCoordinate = aspectCorrectedCoordinate(textureCoordinate, inside);
    if (!inside) {
        outputColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 encoded = debandedSample(videoCoordinate);
    vec3 linearNits = max(decodeTransfer(encoded), vec3(0.0));

    float luma = dot(linearNits, vec3(0.2627, 0.6780, 0.0593));
    if (parameters.toneMapEnabled != 0) {
        float mappedLuma = bt2390(luma);
        linearNits *= mappedLuma / max(luma, 1e-4);
    }
    linearNits = gamutMap(convertPrimaries(linearNits));
    vec3 result = encodeTransfer(linearNits);

    float outputLevels = parameters.outputTransfer == 0 ? 255.0 : 1023.0;
    float dither = (noise(gl_FragCoord.xy) - 0.5) * parameters.ditherStrength / outputLevels;
    outputColor = vec4(clamp(result + vec3(dither), 0.0, 1.0), 1.0);
}
