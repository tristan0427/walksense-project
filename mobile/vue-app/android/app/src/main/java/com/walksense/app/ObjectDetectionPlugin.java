package com.walksense.app;

import android.graphics.Bitmap;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.List;

@CapacitorPlugin(name = "ObjectDetection")
public class ObjectDetectionPlugin extends Plugin {

    private static final String TAG = "ObjectDetection";
    private static final int INPUT_SIZE = 640;

    private Interpreter tflite;
    private ESP32StreamService streamService;
    private Bitmap latestFrame;
    private final Object frameLock = new Object(); // Lock for thread safety
    private boolean isModelLoaded = false;
    private boolean firstFrameReceived = false;

    // ===== Distance & Direction =====

    private String estimateDistance(float bboxArea, float frameArea) {
        float ratio = bboxArea / frameArea;
        if (ratio > 0.30f) return "very close";
        else if (ratio > 0.15f) return "close";
        else if (ratio > 0.05f) return "medium distance";
        else return "far";
    }

    private String getDirection(float centerX, int frameWidth) {
        float leftThird = frameWidth / 3.0f;
        float rightThird = (frameWidth * 2.0f) / 3.0f;

        if (centerX < leftThird) return "left side";
        else if (centerX > rightThird) return "right side";
        else return "ahead";
    }

    // ===== Model Loading =====

