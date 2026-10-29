package com.yfuse.cutoutprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Numerical diagnostics only. Clipping is a preview policy, not a confidence/quality pass. */
final class MaskAnalysis {
    static final class Stats {
        int nonFinite, outsideUnitRange, beyondRoundoff;
        float minimum = Float.POSITIVE_INFINITY, maximum = Float.NEGATIVE_INFINITY;
        String firstInvalid = "";
    }

    static Stats analyze(float[] values) {
        if (values == null || values.length == 0) throw new IllegalArgumentException("empty mask");
        Stats stats = new Stats();
        for (int i = 0; i < values.length; i++) {
            float value = values[i];
            if (!Float.isFinite(value)) stats.nonFinite++;
            else {
                stats.minimum = Math.min(stats.minimum, value);
                stats.maximum = Math.max(stats.maximum, value);
                if (value < 0 || value > 1) stats.outsideUnitRange++;
                if (value < -0.001f || value > 1.001f) stats.beyondRoundoff++;
            }
            if (stats.firstInvalid.isEmpty() && (!Float.isFinite(value) || value < 0 || value > 1)) {
                stats.firstInvalid = i + ":" + value;
            }
        }
        return stats;
    }

    static float[] clipForPreview(float[] values) {
        if (analyze(values).nonFinite != 0) throw new IllegalArgumentException("non-finite mask");
        float[] clipped = new float[values.length];
        for (int i = 0; i < values.length; i++) clipped[i] = Math.max(0, Math.min(1, values[i]));
        return clipped;
    }

    static byte[] encode(float[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(values.length, 4))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) bytes.putFloat(value);
        return bytes.array();
    }

    static final class AlphaComparison {
        double meanAbsoluteError, intersectionOverUnion, disagreementFraction;
        int maximumError;
    }

    static AlphaComparison compareAlpha(float[] clipped, int[] sdkColors) {
        if (clipped.length == 0 || clipped.length != sdkColors.length) {
            throw new IllegalArgumentException("alpha dimensions");
        }
        long sum = 0, intersection = 0, union = 0, disagree = 0;
        AlphaComparison comparison = new AlphaComparison();
        for (int i = 0; i < clipped.length; i++) {
            float value = clipped[i];
            if (!Float.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("alpha is not clipped finite confidence");
            }
            int alpha = Math.round(value * 255), sdkAlpha = sdkColors[i] >>> 24;
            int difference = Math.abs(alpha - sdkAlpha);
            sum += difference;
            comparison.maximumError = Math.max(comparison.maximumError, difference);
            if (difference > 8) disagree++;
            if (alpha >= 128 && sdkAlpha >= 128) intersection++;
            if (alpha >= 128 || sdkAlpha >= 128) union++;
        }
        comparison.meanAbsoluteError = (double) sum / clipped.length;
        comparison.intersectionOverUnion = union == 0 ? 1 : (double) intersection / union;
        comparison.disagreementFraction = (double) disagree / clipped.length;
        return comparison;
    }
}
