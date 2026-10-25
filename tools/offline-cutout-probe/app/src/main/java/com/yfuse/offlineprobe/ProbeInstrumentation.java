package com.yfuse.offlineprobe;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;

public final class ProbeInstrumentation extends Instrumentation {
    private String model;
    @Override public void onCreate(Bundle args) { super.onCreate(args); model = args.getString("model", "pp-human"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            JSONObject report = ProbeRunner.run(getTargetContext(), model, ProbeRunner.builtIns(getTargetContext()), value -> android.util.Log.i("OfflineProbe", value));
            result.putString("status", report.getString("status")); finish(report.getString("status").equals("outputs_created") ? -1 : 0, result);
        } catch (Throwable failure) { result.putString("error", failure.toString()); finish(0, result); }
    }
}
