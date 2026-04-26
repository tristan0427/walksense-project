package com.walksense.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.util.Log;

import java.io.ByteArrayOutputStream;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.gpu.CompatibilityList;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@CapacitorPlugin(name = "ObjectDetection")
public class ObjectDetectionPlugin extends Plugin {

    private static final String TAG = "ObjectDetection";
    private static final int INPUT_SIZE = 320;
    private static final float DEFAULT_CONFIDENCE = 0.38f;
    private static final int SCAN_POOL_SIZE = 20;
    private static final int MAX_SCAN_RESULTS = 2;

    // Camera type constants
    public static final String CAM_DAY   = "day";
    public static final String CAM_NIGHT = "night";

    private Interpreter tflite;
    private GpuDelegate gpuDelegate;

    // Two independent stream services
    private ESP32StreamService dayStreamService;
    private ESP32StreamService nightStreamService;

    // Two independent frame buffers
    private Bitmap latestDayFrame;
    private Bitmap latestNightFrame;
    private final Object dayFrameLock   = new Object();
    private final Object nightFrameLock = new Object();

    // Which camera is currently feeding the YOLO model
    private String activeCamera = CAM_DAY;

    private boolean isModelLoaded        = false;
    private boolean dayFirstFrame        = false;
    private boolean nightFirstFrame      = false;

    // ===== Baseline Perf Counters =====
    private final AtomicLong detectCalls = new AtomicLong(0);
    private final AtomicLong totalInferenceMs = new AtomicLong(0);
    private final AtomicLong totalDetectMs = new AtomicLong(0);
    private long metricsLastLoggedAt = 0L;

    // Guard against overlapping detectFromStream calls from JS interval ticks.
    private final Object detectLock = new Object();

    // ===== Temporal Tracking (close-proximity misclassification fix) =====
    private static final int CLASS_HISTORY_SIZE = 5;
    private final List<String> classHistory = new ArrayList<>();
    private final List<Float>  ratioHistory = new ArrayList<>();
    private static final float IMMINENT_OCCUPANCY_THRESHOLD = 0.85f;
    private static final float TEMPORAL_OVERRIDE_THRESHOLD  = 0.60f;

    // ===== Distance & Direction =====

    private String estimateDistance(float bboxArea, float frameArea) {
        float ratio = bboxArea / frameArea;
        if (ratio > 0.30f) return "very close";
        else if (ratio > 0.15f) return "close";
        else if (ratio > 0.05f) return "medium distance";
        else return "far";
    }

    // ===== Close-Proximity Detection Helpers =====

    private boolean isFrameFillingDetection(Detection det) {
        float bboxArea  = (det.x2 - det.x1) * (det.y2 - det.y1);
        float frameArea = INPUT_SIZE * INPUT_SIZE;
        return (bboxArea / frameArea) > IMMINENT_OCCUPANCY_THRESHOLD;
    }

