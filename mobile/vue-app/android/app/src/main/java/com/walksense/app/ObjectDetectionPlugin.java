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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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

    // Pre-allocated reusable buffers — allocated once at model load, reused every frame
    private ByteBuffer reusableInputBuffer;
    private int[] reusableRgbValues;
    private Bitmap reusableLetterbox;
    private float[][][] reusableOutput;

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

    // ===== Demo Mode Preview Emitter =====
    private ScheduledExecutorService previewExecutor;
    private ScheduledFuture<?> previewTask;
    private volatile boolean demoModeActive = false;

    // ===== Temporal Tracking (close-proximity misclassification fix) =====
    private static final int CLASS_HISTORY_SIZE = 12;
    private final List<String> classHistory   = new ArrayList<>();
    private final List<Float>  ratioHistory   = new ArrayList<>();
    private final List<Float>  centerXHistory = new ArrayList<>();
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
             float ratio     = bboxArea / frameArea;

             switch (det.className) {
                 case "stairs":
                     return ratio > 0.30f && det.y2 > 250;

                 case "pothole":
                 case "puddle":
                     return ratio > 0.25f && det.y2 > 275 && det.confidence > 0.45f;

                 case "person":
                 case "dog":
                 case "bench":
                 case "motorcycle":
                     return ratio > 0.45f;

                 default:
                     return ratio > 0.75f;
         }
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

    private void pushClassHistory(String className, float bboxRatio, float centerX) {
        classHistory.add(className);
        ratioHistory.add(bboxRatio);
        centerXHistory.add(centerX);
        while (classHistory.size() > CLASS_HISTORY_SIZE) {
            classHistory.remove(0);
            ratioHistory.remove(0);
            centerXHistory.remove(0);
        }
    }

    // ===== Stationary Detection =====
    private static final int   STABILITY_MIN_FRAMES    = 10;
    private static final float STABILITY_RATIO_DELTA   = 0.05f;
    private static final float STABILITY_CENTERX_DELTA = 25.0f;

    private boolean isDetectionStable(String currentClass) {
        if (classHistory.size() < STABILITY_MIN_FRAMES) return false;

        int recentSize = classHistory.size();
        List<String> recentClasses  = classHistory.subList(recentSize - STABILITY_MIN_FRAMES, recentSize);
        List<Float>  recentRatios   = ratioHistory.subList(recentSize - STABILITY_MIN_FRAMES, recentSize);
        List<Float>  recentCenterXs = centerXHistory.subList(recentSize - STABILITY_MIN_FRAMES, recentSize);

        for (String cls : recentClasses) {
            if (!cls.equals(currentClass)) return false;
        }

        float minRatio = Collections.min(recentRatios);
        float maxRatio = Collections.max(recentRatios);
        if ((maxRatio - minRatio) > STABILITY_RATIO_DELTA) return false;

        float minX = Collections.min(recentCenterXs);
        float maxX = Collections.max(recentCenterXs);
        if ((maxX - minX) > STABILITY_CENTERX_DELTA) return false;

        return true;
    }

    private String getDirection(float centerX, int frameWidth) {
        float leftThird  = frameWidth / 3.0f;
        float rightThird = (frameWidth * 2.0f) / 3.0f;
        if (centerX < leftThird)  return "left side";
        else if (centerX > rightThird) return "right side";
        else return "ahead";
    }

    // ===== Avoidance Guidance =====
    private static final float AVOIDANCE_MIN_CLEAR_PX = 50.0f;  // ~15% of 320px frame
    private static final float AVOIDANCE_BLOCKED_PX   = 30.0f;  // ~10% of 320px frame

    private String getAvoidanceDirection(float x1, float x2, int frameWidth) {
        float leftClear  = x1;                    // gap from left edge to obstacle
        float rightClear = frameWidth - x2;       // gap from obstacle to right edge

        boolean leftOpen  = leftClear  > AVOIDANCE_MIN_CLEAR_PX;
        boolean rightOpen = rightClear > AVOIDANCE_MIN_CLEAR_PX;

        if (leftOpen && rightOpen) {
            // Both sides have room — suggest the side with MORE space
            if (leftClear > rightClear + 30) return "left";
            if (rightClear > leftClear + 30) return "right";
            return "both";  // roughly equal room on both sides
        }
        if (leftOpen)  return "left";
        if (rightOpen) return "right";

        // Both sides below threshold — obstacle fills the frame width
        return "blocked";
    }


    // ===== Model Loading =====

    @PluginMethod
    public void loadModel(PluginCall call) {
        try {
            Log.d(TAG, "Loading TFLite model...");
            MappedByteBuffer model = FileUtil.loadMappedFile(getContext(), "v13_latest_best_float16.tflite");
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

                int availableCores = Runtime.getRuntime().availableProcessors();
                int threadCount = Math.min(Math.max(availableCores / 2, 2), 4);
                options.setNumThreads(threadCount);

                options.setUseXNNPACK(true);

                Log.d(TAG, "✓ CPU path: threads=" + threadCount + ", XNNPACK=true (cores=" + availableCores + ")");
            }

            tflite = new Interpreter(model, options);
            isModelLoaded = true;

            // Pre-allocate reusable buffers to eliminate per-frame GC pressure
            reusableInputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
            reusableInputBuffer.order(ByteOrder.nativeOrder());
            reusableRgbValues  = new int[INPUT_SIZE * INPUT_SIZE];
            reusableLetterbox  = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
            reusableOutput     = new float[1][33][2100];

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


    @PluginMethod
    public void setDiagnosticsMode(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("diagnosticsEnabled", false);
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
                // Null out stale frame so detection loop rejects instead of ghost-detecting
                synchronized (dayFrameLock) {
                    if (latestDayFrame != null && !latestDayFrame.isRecycled()) {
                        latestDayFrame.recycle();
                    }
                    latestDayFrame = null;
                }
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
                // Null out stale frame so detection loop rejects instead of ghost-detecting
                synchronized (nightFrameLock) {
                    if (latestNightFrame != null && !latestNightFrame.isRecycled()) {
                        latestNightFrame.recycle();
                    }
                    latestNightFrame = null;
                }
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
                    // Atomic swap: take ownership, no copy needed
                    // Stream thread will allocate a fresh frame on next delivery
                    frameToProcess = latestNightFrame;
                    latestNightFrame = null;
                }
            } else {
                synchronized (dayFrameLock) {
                    if (latestDayFrame == null || latestDayFrame.isRecycled()) {
                        call.reject("No valid day frame available");
                        return;
                    }
                    // Atomic swap: take ownership, no copy needed
                    // Stream thread will allocate a fresh frame on next delivery
                    frameToProcess = latestDayFrame;
                    latestDayFrame = null;
                }
            }

            Bitmap resized = null;
            long detectStart = System.currentTimeMillis();
            try {
                resized = letterboxToSquare(frameToProcess);
                ByteBuffer inputBuffer = convertBitmapToByteBuffer(resized);
                // Use pre-allocated output tensor — no allocation needed
                long inferenceStart = System.currentTimeMillis();
                tflite.run(inputBuffer, reusableOutput);
                long inferenceMs = System.currentTimeMillis() - inferenceStart;
                totalInferenceMs.addAndGet(inferenceMs);

                Double confidenceParam = call.getDouble("confidence", (double) DEFAULT_CONFIDENCE);
                float confidenceThreshold = confidenceParam != null ? confidenceParam.floatValue() : DEFAULT_CONFIDENCE;

                List<Detection> detections = postProcessTransposed(reusableOutput[0], confidenceThreshold);

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
                    String avoidance = getAvoidanceDirection(nearest.x1, nearest.x2, INPUT_SIZE);
                    float  centerX   = (nearest.x1 + nearest.x2) / 2f;
                    boolean stable   = isDetectionStable(nearest.className);

                    pushClassHistory(nearest.className, bboxRatio, centerX);

                    JSObject nearestObj = new JSObject();
                    nearestObj.put("class",      nearest.className);
                    nearestObj.put("distance",   distance);
                    nearestObj.put("direction",  direction);
                    nearestObj.put("confidence", nearest.confidence);
                    nearestObj.put("camera",     activeCamera);
                    nearestObj.put("imminent",   imminent);
                    nearestObj.put("avoidance",  avoidance);
                    nearestObj.put("stable",     stable);
                    // Bounding box coordinates for demo mode canvas overlay
                    nearestObj.put("x1", nearest.x1);
                    nearestObj.put("y1", nearest.y1);
                    nearestObj.put("x2", nearest.x2);
                    nearestObj.put("y2", nearest.y2);

                    ret.put("nearest", nearestObj);
                } else {
                    pushClassHistory("none", 0f, 0f);
                }

                ret.put("success", true);
                ret.put("activeCamera", activeCamera);
                ret.put("metrics", buildMetricsPayload(inferenceMs, System.currentTimeMillis() - detectStart));
                call.resolve(ret);

            } catch (Exception e) {
                Log.e(TAG, "Detection failed", e);
                call.reject("Detection error: " + e.getMessage());
            } finally {
                if (frameToProcess != null && !frameToProcess.isRecycled()) frameToProcess.recycle();
                long detectMs = System.currentTimeMillis() - detectStart;
                totalDetectMs.addAndGet(detectMs);
                long calls = detectCalls.incrementAndGet();
                maybeLogMetrics(calls);
            }
        }
    }

    // ===== Demo Mode Preview =====

    @PluginMethod
    public void startPreview(PluginCall call) {
        if (demoModeActive) {
            call.resolve(new JSObject().put("success", true));
            return;
        }

        demoModeActive = true;
        previewExecutor = Executors.newSingleThreadScheduledExecutor();
        previewTask = previewExecutor.scheduleAtFixedRate(() -> {
            try {
                Bitmap frame = null;

                // Grab a COPY of the current frame — does NOT interfere with detection's atomic swap
                if (activeCamera.equals(CAM_NIGHT)) {
                    synchronized (nightFrameLock) {
                        if (latestNightFrame != null && !latestNightFrame.isRecycled()) {
                            frame = latestNightFrame.copy(latestNightFrame.getConfig(), false);
                        }
                    }
                } else {
                    synchronized (dayFrameLock) {
                        if (latestDayFrame != null && !latestDayFrame.isRecycled()) {
                            frame = latestDayFrame.copy(latestDayFrame.getConfig(), false);
                        }
                    }
                }

                if (frame == null) return;

                // Compress to JPEG at quality 40 (~10-20KB, ~5ms encoding)
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                frame.compress(Bitmap.CompressFormat.JPEG, 40, baos);
                frame.recycle();

                byte[] jpegBytes = baos.toByteArray();
                String base64 = "data:image/jpeg;base64," +
                        android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP);

                JSObject data = new JSObject();
                data.put("frame", base64);
                data.put("camera", activeCamera);
                notifyListeners("previewFrame", data);

            } catch (Exception e) {
                Log.e(TAG, "Preview emitter error", e);
            }
        }, 0, 400, TimeUnit.MILLISECONDS);  // 2.5 FPS — gentle on CPU

        Log.d(TAG, "✓ Demo preview started (2.5 FPS)");
        call.resolve(new JSObject().put("success", true));
    }

    @PluginMethod
    public void stopPreview(PluginCall call) {
        stopPreviewInternal();
        Log.d(TAG, "✓ Demo preview stopped");

        // Notify JS to clear the canvas
        JSObject data = new JSObject();
        data.put("frame", JSObject.NULL);
        notifyListeners("previewFrame", data);

        call.resolve(new JSObject().put("success", true));
    }

    private void stopPreviewInternal() {
        demoModeActive = false;
        if (previewTask != null) {
            previewTask.cancel(false);
            previewTask = null;
        }
        if (previewExecutor != null) {
            previewExecutor.shutdownNow();
            previewExecutor = null;
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

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        // Reuse pre-allocated buffer — rewind resets position to 0 (like washing the plate)
        reusableInputBuffer.rewind();
        bitmap.getPixels(reusableRgbValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        int pixel = 0;
        for (int i = 0; i < INPUT_SIZE; ++i) {
            for (int j = 0; j < INPUT_SIZE; ++j) {
                final int val = reusableRgbValues[pixel++];
                reusableInputBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
                reusableInputBuffer.putFloat(((val >> 8)  & 0xFF) / 255.0f);
                reusableInputBuffer.putFloat((val & 0xFF)          / 255.0f);
            }
        }
        reusableInputBuffer.rewind();
        return reusableInputBuffer;
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

            // Auto-detect if coordinates are absolute (0-320) or normalized (0-1)
            // The Float16 model outputs 0-1, the Dynamic Range model outputs 0-320
            float scale = (cx > 2.0f || cy > 2.0f || w > 2.0f) ? 1.0f : INPUT_SIZE;

            float x1 = Math.max(0, Math.min(INPUT_SIZE - 1, (cx - w / 2) * scale));
            float y1 = Math.max(0, Math.min(INPUT_SIZE - 1, (cy - h / 2) * scale));
            float x2 = Math.max(0, Math.min(INPUT_SIZE - 1, (cx + w / 2) * scale));
            float y2 = Math.max(0, Math.min(INPUT_SIZE - 1, (cy + h / 2) * scale));

            if (className.equals("glass wall") && y2 < (INPUT_SIZE * 0.30f)) {
               continue;
            }

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
        boolean isNightCam = activeCamera.equals(CAM_NIGHT);

        switch (className) {
            // Tier 1: Ground hazards — LOWERED day threshold for better recall
            case "pothole":
            case "puddle":
                return isNightCam ? 0.40f : 0.28f;
            case "glass wall":
                return isNightCam ? 0.40f : 0.35f;

            // Tier 1.5: Low-recall obstacles — LOWERED day threshold
            case "bench":
                return isNightCam ? 0.45f : 0.28f;
            case "person":
            case "dog":
                return isNightCam ? 0.42f : 0.30f;

            // Tier 2: Medium-danger obstacles — UNCHANGED (good performance)
            case "stairs":
            case "tricycle":
            case "motorcycle":
            case "car":
            case "bus":
            case "truck":
            case "trash can":
            case "elevator":
                return isNightCam ? 0.45f : 0.35f;

            // Tier 3: Static obstacles — moderate threshold
            case "tree":
            case "fence":
            case "pole":
            case "standing aircon":
            case "bollards":
            case "stall":
            case "cabinet":
            case "refrigerator":
                return isNightCam ? 0.42f : 0.38f;

            // Tier 4: Structural / contextual — reliable under both cameras, unchanged
            case "door":
                return 0.45f;
            case "wall":
                return 0.40f;
            case "window":
            case "gate":
                return 0.45f;

            default:
                return defaultThreshold;
        }
    }

    private float getScaleMultiplier(String className) {
        switch (className) {
            case "pothole":          return 4.0f;   // reduced from 6.0 — high multiplier was amplifying low-conf false detections
            case "puddle":           return 4.0f;   // reduced from 6.0 — same reason; night cam gate now handles priority
            case "bollards":         return 3.5f;
            case "pole":             return 3.0f;
            case "stairs":           return 3.0f;   // reduced from 4.0 — balanced against new night cam threshold gate
            case "door":             return 1.5f;
            case "dog":              return 4.0f;   // was 2.0 — unpredictable moving obstacle, alert early
            case "person":           return 1.0f;
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
            case "elevator":         return 0.7f;
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
                "car",               // 1
                "bus",               // 2
                "motorcycle",        // 3
                "truck",             // 4
                "bollards",          // 5
                "stairs",            // 6
                "door",              // 7
                "chair",             // 8
                "couch",             // 9
                "table",             // 10
                "pothole",           // 11
                "pole",              // 12
                "dog",               // 13
                "wall",              // 14
                "glass wall",        // 15
                "cabinet",           // 16
                "window",            // 17
                "tricycle",          // 18
                "standing aircon",   // 19
                "bench",             // 20
                "fence",             // 21
                "gate",              // 22
                "refrigerator",      // 23
                "trash can",         // 24
                "tree",              // 25
                "stall",             // 26
                "puddle",            // 27
                "elevator"           // 28
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
        // Kill demo preview if active — prevents zombie emitter thread
        stopPreviewInternal();
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
        // Clean up pre-allocated reusable buffers
        if (reusableLetterbox != null && !reusableLetterbox.isRecycled()) {
            reusableLetterbox.recycle();
            reusableLetterbox = null;
        }
        reusableInputBuffer = null;
        reusableRgbValues   = null;
        reusableOutput      = null;
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    private Bitmap letterboxToSquare(Bitmap original) {
        // Draw into pre-allocated 320×320 bitmap — no new allocation needed
        Canvas canvas = new Canvas(reusableLetterbox);
        canvas.drawColor(Color.BLACK);

        float scale = Math.min(INPUT_SIZE / (float) original.getWidth(), INPUT_SIZE / (float) original.getHeight());
        int scaledW = Math.round(original.getWidth() * scale);
        int scaledH = Math.round(original.getHeight() * scale);
        int left = (INPUT_SIZE - scaledW) / 2;
        int top = (INPUT_SIZE - scaledH) / 2;
        RectF dst = new RectF(left, top, left + scaledW, top + scaledH);
        canvas.drawBitmap(original, null, dst, null);
        return reusableLetterbox;
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