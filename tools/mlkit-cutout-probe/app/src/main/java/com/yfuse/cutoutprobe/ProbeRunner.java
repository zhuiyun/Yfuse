package com.yfuse.cutoutprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
        if (!RUNNING.compareAndSet(false, true)) {
            try { return new JSONObject().put("status", "already_running"); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        }
        SubjectSegmenter segmenter = null;
        try {
            // Remove outputs of a previous attempt, so failures cannot display stale success.
            if (!output.exists() && !output.mkdirs()) throw new IllegalStateException("output directory");
            for (File file : output.listFiles()) if (file.isFile()) file.delete();
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
                    .enableForegroundConfidenceMask().build();
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
            int completed = 0;
            for (String name : CASES) {
                try {
                    process(segmenter, name);
                    completed++;
                } catch (Exception exception) {
                    caseErrors.put(new JSONObject().put("name", name).put("error", error(exception)));
                    emit(name + " 失败：" + error(exception));
                    segmenter.close();
                    segmenter = SubjectSegmentation.getClient(options);
                    save();
                }
            }
            report.put("valid_output_count", completed);
            report.put("status", completed == CASES.length ? "segmentation_outputs_created"
                    : completed > 0 ? "partial_outputs" : "no_valid_outputs");
            emit("已生成 " + completed + " 张有效数值前景，需检查实际轮廓。");
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

    private void process(SubjectSegmenter segmenter, String name) throws Exception {
        emit("正在处理：" + name);
        Bitmap source;
        String inputSha;
        try (InputStream stream = context.getAssets().open(name + ".jpg")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int length;
            while ((length = stream.read(buffer)) != -1) bytes.write(buffer, 0, length);
            byte[] encoded = bytes.toByteArray();
            inputSha = hex(MessageDigest.getInstance("SHA-256").digest(encoded));
            source = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
        }
        if (source == null) throw new IllegalStateException("invalid image " + name);
        int originalWidth = source.getWidth(), originalHeight = source.getHeight();
        float scale = Math.min(1f, 1024f / Math.max(originalWidth, originalHeight));
        if (scale < 1f) {
            Bitmap resized = Bitmap.createScaledBitmap(source, Math.round(originalWidth * scale),
                    Math.round(originalHeight * scale), true);
            source.recycle();
            source = resized;
        }
        writeBitmap(source, new File(output, name + "-source.png"));
        long start = SystemClock.elapsedRealtime();
        SubjectSegmentationResult result = Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)),
                120, TimeUnit.SECONDS);
        long elapsed = SystemClock.elapsedRealtime() - start;
        FloatBuffer mask = result.getForegroundConfidenceMask();
        int count = source.getWidth() * source.getHeight();
        if (mask == null || mask.remaining() != count) throw new IllegalStateException("mask dimensions " + name);
        float[] values = new float[count];
        mask.get(values);
        ByteBuffer raw = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        int nonFinite = 0, outsideUnitRange = 0, beyondRoundoff = 0;
        float minimum = Float.POSITIVE_INFINITY, maximum = Float.NEGATIVE_INFINITY;
        String firstInvalid = "";
        for (int i = 0; i < count; i++) {
            float value = values[i];
            raw.putFloat(value);
            if (!Float.isFinite(value)) {
                nonFinite++;
            } else {
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
                if (value < 0 || value > 1) outsideUnitRange++;
                // Preserve raw bytes; saturate only finite probability roundoff within one
                // fp16 ULP near 1. Larger excursions and non-finite output remain failures.
                if (value < -0.001f || value > 1.001f) beyondRoundoff++;
            }
            if (firstInvalid.isEmpty() && (!Float.isFinite(value) || value < 0 || value > 1)) {
                firstInvalid = i + ":" + value;
            }
        }
        try (FileOutputStream stream = new FileOutputStream(new File(output, name + ".f32"))) {
            stream.write(raw.array());
        }
        JSONObject item = new JSONObject();
        item.put("name", name);
        item.put("input_sha256", inputSha);
        item.put("original_width", originalWidth);
        item.put("original_height", originalHeight);
        item.put("width", source.getWidth());
        item.put("height", source.getHeight());
        item.put("inference_ms", elapsed);
        item.put("raw_mask_minimum", String.valueOf(minimum));
        item.put("raw_mask_maximum", String.valueOf(maximum));
        item.put("non_finite_count", nonFinite);
        item.put("outside_unit_range_count", outsideUnitRange);
        item.put("beyond_roundoff_count", beyondRoundoff);
        item.put("first_outside_unit_range", firstInvalid);
        item.put("mask_sha256", hex(MessageDigest.getInstance("SHA-256").digest(raw.array())));
        report.getJSONArray("cases").put(item);
        if (nonFinite > 0 || beyondRoundoff > 0) {
            item.put("status", "invalid_confidence");
            save();
            source.recycle();
            throw new IllegalStateException("invalid confidence " + name + "; min=" + minimum
                    + "; max=" + maximum + "; nonFinite=" + nonFinite + "; beyondRoundoff=" + beyondRoundoff);
        }
        int[] colors = new int[count];
        source.getPixels(colors, 0, source.getWidth(), 0, 0, source.getWidth(), source.getHeight());
        ByteBuffer binary = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        int solid = 0;
        for (int i = 0; i < count; i++) {
            float confidence = Math.max(0, Math.min(1, values[i]));
            binary.putFloat(confidence);
            int alpha = Math.round(confidence * 255);
            if (confidence >= 0.85f) solid++;
            colors[i] = (colors[i] & 0x00ffffff) | (alpha << 24);
        }
        try (FileOutputStream stream = new FileOutputStream(new File(output, name + "-clamped.f32"))) {
            stream.write(binary.array());
        }
        Bitmap foreground = Bitmap.createBitmap(colors, source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        writeBitmap(foreground, new File(output, name + "-foreground.png"));
        item.put("status", "segmentation_output_created");
        item.put("solid_fraction", (double) solid / count);
        item.put("java_heap_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        item.put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize());
        item.put("app_pss_kib", Debug.getPss());
        item.put("quality_mask_sha256", hex(MessageDigest.getInstance("SHA-256").digest(binary.array())));
        save();
        foreground.recycle();
        source.recycle();
        emit(name + " 已生成，耗时 " + elapsed + "ms");
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
