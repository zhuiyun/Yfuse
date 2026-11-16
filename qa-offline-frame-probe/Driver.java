package qa.yfuse.gesture;
import android.app.Instrumentation;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.media.MediaCodecList;
import android.media.MediaCodecInfo;
import java.io.File;
import java.io.EOFException;
import java.nio.file.Files;
import java.util.Arrays;
import org.json.JSONObject;
import org.json.JSONArray;
public final class Driver extends Instrumentation {
  public void onCreate(Bundle args){super.onCreate(args);start();}
  static class ReadOnly extends MediaDataSource {
    byte[] data; int extent,reads,miss;long first=-1;
    ReadOnly(byte[] b,int limit){data=b;extent=limit;}
    public long getSize(){return data.length;}
    public void close(){}
    public int readAt(long off,byte[] dst,int dstOff,int size)throws java.io.IOException{
      if(off<0||off>=data.length)return -1;reads++;
      int n=(int)Math.min((long)size,data.length-off);
      if(off+n>extent){miss++;if(first<0)first=off;throw new EOFException();}
      System.arraycopy(data,(int)off,dst,dstOff,n);return n;
    }
  }
  public void onStart(){Bundle buf=new Bundle();
    try{File root=getTargetContext().getFilesDir();File file=new File(root,"media");
      byte[] bytes=Files.readAllBytes(file.toPath());int prefix=Integer.parseInt(new String(Files.readAllBytes(new File(root,"limit").toPath())).trim());
      JSONArray rows=new JSONArray();
      for(int cap:new int[]{-1,bytes.length,prefix})for(int time:new int[]{10,12,143}){
        JSONObject row=new JSONObject();rows.put(row);row.put("extent",cap);row.put("seconds",time);
        ReadOnly input=new ReadOnly(bytes,cap);MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{if(cap==-1)retriever.setDataSource(file.toString());else retriever.setDataSource(input);
          row.put("duration",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
          Bitmap frame=retriever.getScaledFrameAtTime(time*1000000L,MediaMetadataRetriever.OPTION_CLOSEST,320,180);
          row.put("image",frame!=null);if(frame!=null)frame.recycle();
        }catch(Exception e){row.put("exception",e.getClass().getSimpleName());}finally{retriever.release();}
        row.put("reads",input.reads);row.put("miss",input.miss);row.put("firstMissing",input.first);
      }
      JSONArray codecs=new JSONArray();
      for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos())if(!info.isEncoder()&&Arrays.asList(info.getSupportedTypes()).contains("video/avc"))codecs.put(info.getName());
      JSONObject report=new JSONObject();report.put("rows",rows);report.put("avcDecoders",codecs);
      buf.putString("receipt",report.toString());finish(-1,buf);
    }catch(Exception e){buf.putString("failure",e.getClass().getSimpleName());finish(0,buf);}
  }
}
