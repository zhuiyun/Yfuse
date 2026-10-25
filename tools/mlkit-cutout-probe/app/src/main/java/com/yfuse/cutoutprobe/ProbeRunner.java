package com.yfuse.cutoutprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.android.gms.common.moduleinstall.InstallStatusListener;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Real SDK/model only: a failed module download is an environment failure, never a mask. */
final class ProbeRunner {
    static final String[] CASES = {"whalefall", "heart-of-the-beast"};
    private static final String TAG = "MlkitCutoutProbe";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private final Context context;
    private final Consumer<String> progress;
    private final File output;
    private final JSONObject report = new JSONObject();

    ProbeRunner(Context context, Consumer<String> progress) {
        this.context = context.getApplicationContext();
        this.progress = progress;
        output = new File(context.getFilesDir(), "mlkit-probe");
    }

    JSONObject run() {
        return run(Collections.emptyList());
    }

    JSONObject run(List<Uri> selected) {
        if (!RUNNING.compareAndSet(false, true)) {
            try { return new JSONObject().put("status", "already_running"); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        }
        SubjectSegmenter segmenter = null;
        try {
            // Remove outputs of a previous attempt, so failures cannot display stale success.
            if (!output.exists() && !output.mkdirs()) throw new IllegalStateException("output directory");
            File[] previous = output.listFiles();
            if (previous == null) throw new IllegalStateException("unreadable output directory");
            for (File file : previous) {
                if (file.isFile() && !file.delete()) throw new IllegalStateException("cannot clear previous output");
            }
            report.put("report_schema", 2);
            report.put("probe_version", BuildConfig.VERSION_NAME);
            report.put("probe_version_code", BuildConfig.VERSION_CODE);
            report.put("quality_verdict", "not_evaluated");
            report.put("preview_policy", "clip_finite_values_to_0_1_preserve_all_raw_anomalies");
            report.put("comparison_scope", "same_inference_alpha_only_not_independent_quality_validation");
            report.put("sdk", "play-services-mlkit-subject-segmentation:16.0.0-beta1");
            report.put("api", Build.VERSION.SDK_INT);
            report.put("abis", new JSONArray(Build.SUPPORTED_ABIS));
            report.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            report.put("status", "starting");
            report.put("cases", new JSONArray());
            int gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
            report.put("google_play_services_status", gms);
            try {
                report.put("google_play_services_version", context.getPackageManager()
                        .getPackageInfo("com.google.android.gms", 0).versionName);
            } catch (Exception ignored) {
                report.put("google_play_services_version", "not_installed");
            }
            save();
            if (gms != 0) {
                report.put("status", "google_play_services_unavailable");
                emit("Google Play 服务不可用，状态码 " + gms);
                return report;
            }
            SubjectSegmenterOptions options = new SubjectSegmenterOptions.Builder()
                    .enableForegroundConfidenceMask().enableForegroundBitmap().build();
            segmenter = SubjectSegmentation.getClient(options);
            report.put("status", "waiting_for_model");
            save();
            if (!awaitModel(segmenter)) {
                report.put("status", "model_unavailable");
                emit("模型未能就绪；本次没有生成抠图结果。");
                return report;
            }
            report.put("model_ready", true);
            JSONArray caseErrors = new JSONArray();
            report.put("case_errors", caseErrors);
            List<String> names = new ArrayList<>();
            if (selected.isEmpty()) names.addAll(Arrays.asList(CASES));
            else for (int i = 0; i < selected.size(); i++) names.add("selected-" + (i + 1));
            report.put("requested_case_count", names.size());
            int completed = 0;
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                try {
                    process(segmenter, name, selected.isEmpty() ? null : selected.get(i));
                    completed++;
                } catch (Exception exception) {
                    caseErrors.put(new JSONObject().put("name", name).put("error", error(exception)));
                    emit(name + " 失败：" + error(exception));
                    segmenter.close();
                    segmenter = SubjectSegmentation.getClient(options);
                    save();
                }
            }
            int previewCount = 0, sdkCount = 0, validCount = 0, warningCount = 0;
            JSONArray cases = report.getJSONArray("cases");
            for (int i = 0; i < cases.length(); i++) {
                JSONObject item = cases.getJSONObject(i);
                if (item.optBoolean("preview_created")) previewCount++;
                if (item.optBoolean("sdk_foreground_created")) sdkCount++;
                if (item.optBoolean("confidence_within_roundoff")) validCount++;
                if (item.optInt("outside_unit_range_count") > 0) warningCount++;
            }
            report.put("valid_output_count", validCount);
            report.put("diagnostic_output_count", previewCount);
            report.put("sdk_output_count", sdkCount);
            report.put("cross_checked_count", completed);
            report.put("range_warning_count", warningCount);
            report.put("status", completed == names.size() ? "diagnostic_outputs_created"
                    : previewCount > 0 || sdkCount > 0 ? "partial_diagnostic_outputs" : "no_valid_outputs");
            emit("已导出 " + completed + "/" + names.size() + " 组对比；" + warningCount
                    + " 张有数值越界记录。导出成功不代表抠图质量通过。");
        } catch (Exception exception) {
            try {
                report.put("status", "failed");
                report.put("error", error(exception));
            } catch (Exception ignored) { }
            emit("验证失败：" + error(exception));
        } finally {
            if (segmenter != null) segmenter.close();
            try { save(); } catch (Exception ignored) { }
            RUNNING.set(false);
        }
        return report;
    }

