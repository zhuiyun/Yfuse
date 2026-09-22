package com.yfuse.harmony.entry.design

public struct RgbSample {
    public let red: Float64
    public let green: Float64
    public let blue: Float64
    public init(red: Float64, green: Float64, blue: Float64) {
        this.red = red; this.green = green; this.blue = blue
    }
}

private func clamp(value: Float64, minimum: Float64, maximum: Float64): Float64 {
    return if (value < minimum) { minimum } else if (value > maximum) { maximum } else { value }
}

private func channel(value: Float64): UInt32 { UInt32(clamp(value, 0.0, 255.0)) }

/**
 * Samples only the lower artwork band and derives a page surface. It never rewrites poster pixels.
 * Saturation is retained; the minimum luminance applies exclusively to the page/background color.
 */
public func artworkBottomPageColor(samples: Array<RgbSample>, dark: Bool): UInt32 {
    if (samples.isEmpty()) { return if (dark) { DesignTokens.darkBackground } else { DesignTokens.lightBackground } }
    var totalWeight: Float64 = 0.0
    var red: Float64 = 0.0
    var green: Float64 = 0.0
    var blue: Float64 = 0.0
    for (sample in samples) {
        let maximum = max(sample.red, max(sample.green, sample.blue))
        let minimum = min(sample.red, min(sample.green, sample.blue))
        let saturationWeight = 0.35 + (maximum - minimum) / 255.0
        let luminance = 0.2126 * sample.red + 0.7152 * sample.green + 0.0722 * sample.blue
        let luminanceWeight = if (luminance < 10.0 || luminance > 248.0) { 0.18 } else { 1.0 }
        let weight = saturationWeight * luminanceWeight
        red += sample.red * weight
        green += sample.green * weight
        blue += sample.blue * weight
        totalWeight += weight
    }
    if (totalWeight <= 0.0) { return if (dark) { DesignTokens.darkBackground } else { DesignTokens.lightBackground } }
    red /= totalWeight; green /= totalWeight; blue /= totalWeight
    let luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
    let target = if (dark) { clamp(luminance, 18.0, 54.0) } else { clamp(luminance, 222.0, 246.0) }
    let scale = if (luminance <= 0.1) { 1.0 } else { target / luminance }
    return 0xFF000000 | (channel(red * scale) << 16) | (channel(green * scale) << 8) | channel(blue * scale)
}

private func max(left: Float64, right: Float64): Float64 { if (left > right) { left } else { right } }
private func min(left: Float64, right: Float64): Float64 { if (left < right) { left } else { right } }
