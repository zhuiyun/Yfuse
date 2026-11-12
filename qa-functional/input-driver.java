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
    public void onStart() {
        Bundle result=new Bundle();
        try {
            final UiAutomation ui=getUiAutomation();
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
