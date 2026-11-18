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
    private android.view.accessibility.AccessibilityNodeInfo editableAt(
            android.view.accessibility.AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        android.graphics.Rect box = new android.graphics.Rect(); node.getBoundsInScreen(box);
        if (node.isEditable() && node.isEnabled() && node.isVisibleToUser() && box.contains(x, y)
                && "com.yfuse".equals(String.valueOf(node.getPackageName()))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = editableAt(node.getChild(i), x, y);
            if (found != null) return found;
        }
        return null;
    }
    private void node(android.view.accessibility.AccessibilityNodeInfo n, org.xmlpull.v1.XmlSerializer xml, int depth) throws Exception {
        if(n==null||depth>70)return;
        if(!n.refresh())return;
        xml.startTag(null,"node");
        android.graphics.Rect box=new android.graphics.Rect();n.getBoundsInScreen(box);
        String[] keys={"text","content-desc","class","package","bounds","enabled","checked","clickable","focusable","scrollable","state"};
        Object[] vals={n.getText(),n.getContentDescription(),n.getClassName(),n.getPackageName(),"["+box.left+","+box.top+"]["+box.right+","+box.bottom+"]",n.isEnabled(),n.isChecked(),n.isClickable(),n.isFocusable(),n.isScrollable(),android.os.Build.VERSION.SDK_INT>=30?n.getStateDescription():null};
        for(int i=0;i<keys.length;i++)xml.attribute(null,keys[i],vals[i]==null?"":vals[i].toString());
        xml.attribute(null,"visible",Boolean.toString(n.isVisibleToUser()));
        xml.attribute(null,"selected",Boolean.toString(n.isSelected()));
        xml.attribute(null,"focused",Boolean.toString(n.isFocused()));
        boolean horizontal=false,vertical=false,down=false;
        for(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction action:n.getActionList()) {
            int id=action.getId();
            horizontal|=id==android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()
                    ||id==android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
            down|=id==android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
            vertical|=id==android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId() || down;
        }
        xml.attribute(null,"scroll-horizontal",Boolean.toString(horizontal));
        xml.attribute(null,"scroll-vertical",Boolean.toString(vertical));
        if(vertical)xml.attribute(null,"scroll-down",Boolean.toString(down));
        for(int j=0;j<n.getChildCount();j++)node(n.getChild(j),xml,depth+1);
        xml.endTag(null,"node");
    }

    private android.graphics.Rect seek(android.view.accessibility.AccessibilityNodeInfo n) {
        if(n==null)return null;
        String label=String.valueOf(n.getClassName())+" "+n.getContentDescription()+" "+(android.os.Build.VERSION.SDK_INT>=30?n.getStateDescription():"");
        if(label.contains("SeekBar")||label.contains("播放进度")) {
            if(!n.refresh() || !n.isVisibleToUser())return null;
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
        String state=String.valueOf(android.os.Build.VERSION.SDK_INT>=30?node.getStateDescription():node.getContentDescription());
        if (state.contains("播放进度") || String.valueOf(node.getClassName()).contains("SeekBar")) {
            if (!node.refresh()) return "";
            return String.valueOf(android.os.Build.VERSION.SDK_INT>=30?node.getStateDescription():node.getContentDescription());
        }
        for(int i=0;i<node.getChildCount();i++){
            String value=playbackState(node.getChild(i));
            if(!value.isEmpty())return value;
        }
        return "";
    }
    private String command(android.view.accessibility.AccessibilityNodeInfo node) {
        if(node==null)return "";
        String value=String.valueOf(node.getContentDescription());
        if(value.equals("暂停")||value.equals("Pause")||value.equals("播放")||value.equals("Play")) {
            if (!node.refresh()) return "";
            value=String.valueOf(node.getContentDescription());
            if(value.equals("暂停")||value.equals("Pause"))return "playing";
            if(value.equals("播放")||value.equals("Play"))return "paused";
        }
        for(int i=0;i<node.getChildCount();i++){String s=command(node.getChild(i));if(!s.isEmpty())return s;}
        return "";
    }

    private void perform(UiAutomation ui, Bundle result) throws Exception {
        String label=args.getString("label","");
        android.view.accessibility.AccessibilityNodeInfo root=ui.getRootInActiveWindow();
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes=root==null?java.util.Collections.emptyList():root.findAccessibilityNodeInfosByText(label);
        for(android.view.accessibility.AccessibilityNodeInfo n:nodes){
            if(!label.equals(String.valueOf(n.getText()))&&!label.equals(String.valueOf(n.getContentDescription())))continue;
            android.graphics.Rect box=new android.graphics.Rect(); n.getBoundsInScreen(box);
            if(box.isEmpty())continue;
            long down=SystemClock.uptimeMillis();touch(ui,down,0,box.centerX(),box.centerY());touch(ui,down,1,box.centerX(),box.centerY());
            result.putString("label","found");return;
        }
        result.putString("label","absent");
    }
    private android.view.accessibility.AccessibilityNodeInfo exact(android.view.accessibility.AccessibilityNodeInfo root,String... labels) {
        if(root==null)return null;
        for(String label:labels)if(label.equals(String.valueOf(root.getContentDescription())) || label.equals(String.valueOf(root.getText())))return root;
        for(int i=0;i<root.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo found=exact(root.getChild(i),labels);if(found!=null)return found;}
        return null;
    }
    private boolean accessibleClick(android.view.accessibility.AccessibilityNodeInfo node) {
        for(android.view.accessibility.AccessibilityNodeInfo n=node;n!=null;n=n.getParent()){
            if(n.isClickable() && n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))return true;
        }
        return false;
    }
    private android.graphics.Rect readyRail(UiAutomation ui) {
        android.graphics.Rect previous=null;
        long start=SystemClock.uptimeMillis(), nextReveal=0;
        int stable=0;
        while(SystemClock.uptimeMillis()-start<4400){
            android.view.accessibility.AccessibilityNodeInfo root=ui.getRootInActiveWindow();
            android.graphics.Rect r=seek(root);
            if(r!=null && r.width()>450){
                if(previous!=null && Math.abs(r.left-previous.left)<=2 && Math.abs(r.right-previous.right)<=2 && Math.abs(r.centerY()-previous.centerY())<=2){
                    if(++stable>=2)return r;
                }else stable=0;
                previous=r;
            }else{
                previous=null;stable=0;
                if(SystemClock.uptimeMillis()>=nextReveal){
                    android.view.accessibility.AccessibilityNodeInfo picture=exact(root,"播放画面");
                    if(!accessibleClick(picture)){
                        android.graphics.Bitmap sc=ui.takeScreenshot();
                        if(sc!=null){int x=(int)(sc.getWidth()*.18),y=(int)(sc.getHeight()*.33);sc.recycle();long down=SystemClock.uptimeMillis();touch(ui,down,0,x,y);touch(ui,down,1,x,y);}
                    }
                    nextReveal=SystemClock.uptimeMillis()+1050;
                }
            }
            SystemClock.sleep(120);
        }
        return null;
    }
    private void volume(UiAutomation ui,Bundle result)throws Exception {
        // A new instrumentation connection can initially retain the previous control tree.
        // Wait for UI events, then refresh the rail before measuring its current bounds.
        try{ui.waitForIdle(250,2500);}catch(java.util.concurrent.TimeoutException ignored){}
        int count=Integer.parseInt(args.getString("count")),duration=Integer.parseInt(args.getString("duration"));
        int seed=Integer.parseInt(args.getString("seed"));java.util.Random random=new java.util.Random(seed);
        org.json.JSONArray rows=new org.json.JSONArray();android.graphics.Rect r=null;
        int previous=-20;
        for(int i=0;i<count;i++){
            org.json.JSONObject row=new org.json.JSONObject();rows.put(row); boolean control="instrumented-control".equals(args.getString("variant"));row.put("i",i);row.put("issued",false);
            int target;do{target=5+random.nextInt(duration-13);}while(Math.abs(target-previous)<Math.min(18,duration/4));previous=target;row.put("target",target);
            try{
                r=readyRail(ui);
                if(r==null){row.put("reason","rail unavailable");continue;}
                String before=command(ui.getRootInActiveWindow());row.put("before",before);
                if("paused".equals(before)){
                    boolean resumed=accessibleClick(exact(ui.getRootInActiveWindow(),"播放","Play"));
                    row.put("requestedResume",resumed);
                    if(resumed){SystemClock.sleep(450);r=readyRail(ui);}
                    if(r==null){row.put("reason","rail unavailable after resume");continue;}
                }
                row.put("stateBefore",playbackState(ui.getRootInActiveWindow()));
                long a=SystemClock.uptimeMillis();
                double firstMark=marker(ui);
                boolean advancing=false;
                while(SystemClock.uptimeMillis()-a<1400){
                    SystemClock.sleep(130);double m=marker(ui);
                    if(m>=0 && firstMark>=0 && m-firstMark>=.125 && m-firstMark<4){advancing=true;break;}
                    if(m>=0)firstMark=m;
                }
                row.put("advancingBefore",advancing);
                r=readyRail(ui);if(r==null){row.put("reason","rail disappeared before touch");continue;}
                int y=r.centerY(),end=r.left+(int)Math.round((r.width()-1)*(target/(double)duration)),from=r.left+(int)(r.width()*(target>duration/2?.17:.81));
                long down=SystemClock.uptimeMillis();touch(ui,down,0,from,y);
                for(int m=1;m<=7;m++){SystemClock.sleep(19);touch(ui,down,2,from+(end-from)*m/7,y);}touch(ui,down,1,end,y);
                row.put("issued",true);row.put("releasedAt",System.currentTimeMillis());row.put("bounds",r.flattenToString());org.json.JSONArray samples=new org.json.JSONArray();row.put("samples",samples);long readAt=-900;long begin=SystemClock.uptimeMillis();double[] anchor={-1,0,0};boolean ok=false;
                while(SystemClock.uptimeMillis()-begin<9500){
                    double pos=marker(ui),elapsed=(SystemClock.uptimeMillis()-begin)/1000.;
                    if(SystemClock.uptimeMillis()-begin-readAt>=470){org.json.JSONArray v=new org.json.JSONArray();v.put(Math.round(elapsed*1000));v.put(pos);samples.put(v);readAt=SystemClock.uptimeMillis()-begin;}
                    if(verifiedProgress(anchor,pos,Math.round(elapsed*1000),target)){
                        row.put("first",anchor[0]);row.put("later",pos);row.put("ms",SystemClock.uptimeMillis()-begin);ok=true;break;
                    } SystemClock.sleep(105);
                }
                row.put("anchorPTS",anchor[0]);row.put("anchorElapsedMs",anchor[1]);row.put("anchorResets",anchor[2]);
                row.put("ok",ok);if(!ok){row.put("reason","target or advancing frame not verified");row.put("after",command(ui.getRootInActiveWindow()));row.put("stateAfter",playbackState(ui.getRootInActiveWindow()));row.put("endPTS",marker(ui));}
            }catch(Exception e){row.put("reason",e.getClass().getSimpleName());}
            if("true".equals(args.getString("stopOnFailure")) && !row.optBoolean("ok"))break;
        }
        result.putString("rows",android.util.Base64.encodeToString(rows.toString().getBytes("UTF-8"),android.util.Base64.NO_WRAP));finish(-1,result);
    }

    static boolean verifiedProgress(double[] anchor,double pos,long elapsedMs,int target) {
        if(pos<target-2.1 || pos>target+elapsedMs/1000.+1.8)return false;
        // A seek preroll or transient marker can precede the actual landing. A discontinuity
        // starts a fresh pair; it must never count as playback or poison every later sample.
        if(anchor[0]<0 || pos<anchor[0] || pos-anchor[0]>(elapsedMs-anchor[1])/1000.+1.75){
            if(anchor[0]>=0)anchor[2]++;
            anchor[0]=pos;anchor[1]=elapsedMs;return false;
        }
        return pos>=anchor[0]+.375;
    }

    public void onStart() {
        Bundle result=new Bundle();
        try {
            final UiAutomation ui=getUiAutomation();
            if ("seekTo".equals(args.getString("mode"))) {
                int target=Integer.parseInt(args.getString("target"));
                int duration=Integer.parseInt(args.getString("duration"));
                if(target<0||target>=duration)throw new IllegalArgumentException("target outside source");
                android.graphics.Rect r=readyRail(ui);
                if(r==null)throw new IllegalStateException("Visible stable rail unavailable");
                int from=r.left+(int)(r.width()*(target>duration/2?.2:.8));
                int end=r.left+(int)(r.width()*(double)target/duration),y=r.centerY();
                long down=SystemClock.uptimeMillis();touch(ui,down,0,from,y);
                for(int i=1;i<=12;i++){SystemClock.sleep(25);touch(ui,down,2,from+(end-from)*i/12,y);}
                touch(ui,down,1,end,y);
                result.putString("outcome","seek-touch-issued");result.putInt("target",target);
                finish(-1,result);return;
            }
            if ("playerPause".equals(args.getString("mode"))) {
                if (readyRail(ui) == null) throw new IllegalStateException("Player controls unavailable");
                android.view.accessibility.AccessibilityNodeInfo root = ui.getRootInActiveWindow();
                if (!"paused".equals(command(root)) && !accessibleClick(exact(root, "暂停", "Pause")))
                    throw new IllegalStateException("Pause action unavailable");
                long deadline = SystemClock.uptimeMillis() + 6000;
                while (SystemClock.uptimeMillis() < deadline && !"paused".equals(command(ui.getRootInActiveWindow())))
                    SystemClock.sleep(100);
                if (!"paused".equals(command(ui.getRootInActiveWindow())))
                    throw new IllegalStateException("Pause state not confirmed");
                SystemClock.sleep(500);
                result.putString("firstState", playbackState(ui.getRootInActiveWindow()));
                SystemClock.sleep(2200);
                result.putString("secondState", playbackState(ui.getRootInActiveWindow()));
                result.putString("command", command(ui.getRootInActiveWindow()));
                finish(-1, result); return;
            }
            if ("setText".equals(args.getString("mode")) || "imeAction".equals(args.getString("mode"))) {
                android.view.accessibility.AccessibilityNodeInfo field = null;
                for (int attempt = 0; attempt < 30 && field == null; attempt++) {
                    field = editableAt(ui.getRootInActiveWindow(),
                            Integer.parseInt(args.getString("x")), Integer.parseInt(args.getString("y")));
                    if (field == null) SystemClock.sleep(100);
                }
                if (field == null) throw new IllegalStateException("Visible editable control unavailable");
                if ("imeAction".equals(args.getString("mode"))) {
                    if (!field.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()))
                        throw new IllegalStateException("Editor IME action rejected");
                    result.putString("outcome", "ime-submitted"); finish(-1, result); return;
                }
                Bundle text = new Bundle();
                text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        args.getString("value", ""));
                if (!field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, text))
                    throw new IllegalStateException("Editable action rejected");
                result.putString("outcome", "text-set"); finish(-1, result); return;
            }
            if("select".equals(args.getString("mode"))){perform(ui,result);finish(-1,result);return;}
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
