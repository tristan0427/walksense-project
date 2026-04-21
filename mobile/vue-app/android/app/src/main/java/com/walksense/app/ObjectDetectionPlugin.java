package com.walksense.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Base64;
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
    private boolean diagnosticsEnabled   = false;

    // ===== Baseline Perf Counters =====
    private final AtomicLong detectCalls = new AtomicLong(0);
    private final AtomicLong totalInferenceMs = new AtomicLong(0);
    private final AtomicLong totalDetectMs = new AtomicLong(0);
    private final AtomicLong totalFramePayloadBytes = new AtomicLong(0);
    private final AtomicLong totalFramesReturned = new AtomicLong(0);
    private long metricsLastLoggedAt = 0L;

    // Reusable buffers to reduce allocation churn in hot path.
    private final int[] rgbValues = new int[INPUT_SIZE * INPUT_SIZE];
    private final float[][][] outputTransposed = new float[1][32][2100];

    // ===== Temporal Tracking (close-proximity misclassification fix) =====
    // Sliding window of recent detection classes and their frame occupancy ratios.
    // Used to detect and correct sudden class flips (e.g., car → wall) when an
    // object's surface fills the entire frame at very close range.
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

    /**
     * Checks if the detected object occupies an extreme portion of the frame,
     * indicating the user is at touching distance regardless of classification.
     * At chest-mount height, 85% frame occupancy ≈ 20-30cm from a car door.
     */
    private boolean isFrameFillingDetection(Detection det) {
        float bboxArea  = (det.x2 - det.x1) * (det.y2 - det.y1);
        float frameArea = INPUT_SIZE * INPUT_SIZE;
        return (bboxArea / frameArea) > IMMINENT_OCCUPANCY_THRESHOLD;
    }

    /**
     * If the current detection is "wall" but recent history shows a consistent
     * non-wall class (e.g. car), override the classification.  This handles the
     * case where a large object's surface fills the frame at close range and
     * the YOLO model sees only a flat painted surface.
     *
     * Returns the corrected class name, or null if no override is needed.
     */
    private String getTemporalOverrideClass(Detection det) {
        float bboxArea  = (det.x2 - det.x1) * (det.y2 - det.y1);
        float frameArea = INPUT_SIZE * INPUT_SIZE;
        float ratio     = bboxArea / frameArea;

        // Only consider override when current detection is "wall" or "glass wall"
        // filling >60% of the frame
        if ((!det.className.equals("wall") && !det.className.equals("glass wall"))
                || ratio < TEMPORAL_OVERRIDE_THRESHOLD) {
            return null;
        }
        if (classHistory.size() < 3) return null;

        // Find dominant non-wall class in recent history
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

        // Need ≥3 occurrences of the same non-wall class to override
        if (dominant != null && maxCount >= 3) {
            Log.d(TAG, "[Temporal] Overriding '" + det.className + "' → '" + dominant
                    + "' (appeared " + maxCount + "/" + classHistory.size() + " recent frames)");
            return dominant;
        }
        return null;
    }

    /**
     * Pushes a detection result into the sliding window and trims to size.
     */
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
            MappedByteBuffer model = FileUtil.loadMappedFile(getContext(), "v4_latest_best_float16.tflite");
            Interpreter.Options options = new Interpreter.Options();

            // GPU acceleration with CPU fallback
            boolean usingGpu = false;
            try {
                CompatibilityList compatList = new CompatibilityList();
                if (compatList.isDelegateSupportedOnThisDevice()) {
                    GpuDelegate.Options gpuOptions = compatList.getBestOptionsForThisDevice();
                    gpuDelegate = new GpuDelegate(gpuOptions);
                    options.addDelegate(gpuDelegate);
                    usingGpu = true;
                    Log.d(TAG, "✓ GPU delegate enabled");
                } else {
                    Log.d(TAG, "⚠ GPU not supported on this device — using CPU");
                }
            } catch (Exception gpuError) {
                Log.w(TAG, "GPU delegate failed: " + gpuError.getMessage() + " — using CPU");
                if (gpuDelegate != null) {
                    gpuDelegate.close();
                    gpuDelegate = null;
                }
                options = new Interpreter.Options();
            }

            if (!usingGpu) {
                options.setNumThreads(4);
            }

            tflite = new Interpreter(model, options);
            isModelLoaded = true;

            String accel = usingGpu ? "GPU" : "CPU";
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
        diagnosticsEnabled = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("diagnosticsEnabled", diagnosticsEnabled);
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
        // Legacy compatibility — delegates to startDayStream
        // Prefer startDayStream / startNightStream going forward
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

    /**
     * Called from Vue when BH1750 lux value crosses the day/night threshold.
     * Switches which camera's frames are fed into the YOLO model.
     * Both streams keep running at all times.
     *
     * @param call expects { "camera": "day" | "night" }
     */
    @PluginMethod
    public void switchActiveCamera(PluginCall call) {
        String cam = call.getString("camera", CAM_DAY);
        if (!cam.equals(CAM_DAY) && !cam.equals(CAM_NIGHT)) {
            call.reject("Invalid camera value. Use 'day' or 'night'.");
            return;
        }

        activeCamera = cam;

        // Clear temporal tracking history on camera switch to avoid
        // cross-camera false overrides (different FOV, exposure, etc.)
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
        if (!isModelLoaded) {
            call.reject("Model not loaded");
            return;
        }

        Bitmap frameToProcess = null;

        // Pull frame from whichever camera is currently active
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
            long inferenceStart = System.currentTimeMillis();
            tflite.run(inputBuffer, outputTransposed);
            long inferenceMs = System.currentTimeMillis() - inferenceStart;
            totalInferenceMs.addAndGet(inferenceMs);

            Double confidenceParam = call.getDouble("confidence", (double) DEFAULT_CONFIDENCE);
            float confidenceThreshold = confidenceParam != null ? confidenceParam.floatValue() : DEFAULT_CONFIDENCE;
            boolean includeFrame = diagnosticsEnabled && Boolean.TRUE.equals(call.getBoolean("includeFrame", false));

            List<Detection> detections = postProcessTransposed(outputTransposed[0], confidenceThreshold);

            Detection nearest = findNearestObject(detections);

            JSObject ret = new JSObject();
            if (nearest != null) {
                float bboxArea  = (nearest.x2 - nearest.x1) * (nearest.y2 - nearest.y1);
                float frameArea = INPUT_SIZE * INPUT_SIZE;
                float bboxRatio = bboxArea / frameArea;

                // ── Layer 2: Temporal Class Override ──────────────────────────
                // Check if "wall"/"glass wall" should actually be the class that
                // was consistently detected in recent frames (e.g., car)
                String overrideClass = getTemporalOverrideClass(nearest);
                if (overrideClass != null) {
                    nearest.className = overrideClass;
                }

                // ── Layer 1: Frame Occupancy Heuristic ───────────────────────
                // If a single detection fills >85% of the frame, the user is at
                // collision distance — override to "imminent" regardless of class
                boolean imminent = isFrameFillingDetection(nearest);

                String distance  = imminent ? "imminent" : estimateDistance(bboxArea, frameArea);
                String direction = getDirection((nearest.x1 + nearest.x2) / 2, INPUT_SIZE);

                // Push into sliding window for future temporal tracking
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
                // No detection — push empty into history to naturally age out old entries
                pushClassHistory("none", 0f);
            }

            // Draw bounding boxes and encode frame as base64 JPEG if requested
            if (includeFrame && frameToProcess != null && !frameToProcess.isRecycled()) {
                try {
                    Bitmap annotated = drawDetections(frameToProcess, detections);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    annotated.compress(Bitmap.CompressFormat.JPEG, 50, baos);
                    byte[] jpegBytes = baos.toByteArray();
                    String base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
                    ret.put("frame", "data:image/jpeg;base64," + base64);
                    totalFramePayloadBytes.addAndGet(jpegBytes.length);
                    totalFramesReturned.incrementAndGet();
                    if (annotated != frameToProcess) annotated.recycle();
                } catch (Exception encodeErr) {
                    Log.w(TAG, "Frame encode skipped: " + encodeErr.getMessage());
                }
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

    // ===== Native Hotspot Network Scanner =====

    /**
     * Scans the phone's hotspot network for WalkSense boards using raw TCP sockets.
     * This bypasses CapacitorHttp's broken cellular routing by using the same
     * native Socket path that ESP32StreamService uses (proven to work).
     *
     * Auto-detects the hotspot subnet via NetworkInterface enumeration.
     */
    @PluginMethod
    public void scanHotspotNetwork(PluginCall call) {
        final int port = call.getInt("port", 82);
        final String path = call.getString("path", "/identity");
        final int timeout = call.getInt("timeout", 2000);

        new Thread(() -> {
            AtomicBoolean foundEnough = new AtomicBoolean(false);
            try {
                // Find all local subnets (hotspot, WiFi, etc.)
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

                    // Stop scanning remaining subnets if both cameras found
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

    /**
     * Finds all local /24 subnets by enumerating network interfaces.
     * Returns subnets like ["10.199.223", "192.168.43"].
     */
    private List<String> findLocalSubnets() {
        List<String> subnets = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;

                // Skip cellular interfaces (common names)
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

    /**
     * Pings a single host on the given port/path using a raw TCP Socket.
     * Returns a JSObject with the parsed JSON response, or null on failure.
     */
    private JSObject pingHost(String ip, int port, String path, int timeout, AtomicBoolean cancel) {
        Socket socket = null;
        try {
            if (cancel.get()) return null;
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), timeout);
            socket.setSoTimeout(timeout);

            // Send HTTP GET
            OutputStream out = socket.getOutputStream();
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + ip + ":" + port + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(request.getBytes());
            out.flush();

            // Read response
            InputStream in = socket.getInputStream();
            StringBuilder response = new StringBuilder();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                if (cancel.get()) return null;
                response.append(new String(buffer, 0, bytesRead));
            }

            // Extract JSON body (after HTTP headers)
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

    // ===== Bounding Box Drawing =====

    private Bitmap drawDetections(Bitmap original, List<Detection> detections) {
        Bitmap annotated = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(annotated);

        float scaleX = annotated.getWidth()  / (float) INPUT_SIZE;
        float scaleY = annotated.getHeight() / (float) INPUT_SIZE;

        Paint boxPaint  = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3f);
        boxPaint.setAntiAlias(true);

        Paint bgPaint = new Paint();
        bgPaint.setStyle(Paint.Style.FILL);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);
        textPaint.setAntiAlias(true);
        textPaint.setFakeBoldText(true);

        for (Detection det : detections) {
            int color = getClassColor(det.className);
            boxPaint.setColor(color);
            bgPaint.setColor(color);

            float left   = det.x1 * scaleX;
            float top    = det.y1 * scaleY;
            float right  = det.x2 * scaleX;
            float bottom = det.y2 * scaleY;

            canvas.drawRect(left, top, right, bottom, boxPaint);

            String label = det.className + " " + Math.round(det.confidence * 100) + "%";
            float textWidth  = textPaint.measureText(label);
            float textHeight = 32f;
            float labelTop   = Math.max(0, top - textHeight);

            canvas.drawRect(left, labelTop, left + textWidth + 8, labelTop + textHeight, bgPaint);
            canvas.drawText(label, left + 4, labelTop + textHeight - 6, textPaint);
        }
        return annotated;
    }

    private int getClassColor(String className) {
        switch (className) {
            case "person":          return 0xFF4CAF50;  // green
            case "group of people": return 0xFF8BC34A;  // light green
            case "car":             return 0xFF2196F3;  // blue
            case "bus":             return 0xFFFF9800;  // orange
            case "motorcycle":      return 0xFF9C27B0;  // purple
            case "truck":           return 0xFF3F51B5;  // indigo
            case "bollards":        return 0xFFFFEB3B;  // yellow
            case "stairs":          return 0xFFFFC107;  // amber
            case "door":            return 0xFF795548;  // brown
            case "chair":           return 0xFF607D8B;  // blue-grey
            case "couch":           return 0xFF9E9E9E;  // grey
            case "table":           return 0xFF8D6E63;  // brown-light
            case "pothole":         return 0xFFF44336;  // red
            case "pole":            return 0xFFE91E63;  // pink
            case "dog":             return 0xFFFF5722;  // deep orange
            case "wall":            return 0xFF78909C;  // blue-grey
            case "glass wall":      return 0xFF00BCD4;  // cyan
            case "cabinet":         return 0xFF6D4C41;  // dark brown
            case "window":          return 0xFF26C6DA;  // light cyan
            case "tricycle":        return 0xFFAB47BC;  // light purple
            case "standing aircon": return 0xFF5C6BC0;  // indigo-light
            case "bench":           return 0xFFEF6C00;  // dark orange
            case "fence":           return 0xFFD4E157;  // lime
            case "gate":            return 0xFF8E24AA;  // deep purple
            case "refrigerator":    return 0xFF00897B;  // teal
            case "trash can":       return 0xFFE53935;  // red-dark
            case "tree":            return 0xFF2E7D32;  // dark green
            case "stall":           return 0xFFFF7043;  // light deep orange
            default:                return 0xFF00FFFF;  // cyan fallback
        }
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        byteBuffer.order(ByteOrder.nativeOrder());
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
            for (int j = 5; j < 32; j++) {
                if (output[j][i] > maxClassScore) {
                    maxClassScore = output[j][i];
                    classId = j - 4;
                }
            }

            if (maxClassScore < threshold) continue;

            // ── Per-class confidence override ──────────────────────────────
            // Door hallucinations (partial windows, chartboards, wall panels)
            // typically fire at 0.45–0.60. Real doors trigger at 0.65+.
            String className = getClassName(classId);
            if (className.equals("door") && maxClassScore < 0.60f) continue;
            if (className.equals("pothole") && maxClassScore < 0.35f) continue;
            if (className.equals("stairs") && maxClassScore < 0.35f) continue;

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

    /**
     * Returns a scale multiplier for each class based on its real-world physical size.
     * Naturally large objects (bus, truck) are penalized so their huge screen presence
     * does not trick the system into thinking they are dangerously close.
     * Naturally tiny objects (pothole, bollards) are boosted so a small bounding box
     * still registers as a high-severity threat when it is actually at the user's feet.
     */
    private float getScaleMultiplier(String className) {
        switch (className) {
            // ── Tiny objects: boost heavily ──────────────────────────────────
            case "pothole":          return 4.0f;  // ground-level, easy to miss
            case "bollards":         return 3.5f;  // narrow, small footprint
            case "pole":             return 3.0f;  // thin vertical obstacle
            // ── Medium objects: slight boost ────────────────────────────────
            case "stairs":           return 2.0f;  // spread across ground
            case "door":             return 1.5f;
            case "dog":              return 2.0f;
            // ── Normal-sized objects: no adjustment ─────────────────────────
            case "person":           return 1.0f;
            case "group of people": return 1.0f;
            case "chair":            return 1.2f;
            case "couch":            return 1.0f;
            case "table":            return 1.0f;
            case "tree":             return 0.8f;
            case "motorcycle":       return 0.9f;
            case "car":              return 0.7f;
            case "tricycle":         return 0.9f;  // replaces pedicab
            case "standing aircon":  return 1.2f;  // medium-sized indoor obstacle
            case "bench":            return 1.5f;  // low ground-level obstacle
            case "fence":            return 0.8f;  // usually seen at distance
            case "gate":             return 1.0f;  // similar to door
            case "refrigerator":     return 1.2f;  // large indoor obstacle
            case "trash can":        return 2.0f;  // narrow, trip hazard
            case "stall":            return 0.7f;  // large fixed structure
            // ── Very large objects: penalize so far-away does not trigger ────
            case "wall":             return 0.5f;
            case "glass wall":       return 0.5f;
            case "bus":              return 0.5f;
            case "truck":            return 0.5f;
            case "cabinet":          return 1.0f;  // indoor furniture, normal size
            case "window":           return 0.8f;  // suppressed from TTS (see findNearestObject)
            default:                 return 1.0f;
        }
    }

    /**
     * Selects the single most urgent threat from all detected objects using
     * a Dual-Layer Threat Score:
     *   Layer 1 — Class-Specific Scale Weighting: Adjusts raw bounding box area
     *             based on the object's real-world physical size (via getScaleMultiplier).
     *   Layer 2 — Directional Preference: Objects in the "ahead" sector receive
     *             a 2x multiplier because the direct path is always the priority
     *             collision zone for a blind user walking forward.
     */
    private Detection findNearestObject(List<Detection> detections) {
        if (detections.isEmpty()) return null;
        Detection nearest = null;
        float maxThreatScore = 0;
        for (Detection det : detections) {
            // Window is a training-only disambiguation class — not a navigation obstacle.
            // The model detects it to avoid door/cabinet false positives, but we never announce it.
            if (det.className.equals("window")) continue;

            // Raw bounding box area (pixels²)
            float rawArea = (det.x2 - det.x1) * (det.y2 - det.y1);

            // Layer 1: compensate for the object's real-world physical size
            float scaledArea = rawArea * getScaleMultiplier(det.className);

            // Layer 2: double the threat score if the object is in the direct path
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
                "stall"              // 27
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
        metrics.put("diagnosticsEnabled", diagnosticsEnabled);
        return metrics;
    }

    private void maybeLogMetrics(long calls) {
        long now = System.currentTimeMillis();
        if (calls % 20 != 0 && now - metricsLastLoggedAt < 15000) return;
        metricsLastLoggedAt = now;

        long avgInference = calls == 0 ? 0 : totalInferenceMs.get() / calls;
        long avgDetect = calls == 0 ? 0 : totalDetectMs.get() / calls;
        long avgFrameBytes = totalFramesReturned.get() == 0 ? 0 : totalFramePayloadBytes.get() / totalFramesReturned.get();
        Log.d(TAG, "[Perf] calls=" + calls + " avgInferenceMs=" + avgInference
                + " avgDetectMs=" + avgDetect + " avgFrameBytes=" + avgFrameBytes);
    }

    // ===== Detection Class =====

    private static class Detection {
        String className;
        float confidence;
        float x1, y1, x2, y2;
    }
}