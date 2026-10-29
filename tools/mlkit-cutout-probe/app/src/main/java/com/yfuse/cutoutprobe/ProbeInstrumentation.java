package com.yfuse.cutoutprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import org.json.JSONObject;

public final class ProbeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        JSONObject result = new ProbeRunner(getTargetContext(), message -> {
            Bundle update = new Bundle();
            update.putString("stream", message + "\n");
            sendStatus(0, update);
        }).run();
        Bundle output = new Bundle();
        output.putString("stream", result.toString() + "\n");
        // RESULT_OK means two real diagnostic comparisons, not confidence/semantic quality acceptance.
        finish(result.optString("status").equals("diagnostic_outputs_created")
                ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
    }
}
