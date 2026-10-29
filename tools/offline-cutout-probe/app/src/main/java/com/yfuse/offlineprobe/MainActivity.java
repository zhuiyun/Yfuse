package com.yfuse.offlineprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class MainActivity extends Activity {
    private TextView status;
    private Spinner choices;
    private LinearLayout previews;
    private JSONArray models;
    private Button builtIn, pick;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 64, 32, 48); scroll.addView(root); setContentView(scroll);
        TextView title = new TextView(this); title.setText("双模型离线验证 0.0.2"); title.setTextSize(23); root.addView(title);
        TextView info = new TextView(this); info.setText("模型和六张海报已内置，可在飞行模式下测试。无网络权限。\n真实输出仅供检查，不会自动判为可用素材。\n比较峰值内存时，请重启 App 后单独选择一个模型；全部模式的峰值会累积。\n导出 ZIP 包含原图、透明前景、原始蒙版、耗时与内存记录。"); root.addView(info);
        choices = new Spinner(this); root.addView(choices);
        try {
            models = ProbeRunner.manifest(this).getJSONArray("models"); List<String> names = new ArrayList<>(); names.add("全部模型（依次运行）");
            for (int i = 0; i < models.length(); i++) names.add(models.getJSONObject(i).getString("title"));
            choices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        builtIn = button(root, "测试六张内置海报", v -> runSamples(null));
        pick = button(root, "选择本地图片测试（最多 8 张）", v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); startActivityForResult(intent, 10);
        });
        button(root, "导出报告 ZIP", v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "offline-cutout-report.zip"), 11));
        status = new TextView(this); status.setText("就绪；建议先测试内置海报。"); root.addView(status);
        previews = new LinearLayout(this); previews.setOrientation(LinearLayout.VERTICAL); root.addView(previews);
    }
    private Button button(LinearLayout root, String text, View.OnClickListener listener) { Button button = new Button(this); button.setText(text); button.setOnClickListener(listener); root.addView(button); return button; }
    private void update(String text) { runOnUiThread(() -> status.setText(text)); }
    private void runSamples(List<Uri> uris) {
        int choice = choices.getSelectedItemPosition(); builtIn.setEnabled(false); pick.setEnabled(false); choices.setEnabled(false); previews.removeAllViews();
        new Thread(() -> {
            List<ProbeRunner.Sample> samples = new ArrayList<>();
            try {
                if (uris == null) samples = ProbeRunner.builtIns(this);
                else for (int i = 0; i < uris.size(); i++) {
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                    try (InputStream input = getContentResolver().openInputStream(uris.get(i))) { BitmapFactory.decodeStream(input, null, options); }
                    options.inJustDecodeBounds = false; options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 3200) options.inSampleSize *= 2;
                    try (InputStream input = getContentResolver().openInputStream(uris.get(i))) { samples.add(new ProbeRunner.Sample("local-" + i, "本地图片 " + (i + 1), ProbeRunner.limit(BitmapFactory.decodeStream(input, null, options)))); }
                }
                StringBuilder summary = new StringBuilder();
                for (int i = 0; i < models.length(); i++) if (choice == 0 || choice == i + 1) {
                    JSONObject spec = models.getJSONObject(i);
                    JSONObject report = ProbeRunner.run(this, spec.getString("id"), samples, this::update);
                    String label = spec.getString("title") + "：" + report.getString("status");
                    summary.append(label).append('\n'); final String modelId = spec.getString("id"); final String modelLabel = label;
                    runOnUiThread(() -> addPreviews(modelId, modelLabel));
                    System.gc();
                }
                update(summary + "\n完成。可导出报告；手机数据与模拟器数据请分别比较。");
            } catch (Throwable failure) { update("测试失败：" + failure); }
            finally { for (ProbeRunner.Sample sample : samples) sample.bitmap().recycle(); runOnUiThread(() -> { builtIn.setEnabled(true); pick.setEnabled(true); choices.setEnabled(true); }); }
        }, "offline-probe").start();
    }
    private void addPreviews(String model, String text) {
        TextView label = new TextView(this); label.setText(text); previews.addView(label);
        File[] files = new File(ProbeRunner.out(this), model).listFiles((d, name) -> name.endsWith("-foreground.png"));
        if (files == null) return; Arrays.sort(files);
        for (int i = 0; i < Math.min(2, files.length); i++) {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 4;
            ImageView image = new ImageView(this); image.setBackgroundColor(0xffc7cbd2); image.setAdjustViewBounds(true); image.setImageBitmap(BitmapFactory.decodeFile(files[i].getAbsolutePath(), options)); previews.addView(image);
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null) return;
        if (request == 10) {
            List<Uri> uris = new ArrayList<>(); if (data.getClipData() != null) { for (int i = 0; i < Math.min(8, data.getClipData().getItemCount()); i++) uris.add(data.getClipData().getItemAt(i).getUri()); } else if (data.getData() != null) uris.add(data.getData()); runSamples(uris);
        } else if (request == 11 && data.getData() != null) {
            Uri target = data.getData(); new Thread(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(target); ZipOutputStream zip = new ZipOutputStream(output)) { addZip(zip, ProbeRunner.out(this), ""); update("报告已导出。"); }
                catch (Exception failure) { update("导出失败：" + failure); }
            }).start();
        }
    }
    private static void addZip(ZipOutputStream zip, File directory, String prefix) throws Exception {
        File[] files = directory.listFiles(); if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) addZip(zip, file, prefix + file.getName() + "/");
            else { zip.putNextEntry(new ZipEntry(prefix + file.getName())); try (InputStream input = new FileInputStream(file)) { byte[] buffer = new byte[65536]; for (int n; (n = input.read(buffer)) != -1;) zip.write(buffer, 0, n); } zip.closeEntry(); }
        }
    }
}
