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
    public void onStart() {
        Bundle result=new Bundle();
        try {
            final UiAutomation ui=getUiAutomation();
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
