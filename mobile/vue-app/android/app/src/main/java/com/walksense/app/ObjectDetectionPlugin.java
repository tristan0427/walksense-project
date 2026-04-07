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

@CapacitorPlugin(name = "ObjectDetection")
public class ObjectDetectionPlugin extends Plugin {

    private static final String TAG = "ObjectDetection";
    private static final int INPUT_SIZE = 320;

    // Camera type constants
    public static final String CAM_DAY   = "day";
    public static final String CAM_NIGHT = "night";

    private Interpreter tflite;

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

    // ===== Distance & Direction =====

    private String estimateDistance(float bboxArea, float frameArea) {
        float ratio = bboxArea / frameArea;
        if (ratio > 0.30f) return "very close";
        else if (ratio > 0.15f) return "close";
        else if (ratio > 0.05f) return "medium distance";
        else return "far";
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
            MappedByteBuffer model = FileUtil.loadMappedFile(getContext(), "walksense_float16.tflite");
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
        Log.d(TAG, "✓ Active camera switched to: " + cam);

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
        try {
            resized = Bitmap.createScaledBitmap(frameToProcess, INPUT_SIZE, INPUT_SIZE, true);
            ByteBuffer inputBuffer = convertBitmapToByteBuffer(resized);

            float[][][] outputTransposed = new float[1][24][2100];
            tflite.run(inputBuffer, outputTransposed);

            Double confidenceParam = call.getDouble("confidence", 0.3);
            float confidenceThreshold = confidenceParam != null ? confidenceParam.floatValue() : 0.3f;

            List<Detection> detections = postProcessTransposed(outputTransposed[0], confidenceThreshold);
            Log.d(TAG, "Detections [" + activeCamera + "]: " + detections.size());

            Detection nearest = findNearestObject(detections);

            JSObject ret = new JSObject();
            if (nearest != null) {
                float bboxArea  = (nearest.x2 - nearest.x1) * (nearest.y2 - nearest.y1);
                float frameArea = INPUT_SIZE * INPUT_SIZE;

                String distance  = estimateDistance(bboxArea, frameArea);
                String direction = getDirection((nearest.x1 + nearest.x2) / 2, INPUT_SIZE);

                JSObject nearestObj = new JSObject();
                nearestObj.put("class",      nearest.className);
                nearestObj.put("distance",   distance);
                nearestObj.put("direction",  direction);
                nearestObj.put("confidence", nearest.confidence);
                nearestObj.put("camera",     activeCamera);  // tell Vue which cam detected it

                ret.put("nearest", nearestObj);
            }

            ret.put("success", true);
            ret.put("activeCamera", activeCamera);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e(TAG, "Detection failed", e);
            call.reject("Detection error: " + e.getMessage());
        } finally {
            if (resized != null && !resized.isRecycled()) resized.recycle();
            if (frameToProcess != null && !frameToProcess.isRecycled()) frameToProcess.recycle();
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
                    Log.d(TAG, "[Scanner] Scanning " + subnet + ".1-254 on port " + port);

                    ExecutorService executor = Executors.newFixedThreadPool(30);
                    List<Future<JSObject>> futures = new ArrayList<>();

                    for (int i = 1; i <= 254; i++) {
                        final String ip = subnet + "." + i;
                        futures.add(executor.submit(() -> pingHost(ip, port, path, timeout)));
                    }

                    for (Future<JSObject> future : futures) {
                        try {
                            JSObject result = future.get(timeout + 1000, TimeUnit.MILLISECONDS);
                            if (result != null) {
                                allResults.add(result);
                                Log.d(TAG, "[Scanner] ✅ Found board: " + result.toString());
                            }
                        } catch (Exception e) {
                            // timeout or error — expected for dead IPs
                        }
                    }

                    executor.shutdownNow();

                    // Stop scanning remaining subnets if both cameras found
                    if (allResults.size() >= 2) {
                        Log.d(TAG, "[Scanner] Both cameras found — stopping early.");
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
    private JSObject pingHost(String ip, int port, String path, int timeout) {
        Socket socket = null;
        try {
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
                byteBuffer.putFloat(((val >> 8)  & 0xFF) / 255.0f);
                byteBuffer.putFloat((val & 0xFF)          / 255.0f);
            }
        }
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
            for (int j = 5; j < 24; j++) {
                if (output[j][i] > maxClassScore) {
                    maxClassScore = output[j][i];
                    classId = j - 4;
                }
            }

            if (maxClassScore < threshold) continue;

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
            case "cat":              return 2.5f;
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
            case "vehicle":          return 0.7f;
            // ── Very large objects: penalize so far-away does not trigger ────
            case "wall":             return 0.5f;
            case "glass wall":       return 0.5f;
            case "bus":              return 0.5f;
            case "truck":            return 0.5f;
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
            // Raw bounding box area (pixels²)
            float rawArea = (det.x2 - det.x1) * (det.y2 - det.y1);

            // Layer 1: compensate for the object's real-world physical size
            float scaledArea = rawArea * getScaleMultiplier(det.className);

            // Layer 2: double the threat score if the object is in the direct path
            float centerX = (det.x1 + det.x2) / 2f;
            String dir = getDirection(centerX, INPUT_SIZE);
            float directionalMultiplier = dir.equals("ahead") ? 2.0f : 1.0f;

            float threatScore = scaledArea * directionalMultiplier;

            Log.d(TAG, String.format(
                "[Threat] %s | rawArea=%.0f | scaled=%.0f | dir=%s | score=%.0f",
                det.className, rawArea, scaledArea, dir, threatScore
            ));

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
                "vehicle",           // 2
                "car",               // 3
                "bus",               // 4
                "motorcycle",        // 5
                "truck",             // 6
                "bollards",          // 7
                "stairs",            // 8
                "tree",              // 9
                "door",              // 10
                "chair",             // 11
                "couch",             // 12
                "table",             // 13
                "pothole",           // 14
                "pole",              // 15
                "cat",               // 16
                "dog",               // 17
                "wall",              // 18
                "glass wall"         // 19
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
        if (tflite != null) { tflite.close(); isModelLoaded = false; }
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

    // ===== Detection Class =====

    private static class Detection {
        String className;
        float confidence;
        float x1, y1, x2, y2;
    }
}