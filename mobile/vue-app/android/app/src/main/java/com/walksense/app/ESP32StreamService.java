package com.walksense.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ESP32StreamService {
    private static final String TAG = "ESP32Stream";
    private static final int ESP32_PORT = 81;
    private static final String ESP32_PATH = "/stream";
    private static final int MAX_FRAME_SIZE = 100000;

    // Dynamic host — IP or .local hostname
    private final String host;
    private final Context context;

    private volatile boolean isStreaming = false;
    private Bitmap lastBitmap = null;
    private Socket socket;

    /**
     * @param host    IP address or mDNS hostname (e.g. "walksense-day.local")
     * @param context Android context — needed for NsdManager mDNS resolution
     */
    public ESP32StreamService(String host, Context context) {
        this.host = host;
        this.context = context;
    }

    /**
     * Legacy constructor for backward compatibility — context-free.
     * mDNS resolution will fall back to InetAddress.getByName() which
     * works on many Android devices but is not guaranteed.
     */
    public ESP32StreamService(String host) {
        this.host = host;
        this.context = null;
    }

    public interface StreamCallback {
        void onFrameReceived(Bitmap frame);
        void onError(String error);
        void onConnected();
    }

    /**
     * Resolves a hostname to an IP address string.
     * For .local mDNS hostnames, tries InetAddress resolution first
     * (works on Android 12+ and many earlier devices), then falls back
     * to NsdManager if context is available.
     * For plain IPs, returns them directly with no resolution needed.
     */
    private String resolveHost(String hostname) {
        // Plain IP — no resolution needed
        if (hostname.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            return hostname;
        }

        // Try InetAddress first — works on Android 12+ for .local
        try {
            Log.d(TAG, "Resolving hostname via InetAddress: " + hostname);
            InetAddress addr = InetAddress.getByName(hostname);
            String resolved = addr.getHostAddress();
            Log.d(TAG, "Resolved " + hostname + " → " + resolved);
            return resolved;
        } catch (Exception e) {
            Log.w(TAG, "InetAddress resolution failed for " + hostname + ": " + e.getMessage());
        }

        // Fallback: NsdManager resolution (Android context required)
        if (context != null && hostname.endsWith(".local")) {
            String serviceName = hostname.replace(".local", "");
            Log.d(TAG, "Trying NsdManager resolution for: " + serviceName);

            final String[] resolvedIp = { null };
            final CountDownLatch latch = new CountDownLatch(1);

            NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);

            NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    Log.w(TAG, "NsdManager resolve failed: " + errorCode);
                    latch.countDown();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    InetAddress addr = info.getHost();
                    if (addr != null) {
                        resolvedIp[0] = addr.getHostAddress();
                        Log.d(TAG, "NsdManager resolved " + hostname + " → " + resolvedIp[0]);
                    }
                    latch.countDown();
                }
            };

            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName(serviceName);
            serviceInfo.setServiceType("_http._tcp.");

            try {
                nsdManager.resolveService(serviceInfo, resolveListener);
                latch.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.w(TAG, "NsdManager resolution error: " + e.getMessage());
            }

            if (resolvedIp[0] != null) {
                return resolvedIp[0];
            }
        }

        // All resolution failed — return original hostname and let socket try
        Log.w(TAG, "Could not resolve " + hostname + " — using as-is");
        return hostname;
    }

    public void startStream(StreamCallback callback) {
        isStreaming = true;

        new Thread(() -> {
            InputStream inputStream = null;

            try {
                // Resolve hostname to IP before opening socket
                String resolvedHost = resolveHost(host);
                Log.d(TAG, "=== Opening raw socket to " + resolvedHost + ":" + ESP32_PORT + " ===");

                socket = new Socket(resolvedHost, ESP32_PORT);
                socket.setSoTimeout(0);
                socket.setKeepAlive(true);

                OutputStream out = socket.getOutputStream();
                String httpRequest =
                        "GET " + ESP32_PATH + " HTTP/1.1\r\n" +
                                "Host: " + resolvedHost + ":" + ESP32_PORT + "\r\n" +
                                "Connection: keep-alive\r\n" +
                                "\r\n";
                out.write(httpRequest.getBytes());
                out.flush();
                Log.d(TAG, "HTTP request sent to " + resolvedHost);

                inputStream = socket.getInputStream();

                // Skip HTTP response headers
                StringBuilder headers = new StringBuilder();
                int curr;
                while ((curr = inputStream.read()) != -1) {
                    headers.append((char) curr);
                    if (headers.toString().endsWith("\r\n\r\n")) break;
                }

                Log.d(TAG, "✓ Headers skipped, starting MJPEG parse for " + resolvedHost);
                if (callback != null) callback.onConnected();

                byte[] readBuffer = new byte[4096];
                byte[] frameBuffer = new byte[MAX_FRAME_SIZE];
                int frameBufferIndex = 0;
                boolean inFrame = false;
                int frameCount = 0;
                int prevByte = -1;

                while (isStreaming) {
                    int bytesRead = inputStream.read(readBuffer);
                    if (bytesRead == -1) {
                        Log.d(TAG, "Stream ended by server: " + resolvedHost);
                        break;
                    }

                    for (int i = 0; i < bytesRead; i++) {
                        int currentByte = readBuffer[i] & 0xFF;

                        if (!inFrame && prevByte == 0xFF && currentByte == 0xD8) {
                            inFrame = true;
                            frameBufferIndex = 0;
                            frameBuffer[frameBufferIndex++] = (byte) 0xFF;
                            frameBuffer[frameBufferIndex++] = (byte) 0xD8;
                            prevByte = currentByte;
                            continue;
                        }

                        if (inFrame) {
                            if (frameBufferIndex >= MAX_FRAME_SIZE - 1) {
                                Log.w(TAG, "Frame too large, resetting");
                                inFrame = false;
                                frameBufferIndex = 0;
                                prevByte = currentByte;
                                continue;
                            }

                            frameBuffer[frameBufferIndex++] = (byte) currentByte;

                            if (prevByte == 0xFF && currentByte == 0xD9) {
                                final byte[] frameData = new byte[frameBufferIndex];
                                System.arraycopy(frameBuffer, 0, frameData, 0, frameBufferIndex);

                                Bitmap bitmap = BitmapFactory.decodeByteArray(
                                        frameData, 0, frameData.length
                                );

                                if (bitmap != null) {
                                    frameCount++;
                                    if (callback != null) callback.onFrameReceived(bitmap);
                                    if (frameCount % 30 == 0) {
                                        Log.d(TAG, "✓ " + frameCount + " frames from " + resolvedHost);
                                    }
                                } else {
                                    Log.w(TAG, "Failed to decode frame from " + resolvedHost + ", size: " + frameBufferIndex);
                                }

                                inFrame = false;
                                frameBufferIndex = 0;
                            }
                        }

                        prevByte = currentByte;
                    }
                }

                Log.d(TAG, "Stream stopped [" + resolvedHost + "]. Total frames: " + frameCount);

            } catch (Exception e) {
                if (isStreaming) {
                    Log.e(TAG, "Stream error [" + host + "]: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    if (callback != null) callback.onError("Stream error: " + e.getMessage());
                } else {
                    Log.d(TAG, "Stream stopped intentionally [" + host + "]");
                }
            } finally {
                try { if (inputStream != null) inputStream.close(); } catch (Exception ignored) {}
                try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
                Log.d(TAG, "Socket cleaned up [" + host + "]");
            }
        }).start();
    }

    public void stopStream() {
        Log.d(TAG, "Stop requested [" + host + "]");
        isStreaming = false;
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Exception ignored) {}
    }

    public String getHost() {
        return host;
    }
}