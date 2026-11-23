package qa.yfuse.gesture;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

public final class Driver extends Instrumentation {
    private Bundle args;
    public void onCreate(Bundle state) { super.onCreate(state); args=state; start(); }
    private void node(android.view.accessibility.AccessibilityNodeInfo n, org.xmlpull.v1.XmlSerializer xml, int depth) throws Exception {
        if(n==null||depth>70)return;
        xml.startTag(null,"node");
        android.graphics.Rect box=new android.graphics.Rect();n.getBoundsInScreen(box);
        String[] keys={"text","content-desc","class","package","bounds","enabled","checked","clickable","focusable","scrollable","state"};
        Object[] vals={n.getText(),n.getContentDescription(),n.getClassName(),n.getPackageName(),"["+box.left+","+box.top+"]["+box.right+","+box.bottom+"]",n.isEnabled(),n.isChecked(),n.isClickable(),n.isFocusable(),n.isScrollable(),android.os.Build.VERSION.SDK_INT>=30?n.getStateDescription():null};
        for(int i=0;i<keys.length;i++)xml.attribute(null,keys[i],vals[i]==null?"":vals[i].toString());
        for(int j=0;j<n.getChildCount();j++)node(n.getChild(j),xml,depth+1);
        xml.endTag(null,"node");
    }

    private android.graphics.Rect seek(android.view.accessibility.AccessibilityNodeInfo n) {
        if(n==null)return null;
        String label=String.valueOf(n.getClassName())+" "+n.getContentDescription()+" "+n.getStateDescription();
        if(label.contains("SeekBar")||label.contains("播放进度")) {
            android.graphics.Rect b=new android.graphics.Rect();n.getBoundsInScreen(b);
            if(b.width()>150 && b.height()>0)return b;
        }
        for(int i=0;i<n.getChildCount();i++){android.graphics.Rect b=seek(n.getChild(i));if(b!=null)return b;}
        return null;
    }
    private void touch(UiAutomation ui,long down,int action,int x,int y) {
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,(float)x,(float)y,0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{if(!ui.injectInputEvent(e,true))throw new IllegalStateException("touch not delivered");}finally{e.recycle();}
    }
    private int pixel(android.graphics.Bitmap b,int x,int y){int z=b.getPixel(x,y);return (android.graphics.Color.red(z)+android.graphics.Color.green(z)+android.graphics.Color.blue(z))/3;}
    private double marker(UiAutomation ui) {
        android.graphics.Bitmap original=ui.takeScreenshot();if(original==null)return -1;
        android.graphics.Bitmap b=original.getConfig()==android.graphics.Bitmap.Config.HARDWARE?original.copy(android.graphics.Bitmap.Config.ARGB_8888,false):original;
        try{
            int w=b.getWidth(),h=b.getHeight();double s=Math.min(w/320.,h/180.),left=(w-320*s)/2,top=(h-180*s)/2;
            for(int yy:new int[]{86,92,77,99}){
                int[] v=new int[18];for(int i=0;i<18;i++)v[i]=pixel(b,(int)(left+(58+12*i)*s),(int)(top+yy*s));
                int black=Math.max(v[1],Math.max(v[15],v[17])),white=Math.min(v[0],Math.min(v[2],v[16]));if(white-black<42)continue;
                int m=(black+white)/2,raw=0,p=0;for(int i=3;i<14;i++){int bit=v[i]>m?1:0;raw=raw*2+bit;p^=bit;}
                if(p==(v[14]>m?1:0))return raw/8.;
            }return -1;
        }finally{if(b!=original)b.recycle();original.recycle();}
    }

    private String playbackState(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return "";
        String state=String.valueOf(node.getStateDescription());
        if (state.contains("播放进度")) return state;
        for(int i=0;i<node.getChildCount();i++){
            String value=playbackState(node.getChild(i));
            if(!value.isEmpty())return value;
        }
        return "";
    }
    private String command(android.view.accessibility.AccessibilityNodeInfo node) {
        if(node==null)return "";
        String value=String.valueOf(node.getContentDescription());
        if(value.equals("暂停")||value.equals("Pause"))return "playing";
        if(value.equals("播放")||value.equals("Play"))return "paused";
        for(int i=0;i<node.getChildCount();i++){String s=command(node.getChild(i));if(!s.isEmpty())return s;}
        return "";
    }

