package com.yfuse.senplayer;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.PixelFormat;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Cloud diagnostic only. Uses the unchanged bridge and original enforced license manager. */
public final class ProbeActivity extends Activity implements SurfaceHolder.Callback {
  private SurfaceView video, captions;
  private boolean started;
  private final JSONArray results = new JSONArray();

  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(128);
    FrameLayout host = new FrameLayout(this);
    video = new SurfaceView(this);
    captions = new SurfaceView(this);
    captions.getHolder().setFormat(PixelFormat.TRANSLUCENT);
    captions.setZOrderMediaOverlay(true);
    host.addView(video); host.addView(captions); setContentView(host);
    video.getHolder().addCallback(this); captions.getHolder().addCallback(this);
  }
  public void surfaceChanged(SurfaceHolder h, int f, int w, int height) { start(); }
  public void surfaceCreated(SurfaceHolder h) { start(); }
  public void surfaceDestroyed(SurfaceHolder h) {}
  private void start() {
    if (started || !video.getHolder().getSurface().isValid() || !captions.getHolder().getSurface().isValid()) return;
    started = true;
    new Thread(this::probe, "SubtitleProtocolProbe").start();
  }
  private void record(String stage, Object value) throws Exception {
    results.put(new JSONObject().put("stage", stage).put("result", String.valueOf(value)));
    try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "probe.json"))) {
      output.write(results.toString(2).getBytes("UTF-8"));
    }
  }
  private static Object field(Object owner, String name) throws Exception {
    Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
  }
  private static Object await(Object result) throws Exception {
    return result instanceof CompletableFuture ? ((CompletableFuture<?>) result).get(20, TimeUnit.SECONDS) : result;
  }
  private Object command(Object player, long source, String name, String[] strings, double[] numbers) throws Exception {
    return await(player.getClass().getMethod("G", String.class, long.class, String[].class, double[].class)
        .invoke(player, name, source, strings, numbers));
  }
  private void rawConfiguration(Object player, long source, Object config) throws Exception {
    ArrayList<String> strings = new ArrayList<>(); double[] numbers = new double[28];
    String[] stringFields = {"a", "b", "c", "d", "e"};
    String[] doubleFields = {"f", "g", "h", "i", "j", "k"};
    for (int i = 0; i < 2; i++) {
      Object style = field(config, i == 0 ? "a" : "b");
      for (String f : stringFields) strings.add((String)field(style, f));
      for (int j = 0; j < doubleFields.length; j++) numbers[i*11+j] = (double)field(style, doubleFields[j]);
      numbers[i*11+7] = (boolean)field(style, "l") ? 1 : 0;
      numbers[i*11+8] = (boolean)field(style, "m") ? 1 : 0;
    }
    strings.add((String)field(config, "c")); strings.add((String)field(config, "d"));
    String[] booleans = {"e", "f", "g", "h", "i"};
    for (int i=0;i<5;i++) numbers[22+i]=(boolean)field(config,booleans[i])?1:0;
    numbers[27]=(double)field(config,"j");
    record("subtitles-command", command(player,source,"subtitles",strings.toArray(new String[0]),numbers));
    for (String[] pair : new String[][] {{"subtitle-brightness","1.0"},{"subtitle-opacity","1.0"},{"subtitle-hdr","no"}})
      record(pair[0],command(player,source,"option",pair,new double[0]));
  }
  private void probe() {
    try {
      LinkedHashMap<String,String> options = new LinkedHashMap<>();
      options.put("hwdec","no"); options.put("sub-fonts-dir","/system/fonts");
      options.put("secondary-sub-fonts-dir","/system/fonts");
      options.put("sub-font-provider","none"); options.put("secondary-sub-font-provider","none");
      SenPlayerSession session = new SenPlayerSession(this,video.getHolder().getSurface(),captions.getHolder().getSurface(),getWindowManager().getDefaultDisplay(),options);
      record("license-enforced-session", "created");
      ClassLoader loader=(ClassLoader)field(field(session,"rt"),"loader");
      Class<?> logClass=loader.loadClass("com.mlibkit.log.LogKit");
      Object log=logClass.getConstructor(File.class,boolean.class).newInstance(new File(getFilesDir(),"native-log"),true);
      loader.loadClass("pi").getField("b").set(null,log);
      Class<?> callback=loader.loadClass("oi");
      loader.loadClass("com.mplaykit.Diagnostics").getMethod("b",callback)
          .invoke(null,callback.getConstructor(int.class).newInstance(0));
      session.resize(video.getWidth(),video.getHeight());
      session.open(getIntent().getStringExtra("url"),new LinkedHashMap<>(),0,true);
      Thread.sleep(4000);
      record("media", session.media());
      try {
        session.configureSubtitles(false,1,1,0,0,"#FFFFFFFF","#000000FF","#00000000",2,false);
        record("bridge-configuration","accepted");
      } catch (Exception e) { record("bridge-configuration",e.getClass().getSimpleName()+": "+e.getMessage()); }
      Object player=field(session,"player"); long source=(long)field(session,"source");
      Object config=SenPlayerSession.newSubtitleConfiguration(loader);
      record("original-factory-M",await(player.getClass().getMethod("M",config.getClass(),long.class).invoke(player,config,source)));
      rawConfiguration(player,source,config);
      for (String[] pair : new String[][] {{"sub-visibility","yes"},{"sub-font","Roboto"},
          {"sub-font-size","40"},{"sub-font-provider","none"},{"sub-fonts-dir","/system/fonts"},
          {"sub-scale","1.0"},{"secondary-sub-scale","1.0"},{"sub-ass-override","no"}})
        record("direct-"+pair[0],command(player,source,"option",pair,new double[0]));
      String[][] styles={{"font","Roboto"},{"font-size","40"},{"scale","1.2"},{"delay","0.0"},
          {"color","#FFFFFFFF"},{"border-color","#000000FF"},{"back-color","#00000000"},
          {"border-size","2.0"},{"shadow-offset","1.0"},{"spacing","0.0"},
          {"italic","no"},{"bold","no"},{"ass-override","no"}};
      for (String prefix:new String[]{"sub-","secondary-sub-"})
        for(String[] pair:styles)
          record("style-"+prefix+pair[0],command(player,source,"option",new String[]{prefix+pair[0],pair[1]},new double[0]));
      Thread.sleep(1500);
      record("native-logs",logClass.getMethod("recent",int.class).invoke(log,150));
      record("done","true");
    } catch (Throwable e) {
      try { record("failure",e.getClass().getSimpleName()+": "+e.getMessage()); } catch (Exception ignored) {}
    }
  }
}