    @PluginMethod
    public void loadModel(PluginCall call) {
        try {
            Log.d(TAG, "Loading TFLite model...");

            MappedByteBuffer model = FileUtil.loadMappedFile(
                    getContext(),
                    "best_float16.tflite"
            );

            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);

            tflite = new Interpreter(model, options);
            isModelLoaded = true;

            Log.d(TAG, "✓ Model loaded");

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e(TAG, "Model load failed", e);
            call.reject("Failed to load model: " + e.getMessage());
        }
    }

    // ===== ESP32 Stream =====

    @PluginMethod
    public void startESP32Stream(PluginCall call) {
        Log.d(TAG, "Starting ESP32 stream...");

        firstFrameReceived = false; // reset in case stream restarts
        streamService = new ESP32StreamService();

        streamService.startStream(new ESP32StreamService.StreamCallback() {

            @Override
            public void onFrameReceived(Bitmap frame) {
                synchronized (frameLock) {
                    // Create a safe copy of the frame
                    Bitmap frameCopy = frame.copy(Bitmap.Config.ARGB_8888, false);

                    // Recycle old frame if it exists
                    if (latestFrame != null && !latestFrame.isRecycled()) {
                        latestFrame.recycle();
                    }

                    // Store the copy
                    latestFrame = frameCopy;

                    // Recycle the original frame if it's different from the copy
                    if (frame != frameCopy && !frame.isRecycled()) {
                        frame.recycle();
                    }
                }

                // ✅ Notify Vue only after the FIRST real frame arrives
                if (!firstFrameReceived) {
                    firstFrameReceived = true;
                    Log.d(TAG, "✓ First frame received — notifying Vue");
                    JSObject ret = new JSObject();
                    ret.put("status", "connected");
                    notifyListeners("streamConnected", ret);
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Stream error: " + error);
                JSObject ret = new JSObject();
                ret.put("error", error);
                notifyListeners("streamError", ret);
            }

            @Override
            public void onConnected() {
                // ✅ HTTP connected but no frame yet — do NOT notify Vue here
                // Vue will be notified only when first frame arrives in onFrameReceived
                Log.d(TAG, "HTTP connected, waiting for first frame...");
            }
        });

        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    // ===== Detection =====

    @PluginMethod
    public void detectFromStream(PluginCall call) {
        if (!isModelLoaded) {
            call.reject("Model not loaded");
            return;
        }

        Bitmap frameToProcess = null;

        // Safely get a copy of the latest frame
        synchronized (frameLock) {
            if (latestFrame == null || latestFrame.isRecycled()) {
                call.reject("No valid frame available");
                return;
            }
            // Create a mutable copy for processing
            frameToProcess = Bitmap.createBitmap(latestFrame);
        }

        try {
            // Resize frame
            Bitmap resized = Bitmap.createScaledBitmap(
                    frameToProcess, INPUT_SIZE, INPUT_SIZE, true
            );

            // Convert to ByteBuffer
            ByteBuffer inputBuffer = convertBitmapToByteBuffer(resized);

            // Run inference — output shape: [1][8400][22] for 18 classes (0-17)
            float[][][] output = new float[1][22][8400];
            tflite.run(inputBuffer, output);

            // Post-process
            List<Detection> detections = postProcessTransposed(output[0], 0.6f);

            // Find nearest
            Detection nearest = findNearestObject(detections);

            JSObject ret = new JSObject();

            if (nearest != null) {
                float bboxArea = (nearest.x2 - nearest.x1) * (nearest.y2 - nearest.y1);
                float frameArea = INPUT_SIZE * INPUT_SIZE;

                String distance = estimateDistance(bboxArea, frameArea);
                String direction = getDirection((nearest.x1 + nearest.x2) / 2, INPUT_SIZE);

                JSObject nearestObj = new JSObject();
                nearestObj.put("class", nearest.className);
                nearestObj.put("distance", distance);
                nearestObj.put("direction", direction);
                nearestObj.put("confidence", nearest.confidence);

                ret.put("nearest", nearestObj);
            }

            ret.put("success", true);
            call.resolve(ret);

            // Clean up bitmaps
            frameToProcess.recycle();
            resized.recycle();

        } catch (Exception e) {
            Log.e(TAG, "Detection failed", e);
            if (frameToProcess != null && !frameToProcess.isRecycled()) {
                frameToProcess.recycle();
            }
            call.reject("Detection error: " + e.getMessage());
        }
    }

    // ===== Helper Methods =====

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        int pixel = 0;
        for (int i = 0; i < INPUT_SIZE; ++i) {
            for (int j = 0; j < INPUT_SIZE; ++j) {
                final int val = intValues[pixel++];
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
                byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);
                byteBuffer.putFloat((val & 0xFF) / 255.0f);
            }
        }

        return byteBuffer;
    }

    private List<Detection> postProcessTransposed(float[][] output, float threshold) {
        List<Detection> detections = new ArrayList<>();

        int numDetections = output[0].length; // 8400

        for (int i = 0; i < numDetections; i++) {
            // Extract bbox (first 4 rows)
            float cx = output[0][i];
            float cy = output[1][i];
            float w = output[2][i];
            float h = output[3][i];


            float objectConfidence = output[4][i];

            if (objectConfidence < threshold) continue;


            int classId = 0;
            float maxClassScore = output[5][i];
            for (int j = 6; j < 22; j++) {
                if (output[j][i] > maxClassScore) {
                    maxClassScore = output[j][i];
                    classId = j - 5;
                }
            }

            float confidence = objectConfidence * maxClassScore;
            if (confidence < threshold) continue;


            float x1 = (cx - w / 2) * INPUT_SIZE;
            float y1 = (cy - h / 2) * INPUT_SIZE;
            float x2 = (cx + w / 2) * INPUT_SIZE;
            float y2 = (cy + h / 2) * INPUT_SIZE;


            x1 = Math.max(0, Math.min(INPUT_SIZE - 1, x1));
            y1 = Math.max(0, Math.min(INPUT_SIZE - 1, y1));
            x2 = Math.max(0, Math.min(INPUT_SIZE - 1, x2));
            y2 = Math.max(0, Math.min(INPUT_SIZE - 1, y2));

            Detection det = new Detection();
            det.className = getClassName(classId);
            det.confidence = confidence;
            det.x1 = x1;
            det.y1 = y1;
            det.x2 = x2;
            det.y2 = y2;

            detections.add(det);
        }

        return detections;
    }
    private Detection findNearestObject(List<Detection> detections) {
        if (detections.isEmpty()) return null;

        Detection nearest = null;
        float maxArea = 0;

        for (Detection det : detections) {
            float area = (det.x2 - det.x1) * (det.y2 - det.y1);
            if (area > maxArea) {
                maxArea = area;
                nearest = det;
            }
        }

        return nearest;
    }

    private String getClassName(int classId) {
        String[] classes = {
                "person",      // 0
                "bird",        // 1
                "car",         // 2
                "cat",         // 3
                "chair",       // 4
                "dog",         // 5
                "table",       // 6
                "pothole",     // 7
                "ceiling",     // 8
                "wall",        // 9
                "window",      // 10
                "bollard",     // 11
                "crosswalk",   // 12
                "downstairs",  // 13
                "upstairs",    // 14
                "door",        // 15
                "stair",       // 16
                "pole"         // 17
        };
        if (classId >= 0 && classId < classes.length) return classes[classId];
        return "obstacle";
    }

    // ===== Cleanup =====

    @PluginMethod
    public void stopESP32Stream(PluginCall call) {
        if (streamService != null) {
            streamService.stopStream();
        }

        synchronized (frameLock) {
            if (latestFrame != null && !latestFrame.isRecycled()) {
                latestFrame.recycle();
                latestFrame = null;
            }
        }

        firstFrameReceived = false;
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void unloadModel(PluginCall call) {
        if (tflite != null) {
            tflite.close();
            isModelLoaded = false;
        }

        // Also clean up any remaining frames
        synchronized (frameLock) {
            if (latestFrame != null && !latestFrame.isRecycled()) {
                latestFrame.recycle();
                latestFrame = null;
            }
        }

        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    // ===== Detection Class =====

    private static class Detection {
        String className;
        float confidence;
        float x1, y1, x2, y2;
    }
}