    private void volume(UiAutomation ui,Bundle result)throws Exception {
        int count=Integer.parseInt(args.getString("count")),duration=Integer.parseInt(args.getString("duration"));
        int seed=Integer.parseInt(args.getString("seed"));java.util.Random random=new java.util.Random(seed);
        org.json.JSONArray rows=new org.json.JSONArray();android.graphics.Rect r=null;
        int previous=-20;
        for(int i=0;i<count;i++){
            org.json.JSONObject row=new org.json.JSONObject();rows.put(row); boolean control="instrumented-control".equals(args.getString("variant"));row.put("i",i);row.put("issued",false);
            int target;do{target=5+random.nextInt(duration-13);}while(Math.abs(target-previous)<Math.min(18,Math.max(1,(duration-14)/3)));previous=target;row.put("target",target);
            try{
                r=seek(ui.getRootInActiveWindow());
                for(int a=0;r==null&&a<3;a++){
                    android.graphics.Bitmap sc=ui.takeScreenshot();if(sc==null)break;int x=(int)(sc.getWidth()*.52),y=(int)(sc.getHeight()*.36);sc.recycle();
                    long down=SystemClock.uptimeMillis();touch(ui,down,0,x,y);touch(ui,down,1,x,y);SystemClock.sleep(280);r=seek(ui.getRootInActiveWindow());
                }
                if(r==null){row.put("reason","rail unavailable");continue;}
                row.put("before",command(ui.getRootInActiveWindow()));row.put("stateBefore",playbackState(ui.getRootInActiveWindow()));
                if(control && "paused".equals(row.optString("before"))){ui.injectInputEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_MEDIA_PLAY),true);ui.injectInputEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_MEDIA_PLAY),true);SystemClock.sleep(250);}
                int y=r.centerY(),end=r.left+(int)Math.round((r.width()-1)*(target/(double)duration)),from=r.left+(int)(r.width()*(target>duration/2?.17:.81));
                long down=SystemClock.uptimeMillis();touch(ui,down,0,from,y);
                for(int m=1;m<=7;m++){SystemClock.sleep(19);touch(ui,down,2,from+(end-from)*m/7,y);}touch(ui,down,1,end,y);
                row.put("issued",true);row.put("releasedAt",System.currentTimeMillis());row.put("bounds",r.flattenToString());org.json.JSONArray samples=new org.json.JSONArray();row.put("samples",samples);long readAt=-900;long begin=SystemClock.uptimeMillis(),firstT=0;double first=-1;boolean ok=false;
                while(SystemClock.uptimeMillis()-begin<9500){
                    double pos=marker(ui),elapsed=(SystemClock.uptimeMillis()-begin)/1000.;
                    if(SystemClock.uptimeMillis()-begin-readAt>=470){org.json.JSONArray v=new org.json.JSONArray();v.put(Math.round(elapsed*1000));v.put(pos);samples.put(v);readAt=SystemClock.uptimeMillis()-begin;}
                    if(pos>=target-2.1 && pos<=target+elapsed+1.8){
                        if(first<0||pos<first){first=pos;firstT=SystemClock.uptimeMillis();}
                        else if(pos>=first+.375 && pos-first<=(SystemClock.uptimeMillis()-firstT)/1000.+1.75){
                            row.put("first",first);row.put("later",pos);row.put("ms",SystemClock.uptimeMillis()-begin);ok=true;break;}
                    } SystemClock.sleep(105);
                }
                row.put("ok",ok);if(!ok){row.put("reason","target or advancing frame not verified");row.put("after",command(ui.getRootInActiveWindow()));row.put("stateAfter",playbackState(ui.getRootInActiveWindow()));row.put("endPTS",marker(ui));}
            }catch(Exception e){row.put("reason",e.getClass().getSimpleName());}
        }
        result.putString("rows",android.util.Base64.encodeToString(rows.toString().getBytes("UTF-8"),android.util.Base64.NO_WRAP));finish(-1,result);
    }

    public void onStart() {
        Bundle result=new Bundle();
        try {
            final UiAutomation ui=getUiAutomation();
            if("volume".equals(args.getString("mode"))){volume(ui,result);return;}
            if("tree".equals(args.getString("mode"))) {
                android.view.accessibility.AccessibilityNodeInfo root=null;
                for(int attempt=0;attempt<10 && root==null;attempt++){root=ui.getRootInActiveWindow(); if(root==null)SystemClock.sleep(100);}
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                org.xmlpull.v1.XmlSerializer xml=android.util.Xml.newSerializer();xml.setOutput(bytes,"UTF-8");xml.startDocument("UTF-8",true);xml.startTag(null,"hierarchy");
                node(root,xml,0);xml.endTag(null,"hierarchy");xml.endDocument();
                result.putString("tree",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP));finish(-1,result);return;
            }
            final int x=Integer.parseInt(args.getString("x")), end=Integer.parseInt(args.getString("end"));
            final int y=Integer.parseInt(args.getString("y")), retained=Integer.parseInt(args.getString("moves","7"));
            long down=SystemClock.uptimeMillis();
            for(int i=0;i<=retained+1;i++) {
                int action=i==0?MotionEvent.ACTION_DOWN:i==retained+1?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
                int px=action==MotionEvent.ACTION_UP?end:x+(end-x)*i/12;
                MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,(float)px,(float)y,0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try {if(!ui.injectInputEvent(event,true))throw new IllegalStateException("input rejected");}
                finally {event.recycle();}
                SystemClock.sleep(14);
            }
            result.putString("outcome","injected");finish(-1,result);
        } catch(Exception exception) { result.putString("outcome",exception.toString());finish(0,result); }
    }
}