    private boolean awaitModel(SubjectSegmenter segmenter) throws Exception {
        ModuleInstallClient installer = ModuleInstall.getClient(context);
        boolean ready = Tasks.await(installer.areModulesAvailable(segmenter), 30, TimeUnit.SECONDS)
                .areModulesAvailable();
        report.put("model_available_initially", ready);
        if (ready) return true;
        emit("正在请求 Google 下载主体分割模型……");
        JSONArray updates = new JSONArray();
        report.put("module_install_updates", updates);
        AtomicInteger installError = new AtomicInteger();
        InstallStatusListener listener = update -> {
            try {
                JSONObject state = new JSONObject();
                state.put("state", update.getInstallState());
                state.put("error_code", update.getErrorCode());
                installError.set(update.getErrorCode());
                if (update.getProgressInfo() != null) {
                    state.put("bytes_downloaded", update.getProgressInfo().getBytesDownloaded());
                    state.put("total_bytes", update.getProgressInfo().getTotalBytesToDownload());
                }
                synchronized (updates) { updates.put(state); }
                emit("模型下载状态 " + update.getInstallState() + "，错误码 " + update.getErrorCode());
            } catch (Exception ignored) { }
        };
        ModuleInstallRequest request = ModuleInstallRequest.newBuilder().addApi(segmenter)
                .setListener(listener).build();
        try {
            boolean installed = Tasks.await(installer.installModules(request), 30, TimeUnit.SECONDS)
                    .areModulesAlreadyInstalled();
            report.put("install_response_already_installed", installed);
            long start = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - start < 600_000) {
                ready = Tasks.await(installer.areModulesAvailable(segmenter), 20, TimeUnit.SECONDS)
                        .areModulesAvailable();
                report.put("model_wait_ms", SystemClock.elapsedRealtime() - start);
                save();
                if (ready) return true;
                if (installError.get() != 0) return false;
                emit("等待模型下载：" + ((SystemClock.elapsedRealtime() - start) / 1000) + " 秒");
                Thread.sleep(10_000);
            }
            return false;
        } finally {
            installer.unregisterListener(listener);
        }
    }

    private InputStream openInput(String name, Uri selected) throws Exception {
        InputStream input = selected == null ? context.getAssets().open(name + ".jpg")
                : context.getContentResolver().openInputStream(selected);
        if (input == null) throw new IllegalStateException("unreadable input " + name);
        return input;
    }

    private void process(SubjectSegmenter segmenter, String name, Uri selected) throws Exception {
        emit("正在处理：" + name);
        JSONObject item = new JSONObject().put("name", name)
                .put("input_type", selected == null ? "verified_public_asset" : "user_selected_image")
                .put("quality_verdict", "not_evaluated").put("status", "processing");
        report.getJSONArray("cases").put(item);
        Bitmap source = null, sdkForeground = null, foreground = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = openInput(name, selected)) {
                byte[] bytes = new byte[16384];
                int length;
                while ((length = stream.read(bytes)) != -1) digest.update(bytes, 0, length);
            }
            item.put("input_sha256", hex(digest.digest()));
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = openInput(name, selected)) {
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalStateException("invalid image");
            item.put("original_width", bounds.outWidth).put("original_height", bounds.outHeight);
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decode.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / decode.inSampleSize > 2048) {
                decode.inSampleSize *= 2;
            }
            try (InputStream stream = openInput(name, selected)) {
                source = BitmapFactory.decodeStream(stream, null, decode);
            }
            if (source == null) throw new IllegalStateException("decode failed");
            float scale = Math.min(1f, 1024f / Math.max(source.getWidth(), source.getHeight()));
            if (scale < 1f) {
                Bitmap resized = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
                        Math.max(1, Math.round(source.getHeight() * scale)), true);
                if (resized != source) source.recycle();
                source = resized;
            }
            int width = source.getWidth(), height = source.getHeight(), count = width * height;
            item.put("width", width).put("height", height);
            writeBitmap(source, new File(output, name + "-source.png"));
            long start = SystemClock.elapsedRealtime();
            SubjectSegmentationResult result = Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)),
                    120, TimeUnit.SECONDS);
            item.put("inference_ms", SystemClock.elapsedRealtime() - start);
            // Save the SDK's own bitmap before inspecting the mask: preserve either output
            // even if the other is malformed. No replacement model or mask is generated.
            sdkForeground = result.getForegroundBitmap();
            boolean sdkMatches = false;
            if (sdkForeground != null) {
                item.put("sdk_width", sdkForeground.getWidth()).put("sdk_height", sdkForeground.getHeight());
                File sdkFile = new File(output, name + "-sdk-foreground.png");
                writeBitmap(sdkForeground, sdkFile);
                item.put("sdk_foreground_created", true);
                item.put("sdk_foreground_sha256", fileSha(sdkFile));
                sdkMatches = sdkForeground.getWidth() == width && sdkForeground.getHeight() == height;
                item.put("sdk_dimensions_match", sdkMatches);
            }
            FloatBuffer mask = result.getForegroundConfidenceMask();
            if (mask == null) throw new IllegalStateException("missing confidence mask");
            mask = mask.duplicate();
            mask.rewind();
            if (mask.remaining() != count) throw new IllegalStateException("mask dimensions");
            float[] values = new float[count];
            mask.get(values);
            byte[] raw = MaskAnalysis.encode(values);
            writeBytes(raw, new File(output, name + ".f32"));
            MaskAnalysis.Stats stats = MaskAnalysis.analyze(values);
            item.put("raw_mask_minimum", String.valueOf(stats.minimum));
            item.put("raw_mask_maximum", String.valueOf(stats.maximum));
            item.put("non_finite_count", stats.nonFinite);
            item.put("outside_unit_range_count", stats.outsideUnitRange);
            item.put("beyond_roundoff_count", stats.beyondRoundoff);
            item.put("first_outside_unit_range", stats.firstInvalid);
            item.put("mask_sha256", hex(MessageDigest.getInstance("SHA-256").digest(raw)));
            item.put("confidence_within_roundoff", stats.nonFinite == 0 && stats.beyondRoundoff == 0);
            item.put("confidence_status", stats.nonFinite > 0 ? "non_finite"
                    : stats.beyondRoundoff > 0 ? "out_of_range_beyond_roundoff"
                    : stats.outsideUnitRange > 0 ? "roundoff_outside_unit_range" : "within_unit_range");
            save();
            if (stats.nonFinite > 0) throw new IllegalStateException("non-finite mask; raw and SDK outputs retained");
            // Finite out-of-range values are clipped for a DIAGNOSTIC preview only.
            // They remain recorded as anomalies and never become a numerical/quality pass.
            float[] clipped = MaskAnalysis.clipForPreview(values);
            byte[] clippedBytes = MaskAnalysis.encode(clipped);
            writeBytes(clippedBytes, new File(output, name + "-clamped.f32"));
            item.put("clamped_mask_sha256", hex(MessageDigest.getInstance("SHA-256").digest(clippedBytes)));
            int[] colors = new int[count];
            source.getPixels(colors, 0, width, 0, 0, width, height);
            int solid = 0;
            for (int i = 0; i < count; i++) {
                if (clipped[i] >= .85f) solid++;
                colors[i] = (colors[i] & 0x00ffffff) | (Math.round(clipped[i] * 255) << 24);
            }
            foreground = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
            File previewFile = new File(output, name + "-foreground.png");
            writeBitmap(foreground, previewFile);
            item.put("preview_created", true).put("preview_sha256", fileSha(previewFile));
            item.put("solid_fraction", (double) solid / count);
            if (!sdkMatches) throw new IllegalStateException("SDK foreground missing or dimensions differ; preview retained");
            sdkForeground.getPixels(colors, 0, width, 0, 0, width, height);
            MaskAnalysis.AlphaComparison comparison = MaskAnalysis.compareAlpha(clipped, colors);
            item.put("alpha_mean_absolute_error_255", comparison.meanAbsoluteError);
            item.put("alpha_maximum_error_255", comparison.maximumError);
            item.put("alpha_disagreement_fraction_over_8", comparison.disagreementFraction);
            item.put("alpha_iou_at_128", comparison.intersectionOverUnion);
            int sdkVisible = 0;
            for (int color : colors) if ((color >>> 24) >= 128) sdkVisible++;
            item.put("sdk_visible_fraction", (double) sdkVisible / count);
            item.put("sdk_degenerate_alpha", sdkVisible == 0 || sdkVisible == count);
            item.put("status", stats.outsideUnitRange > 0
                    ? "diagnostic_outputs_created_with_range_warning" : "diagnostic_outputs_created");
            item.put("java_heap_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            item.put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize());
            item.put("app_pss_kib", Debug.getPss());
            emit(name + " 已导出对比，耗时 " + item.optLong("inference_ms") + "ms");
        } catch (Exception exception) {
            item.put("status", "incomplete_diagnostic_outputs").put("error", error(exception));
            throw exception;
        } finally {
            if (foreground != null) foreground.recycle();
            if (sdkForeground != null && sdkForeground != source) sdkForeground.recycle();
            if (source != null) source.recycle();
            save();
        }
    }

    private static void writeBytes(byte[] bytes, File file) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(bytes); }
    }

    private static String fileSha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new java.io.FileInputStream(file)) {
            byte[] bytes = new byte[16384];
            int count;
            while ((count = stream.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return hex(digest.digest());
    }

    static void writeBitmap(Bitmap bitmap, File file) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException("PNG encode");
        }
    }

    private void save() throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(output, "report.json"))) {
            stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void emit(String message) {
        Log.i(TAG, message);
        progress.accept(message);
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format("%02x", b & 255));
        return value.toString();
    }

    private static String error(Throwable throwable) {
        while (throwable.getCause() != null) throwable = throwable.getCause();
        return throwable.getClass().getSimpleName() + (throwable instanceof ApiException
                ? ":status=" + ((ApiException) throwable).getStatusCode() : ":" + throwable.getMessage());
    }
}