    private String getTemporalOverrideClass(Detection det) {
        float bboxArea  = (det.x2 - det.x1) * (det.y2 - det.y1);
        float frameArea = INPUT_SIZE * INPUT_SIZE;
        float ratio     = bboxArea / frameArea;

        if ((!det.className.equals("wall") && !det.className.equals("glass wall"))
                || ratio < TEMPORAL_OVERRIDE_THRESHOLD) {
            return null;
        }
        if (classHistory.size() < 3) return null;

        Map<String, Integer> counts = new HashMap<>();
        for (String cls : classHistory) {
            if (!cls.equals("wall") && !cls.equals("glass wall") && !cls.equals("none")) {
                counts.put(cls, counts.getOrDefault(cls, 0) + 1);
            }
        }

        String dominant = null;
        int maxCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                dominant = entry.getKey();
            }
        }

        if (dominant != null && maxCount >= 3) {
            Log.d(TAG, "[Temporal] Overriding '" + det.className + "' → '" + dominant
                    + "' (appeared " + maxCount + "/" + classHistory.size() + " recent frames)");
            return dominant;
        }
        return null;
    }

    private void pushClassHistory(String className, float bboxRatio) {
        classHistory.add(className);
        ratioHistory.add(bboxRatio);
        while (classHistory.size() > CLASS_HISTORY_SIZE) {
            classHistory.remove(0);
            ratioHistory.remove(0);
        }
    }

    private String getDirection(float centerX, int frameWidth) {
        float leftThird  = frameWidth / 3.0f;
        float rightThird = (frameWidth * 2.0f) / 3.0f;
        if (centerX < leftThird)  return "left side";
        else if (centerX > rightThird) return "right side";
        else return "ahead";
    }

    // ===== Model Loading =====

    @PluginMethod
    public void loadModel(PluginCall call) {
        try {
            Log.d(TAG, "Loading TFLite model...");
            MappedByteBuffer model = FileUtil.loadMappedFile(getContext(), "v7_latest_best_float16.tflite");
            Interpreter.Options options = new Interpreter.Options();

            // GPU acceleration with optimized CPU fallback
            boolean usingGpu = false;
            try {
                CompatibilityList compatList = new CompatibilityList();
                if (compatList.isDelegateSupportedOnThisDevice()) {
                    gpuDelegate = new GpuDelegate();
                    options.addDelegate(gpuDelegate);
                    usingGpu = true;
                    Log.d(TAG, "✓ GPU delegate enabled");
                } else {
                    Log.d(TAG, "⚠ GPU not supported on this device — using optimized CPU");
                }
            } catch (Throwable gpuError) {
                Log.w(TAG, "GPU delegate failed: " + gpuError.getMessage() + " — using optimized CPU");
                if (gpuDelegate != null) {
                    gpuDelegate.close();
                    gpuDelegate = null;
                }
                options = new Interpreter.Options();
            }

            if (!usingGpu) {
                // Dynamically pick thread count: use half of available cores to avoid
                // thermal throttling on weaker/cheaper chipsets, capped at 4.
                int availableCores = Runtime.getRuntime().availableProcessors();
                int threadCount = Math.min(Math.max(availableCores / 2, 2), 4);
                options.setNumThreads(threadCount);

                // XNNPACK is the fastest CPU inference delegate available in TFLite 2.x.
                // It uses SIMD intrinsics (NEON on ARM) for fast float32/float16 math
                // without requiring a GPU. Always enable it on the CPU path.
                options.setUseXNNPACK(true);

                Log.d(TAG, "✓ CPU path: threads=" + threadCount + ", XNNPACK=true (cores=" + availableCores + ")");
            }

            tflite = new Interpreter(model, options);
            isModelLoaded = true;

            String accel = usingGpu ? "GPU" : "CPU+XNNPACK";
            Log.d(TAG, "✓ Model loaded [" + accel + "]");

            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("acceleration", accel);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Model load failed", e);
            call.reject("Failed to load model: " + e.getMessage());
        }
    }

    // NOTE: diagnosticsEnabled flag is intentionally removed.
    // Base64 frame encoding has been permanently stripped from this plugin.
    // The setDiagnosticsMode method is kept as a no-op stub for backward
    // compatibility with any JS callers that haven't been updated yet.
    @PluginMethod
    public void setDiagnosticsMode(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("diagnosticsEnabled", false);  // always false — frame encoding removed
        call.resolve(ret);
    }

    // ===== Start Day Stream =====

    @PluginMethod
    public void startDayStream(PluginCall call) {
        String ip = call.getString("ip");
        if (ip == null || ip.isEmpty()) {
            call.reject("Missing 'ip' parameter for day camera");
            return;
        }

        Log.d(TAG, "Starting DAY stream from: " + ip);
        dayFirstFrame = false;

        if (dayStreamService != null) dayStreamService.stopStream();
        dayStreamService = new ESP32StreamService(ip, getContext());

        dayStreamService.startStream(new ESP32StreamService.StreamCallback() {
            @Override
            public void onFrameReceived(Bitmap frame) {
                synchronized (dayFrameLock) {
                    Bitmap copy = frame.copy(Bitmap.Config.ARGB_8888, false);
                    if (latestDayFrame != null && !latestDayFrame.isRecycled()) latestDayFrame.recycle();
                    latestDayFrame = copy;
                    if (frame != copy && !frame.isRecycled()) frame.recycle();
                }
                if (!dayFirstFrame) {
                    dayFirstFrame = true;
                    Log.d(TAG, "✓ Day camera first frame");
                    JSObject ret = new JSObject();
                    ret.put("status", "connected");
                    ret.put("camera", CAM_DAY);
                    notifyListeners("dayCamConnected", ret);
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Day stream error: " + error);
                JSObject ret = new JSObject();
                ret.put("error", error);
                ret.put("camera", CAM_DAY);
                notifyListeners("dayCamError", ret);
            }

            @Override
            public void onConnected() {
                Log.d(TAG, "Day cam HTTP connected, waiting for first frame...");
            }
        });

        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    // ===== Start Night Stream =====

    @PluginMethod
    public void startNightStream(PluginCall call) {
        String ip = call.getString("ip");
        if (ip == null || ip.isEmpty()) {
            call.reject("Missing 'ip' parameter for night camera");
            return;
        }

        Log.d(TAG, "Starting NIGHT stream from: " + ip);
        nightFirstFrame = false;

        if (nightStreamService != null) nightStreamService.stopStream();
        nightStreamService = new ESP32StreamService(ip, getContext());

        nightStreamService.startStream(new ESP32StreamService.StreamCallback() {
            @Override
            public void onFrameReceived(Bitmap frame) {
                synchronized (nightFrameLock) {
                    Bitmap copy = frame.copy(Bitmap.Config.ARGB_8888, false);
                    if (latestNightFrame != null && !latestNightFrame.isRecycled()) latestNightFrame.recycle();
                    latestNightFrame = copy;
                    if (frame != copy && !frame.isRecycled()) frame.recycle();
                }
                if (!nightFirstFrame) {
                    nightFirstFrame = true;
                    Log.d(TAG, "✓ Night camera first frame");
                    JSObject ret = new JSObject();
                    ret.put("status", "connected");
                    ret.put("camera", CAM_NIGHT);
                    notifyListeners("nightCamConnected", ret);
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Night stream error: " + error);
                JSObject ret = new JSObject();
                ret.put("error", error);
                ret.put("camera", CAM_NIGHT);
                notifyListeners("nightCamError", ret);
            }

            @Override
            public void onConnected() {
                Log.d(TAG, "Night cam HTTP connected, waiting for first frame...");
            }
        });

        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    // ===== Legacy single-stream method (kept for backward compatibility) =====

    @PluginMethod
    public void startESP32Stream(PluginCall call) {
        String ip = call.getString("ip", "192.168.43.101");

        if (ip == null || ip.isEmpty()) {
            call.reject("Missing 'ip' parameter for day camera");
            return;
        }

        Log.d(TAG, "Starting DAY stream (legacy) from: " + ip);
        dayFirstFrame = false;

        if (dayStreamService != null) dayStreamService.stopStream();
        dayStreamService = new ESP32StreamService(ip, getContext());

        dayStreamService.startStream(new ESP32StreamService.StreamCallback() {
            @Override
            public void onFrameReceived(Bitmap frame) {
                synchronized (dayFrameLock) {
                    Bitmap copy = frame.copy(Bitmap.Config.ARGB_8888, false);
                    if (latestDayFrame != null && !latestDayFrame.isRecycled()) latestDayFrame.recycle();
                    latestDayFrame = copy;
                    if (frame != copy && !frame.isRecycled()) frame.recycle();
                }
                if (!dayFirstFrame) {
                    dayFirstFrame = true;
                    Log.d(TAG, "✓ Day camera first frame (legacy stream)");
                    JSObject ret = new JSObject();
                    ret.put("status", "connected");
                    ret.put("camera", CAM_DAY);
                    notifyListeners("dayCamConnected", ret);
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Day stream error: " + error);
                JSObject ret = new JSObject();
                ret.put("error", error);
                ret.put("camera", CAM_DAY);
                notifyListeners("dayCamError", ret);
            }

            @Override
            public void onConnected() {
                Log.d(TAG, "Day cam HTTP connected, waiting for first frame...");
            }
        });

        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    // ===== Switch Active Camera =====

    @PluginMethod
    public void switchActiveCamera(PluginCall call) {
        String cam = call.getString("camera", CAM_DAY);
        if (!cam.equals(CAM_DAY) && !cam.equals(CAM_NIGHT)) {
            call.reject("Invalid camera value. Use 'day' or 'night'.");
            return;
        }

        activeCamera = cam;
        classHistory.clear();
        ratioHistory.clear();
        Log.d(TAG, "✓ Active camera switched to: " + cam + " (history cleared)");

        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("activeCamera", activeCamera);
        call.resolve(ret);
    }

    // ===== Detection =====

    @PluginMethod
    public void detectFromStream(PluginCall call) {
        synchronized (detectLock) {
            if (!isModelLoaded) {
                call.reject("Model not loaded");
                return;
            }

            Bitmap frameToProcess = null;

            if (activeCamera.equals(CAM_NIGHT)) {
                synchronized (nightFrameLock) {
                    if (latestNightFrame == null || latestNightFrame.isRecycled()) {
                        call.reject("No valid night frame available");
                        return;
                    }
                    frameToProcess = latestNightFrame.copy(latestNightFrame.getConfig(), false);
                }
            } else {
                synchronized (dayFrameLock) {
                    if (latestDayFrame == null || latestDayFrame.isRecycled()) {
                        call.reject("No valid day frame available");
                        return;
                    }
                    frameToProcess = latestDayFrame.copy(latestDayFrame.getConfig(), false);
                }
            }

            Bitmap resized = null;
            long detectStart = System.currentTimeMillis();
            try {
                resized = letterboxToSquare(frameToProcess);
                ByteBuffer inputBuffer = convertBitmapToByteBuffer(resized);
                float[][][] outputTransposed = new float[1][33][2100];
                long inferenceStart = System.currentTimeMillis();
                tflite.run(inputBuffer, outputTransposed);
                long inferenceMs = System.currentTimeMillis() - inferenceStart;
                totalInferenceMs.addAndGet(inferenceMs);

                Double confidenceParam = call.getDouble("confidence", (double) DEFAULT_CONFIDENCE);
                float confidenceThreshold = confidenceParam != null ? confidenceParam.floatValue() : DEFAULT_CONFIDENCE;

                // NOTE: includeFrame parameter is intentionally ignored.
                // Base64 frame encoding has been permanently removed to eliminate
                // the 100-300ms CPU overhead it caused on every detection cycle.
                // Visual debugging must be done via Android Studio's Logcat
                // or by temporarily re-attaching a USB display/debugger.

                List<Detection> detections = postProcessTransposed(outputTransposed[0], confidenceThreshold);

                Detection nearest = findNearestObject(detections);

                JSObject ret = new JSObject();
                if (nearest != null) {
                    float bboxArea  = (nearest.x2 - nearest.x1) * (nearest.y2 - nearest.y1);
                    float frameArea = INPUT_SIZE * INPUT_SIZE;
                    float bboxRatio = bboxArea / frameArea;

                    String overrideClass = getTemporalOverrideClass(nearest);
                    if (overrideClass != null) {
                        nearest.className = overrideClass;
                    }

                    boolean imminent = isFrameFillingDetection(nearest);

                    String distance  = imminent ? "imminent" : estimateDistance(bboxArea, frameArea);
                    String direction = getDirection((nearest.x1 + nearest.x2) / 2, INPUT_SIZE);

                    pushClassHistory(nearest.className, bboxRatio);

                    JSObject nearestObj = new JSObject();
                    nearestObj.put("class",      nearest.className);
                    nearestObj.put("distance",   distance);
                    nearestObj.put("direction",  direction);
                    nearestObj.put("confidence", nearest.confidence);
                    nearestObj.put("camera",     activeCamera);
                    nearestObj.put("imminent",   imminent);

                    ret.put("nearest", nearestObj);
                } else {
                    pushClassHistory("none", 0f);
                }

                ret.put("success", true);
                ret.put("activeCamera", activeCamera);
                ret.put("metrics", buildMetricsPayload(inferenceMs, System.currentTimeMillis() - detectStart));
                call.resolve(ret);

            } catch (Exception e) {
                Log.e(TAG, "Detection failed", e);
                call.reject("Detection error: " + e.getMessage());
            } finally {
                if (resized != null && !resized.isRecycled()) resized.recycle();
                if (frameToProcess != null && !frameToProcess.isRecycled()) frameToProcess.recycle();
                long detectMs = System.currentTimeMillis() - detectStart;
                totalDetectMs.addAndGet(detectMs);
                long calls = detectCalls.incrementAndGet();
                maybeLogMetrics(calls);
            }
        }
    }

    // ===== Native Hotspot Network Scanner =====

    @PluginMethod
    public void scanHotspotNetwork(PluginCall call) {
        final int port = call.getInt("port", 82);
        final String path = call.getString("path", "/identity");
        final int timeout = call.getInt("timeout", 2000);

        new Thread(() -> {
            AtomicBoolean foundEnough = new AtomicBoolean(false);
            try {
                List<String> subnets = findLocalSubnets();
                if (subnets.isEmpty()) {
                    call.reject("No local network interfaces found");
                    return;
                }

                Log.d(TAG, "[Scanner] Found " + subnets.size() + " local subnet(s): " + subnets);

                List<JSObject> allResults = new ArrayList<>();

                for (String subnet : subnets) {
                    if (foundEnough.get()) break;
                    Log.d(TAG, "[Scanner] Scanning " + subnet + ".1-254 on port " + port);

                    ExecutorService executor = Executors.newFixedThreadPool(SCAN_POOL_SIZE);
                    List<Future<JSObject>> futures = new ArrayList<>();

                    for (int i = 1; i <= 254; i++) {
                        if (foundEnough.get()) break;
                        final String ip = subnet + "." + i;
                        futures.add(executor.submit(() -> pingHost(ip, port, path, timeout, foundEnough)));
                    }

                    for (Future<JSObject> future : futures) {
                        if (foundEnough.get()) break;
                        try {
                            JSObject result = future.get(timeout + 1000, TimeUnit.MILLISECONDS);
                            if (result != null) {
                                allResults.add(result);
                                if (allResults.size() >= MAX_SCAN_RESULTS) {
                                    foundEnough.set(true);
                                }
                            }
                        } catch (Exception e) {
                            // timeout or error — expected for dead IPs
                        }
                    }

                    executor.shutdownNow();

                    if (allResults.size() >= MAX_SCAN_RESULTS) {
                        Log.d(TAG, "[Scanner] Both cameras found — stopping early.");
                        foundEnough.set(true);
                        break;
                    }
                }

                Log.d(TAG, "[Scanner] Scan complete. Found " + allResults.size() + " board(s).");

                JSObject ret = new JSObject();
                ret.put("success", true);
                com.getcapacitor.JSArray arr = new com.getcapacitor.JSArray();
                for (JSObject r : allResults) {
                    arr.put(r);
                }
                ret.put("boards", arr);
                call.resolve(ret);

            } catch (Exception e) {
                Log.e(TAG, "[Scanner] Scan failed: " + e.getMessage());
                call.reject("Scan failed: " + e.getMessage());
            }
        }).start();
    }

    private List<String> findLocalSubnets() {
        List<String> subnets = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

                String name = ni.getName().toLowerCase();
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("v4-rmnet")) {
                    Log.d(TAG, "[Scanner] Skipping cellular interface: " + name);
                    continue;
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        String ip = addr.getHostAddress();
                        String[] parts = ip.split("\\.");
                        if (parts.length == 4) {
                            String subnet = parts[0] + "." + parts[1] + "." + parts[2];
                            if (!subnets.contains(subnet)) {
                                subnets.add(subnet);
                                Log.d(TAG, "[Scanner] Found local subnet: " + subnet
                                        + " on interface " + ni.getName() + " (IP: " + ip + ")");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "[Scanner] Error enumerating interfaces: " + e.getMessage());
        }
        return subnets;
    }

    private JSObject pingHost(String ip, int port, String path, int timeout, AtomicBoolean cancel) {
        Socket socket = null;
        try {
            if (cancel.get()) return null;
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), timeout);
            socket.setSoTimeout(timeout);

            OutputStream out = socket.getOutputStream();
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + ip + ":" + port + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(request.getBytes());
            out.flush();

            InputStream in = socket.getInputStream();
            StringBuilder response = new StringBuilder();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                if (cancel.get()) return null;
                response.append(new String(buffer, 0, bytesRead));
            }

            String responseStr = response.toString();
            int bodyStart = responseStr.indexOf("\r\n\r\n");
            if (bodyStart >= 0) {
                String body = responseStr.substring(bodyStart + 4).trim();
                return new JSObject(body);
            }
        } catch (Exception e) {
            // Connection refused or timeout — expected for dead IPs
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    // ===== Helper Methods =====

    // NOTE: drawDetections() and getClassColor() have been permanently removed.
    // These methods drew bounding boxes onto a Bitmap copy of the frame before
    // Base64 encoding it for the JS layer. With Base64 encoding gone, there is
    // no output surface to draw onto. Removing them saves ~50-100ms per detection
    // call on CPU-only devices by eliminating Canvas allocations, pixel-level
    // loop iterations over a 320x320 bitmap, and Paint object creation.

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        byteBuffer.order(ByteOrder.nativeOrder());
        int[] rgbValues = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(rgbValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        int pixel = 0;
        for (int i = 0; i < INPUT_SIZE; ++i) {
            for (int j = 0; j < INPUT_SIZE; ++j) {
                final int val = rgbValues[pixel++];
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
                byteBuffer.putFloat(((val >> 8)  & 0xFF) / 255.0f);
                byteBuffer.putFloat((val & 0xFF)          / 255.0f);
            }
        }
        byteBuffer.rewind();
        return byteBuffer;
    }

    private List<Detection> postProcessTransposed(float[][] output, float threshold) {
        List<Detection> detections = new ArrayList<>();
        int numDetections = output[0].length;
        for (int i = 0; i < numDetections; i++) {
            float cx = output[0][i];
            float cy = output[1][i];
            float w  = output[2][i];
            float h  = output[3][i];

            int classId = 0;
            float maxClassScore = output[4][i];
            for (int j = 5; j < 33; j++) {
                if (output[j][i] > maxClassScore) {
                    maxClassScore = output[j][i];
                    classId = j - 4;
                }
            }

            String className = getClassName(classId);
            float requiredThreshold = getThresholdForClass(className, threshold);
            if (maxClassScore < requiredThreshold) continue;

            float x1 = Math.max(0, Math.min(INPUT_SIZE - 1, (cx - w / 2) * INPUT_SIZE));
            float y1 = Math.max(0, Math.min(INPUT_SIZE - 1, (cy - h / 2) * INPUT_SIZE));
            float x2 = Math.max(0, Math.min(INPUT_SIZE - 1, (cx + w / 2) * INPUT_SIZE));
            float y2 = Math.max(0, Math.min(INPUT_SIZE - 1, (cy + h / 2) * INPUT_SIZE));

            Detection det = new Detection();
            det.className  = getClassName(classId);
            det.confidence = maxClassScore;
            det.x1 = x1; det.y1 = y1;
            det.x2 = x2; det.y2 = y2;
            detections.add(det);
        }
        return detections;
    }

    private float getThresholdForClass(String className, float defaultThreshold) {
        switch (className) {
            case "pothole":
            case "puddle":
            case "stairs":
            case "tricycle":
            case "motorcycle":
            case "car":
            case "bus":
            case "truck":
            case "glass wall":
            case "trash can":
                return 0.35f;
            case "door":
                return 0.60f;
            default:
                return defaultThreshold;
        }
    }

    private float getScaleMultiplier(String className) {
        switch (className) {
            case "pothole":          return 4.0f;
            case "puddle":           return 4.0f;
            case "bollards":         return 3.5f;
            case "pole":             return 3.0f;
            case "stairs":           return 2.0f;
            case "door":             return 1.5f;
            case "dog":              return 2.0f;
            case "person":           return 1.0f;
            case "group of people": return 1.0f;
            case "chair":            return 1.2f;
            case "couch":            return 1.0f;
            case "table":            return 1.0f;
            case "tree":             return 0.8f;
            case "motorcycle":       return 0.9f;
            case "car":              return 0.7f;
            case "tricycle":         return 0.9f;
            case "standing aircon":  return 1.2f;
            case "bench":            return 1.5f;
            case "fence":            return 0.8f;
            case "gate":             return 1.0f;
            case "refrigerator":     return 1.2f;
            case "trash can":        return 2.0f;
            case "stall":            return 0.7f;
            case "wall":             return 0.5f;
            case "glass wall":       return 0.5f;
            case "bus":              return 0.5f;
            case "truck":            return 0.5f;
            case "cabinet":          return 1.0f;
            case "window":           return 0.8f;
            default:                 return 1.0f;
        }
    }

    private Detection findNearestObject(List<Detection> detections) {
        if (detections.isEmpty()) return null;
        Detection nearest = null;
        float maxThreatScore = 0;
        for (Detection det : detections) {
            if (det.className.equals("window")) continue;

            float rawArea = (det.x2 - det.x1) * (det.y2 - det.y1);
            float scaledArea = rawArea * getScaleMultiplier(det.className);

            float centerX = (det.x1 + det.x2) / 2f;
            String dir = getDirection(centerX, INPUT_SIZE);
            float directionalMultiplier = dir.equals("ahead") ? 2.0f : 1.0f;

            float threatScore = scaledArea * directionalMultiplier;

            if (threatScore > maxThreatScore) {
                maxThreatScore = threatScore;
                nearest = det;
            }
        }
        return nearest;
    }

    private String getClassName(int classId) {
        String[] classes = {
                "person",            // 0
                "group of people",   // 1
                "car",               // 2
                "bus",               // 3
                "motorcycle",        // 4
                "truck",             // 5
                "bollards",          // 6
                "stairs",            // 7
                "door",              // 8
                "chair",             // 9
                "couch",             // 10
                "table",             // 11
                "pothole",           // 12
                "pole",              // 13
                "dog",               // 14
                "wall",              // 15
                "glass wall",        // 16
                "cabinet",           // 17
                "window",            // 18
                "tricycle",          // 19
                "standing aircon",   // 20
                "bench",             // 21
                "fence",             // 22
                "gate",              // 23
                "refrigerator",      // 24
                "trash can",         // 25
                "tree",              // 26
                "stall",             // 27
                "puddle"             // 28
        };
        if (classId >= 0 && classId < classes.length) return classes[classId];
        return "obstacle";
    }

    // ===== Cleanup =====

    @PluginMethod
    public void stopESP32Stream(PluginCall call) {
        if (dayStreamService != null)   dayStreamService.stopStream();
        if (nightStreamService != null) nightStreamService.stopStream();

        synchronized (dayFrameLock) {
            if (latestDayFrame != null && !latestDayFrame.isRecycled()) {
                latestDayFrame.recycle(); latestDayFrame = null;
            }
        }
        synchronized (nightFrameLock) {
            if (latestNightFrame != null && !latestNightFrame.isRecycled()) {
                latestNightFrame.recycle(); latestNightFrame = null;
            }
        }

        dayFirstFrame = false;
        nightFirstFrame = false;

        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void unloadModel(PluginCall call) {
        if (tflite != null) { tflite.close(); tflite = null; isModelLoaded = false; }
        if (gpuDelegate != null) { gpuDelegate.close(); gpuDelegate = null; }
        synchronized (dayFrameLock) {
            if (latestDayFrame != null && !latestDayFrame.isRecycled()) {
                latestDayFrame.recycle(); latestDayFrame = null;
            }
        }
        synchronized (nightFrameLock) {
            if (latestNightFrame != null && !latestNightFrame.isRecycled()) {
                latestNightFrame.recycle(); latestNightFrame = null;
            }
        }
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    private Bitmap letterboxToSquare(Bitmap original) {
        Bitmap output = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);

        float scale = Math.min(INPUT_SIZE / (float) original.getWidth(), INPUT_SIZE / (float) original.getHeight());
        int scaledW = Math.round(original.getWidth() * scale);
        int scaledH = Math.round(original.getHeight() * scale);
        int left = (INPUT_SIZE - scaledW) / 2;
        int top = (INPUT_SIZE - scaledH) / 2;
        RectF dst = new RectF(left, top, left + scaledW, top + scaledH);
        canvas.drawBitmap(original, null, dst, null);
        return output;
    }

    private JSObject buildMetricsPayload(long inferenceMs, long detectMs) {
        JSObject metrics = new JSObject();
        metrics.put("inferenceMs", inferenceMs);
        metrics.put("detectMs", detectMs);
        return metrics;
    }

    private void maybeLogMetrics(long calls) {
        long now = System.currentTimeMillis();
        if (calls % 20 != 0 && now - metricsLastLoggedAt < 15000) return;
        metricsLastLoggedAt = now;

        long avgInference = calls == 0 ? 0 : totalInferenceMs.get() / calls;
        long avgDetect = calls == 0 ? 0 : totalDetectMs.get() / calls;
        Log.d(TAG, "[Perf] calls=" + calls + " avgInferenceMs=" + avgInference
                + " avgDetectMs=" + avgDetect);
    }

    // ===== Detection Class =====

    private static class Detection {
        String className;
        float confidence;
        float x1, y1, x2, y2;
    }
}