package com.walksense.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.walksense.app.ObjectDetectionPlugin;
import com.getcapacitor.community.tts.TextToSpeechPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        registerPlugin(ObjectDetectionPlugin.class);
        registerPlugin(TextToSpeechPlugin.class);
    }
}