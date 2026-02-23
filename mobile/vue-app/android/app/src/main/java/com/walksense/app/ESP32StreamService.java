package com.walksense.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class ESP32StreamService {
    private static final String TAG = "ESP32Stream";
    private static final String ESP32_HOST = "172.20.129.172";
    private static final int ESP32_PORT = 81;
    private static final String ESP32_PATH = "/stream";
    private static final int MAX_FRAME_SIZE = 100000;

    private volatile boolean isStreaming = false;
    private Bitmap lastBitmap = null;
    private Socket socket;

    public interface StreamCallback {
        void onFrameReceived(Bitmap frame);
        void onError(String error);
        void onConnected();
    }

    public void startStream(StreamCallback callback) {
        isStreaming = true;

        new Thread(() -> {
            InputStream inputStream = null;

            try {
                Log.d(TAG, "=== Opening raw socket connection ===");

                // Raw socket — bypasses OkHttp and its chunked transfer handling entirely
                socket = new Socket(ESP32_HOST, ESP32_PORT);
                socket.setSoTimeout(0);
                socket.setKeepAlive(true);

                // Send HTTP GET manually
                OutputStream out = socket.getOutputStream();
                String httpRequest =
                        "GET " + ESP32_PATH + " HTTP/1.1\r\n" +
                                "Host: " + ESP32_HOST + ":" + ESP32_PORT + "\r\n" +
                                "Connection: keep-alive\r\n" +
                                "\r\n";
                out.write(httpRequest.getBytes());
                out.flush();
                Log.d(TAG, "HTTP request sent");

                inputStream = socket.getInputStream();

                // Skip HTTP response headers — read until double CRLF
                StringBuilder headers = new StringBuilder();
                int curr;
                while ((curr = inputStream.read()) != -1) {
                    headers.append((char) curr);
                    if (headers.toString().endsWith("\r\n\r\n")) break;
                }

                Log.d(TAG, "✓ Headers skipped, starting MJPEG parse");
                if (callback != null) callback.onConnected();

                // Parse raw MJPEG stream
                byte[] readBuffer = new byte[4096];
                byte[] frameBuffer = new byte[MAX_FRAME_SIZE];
                int frameBufferIndex = 0;
                boolean inFrame = false;
                int frameCount = 0;
                int prevByte = -1;

                while (isStreaming) {
                    int bytesRead = inputStream.read(readBuffer);
                    if (bytesRead == -1) {
                        Log.d(TAG, "Stream ended by server");
                        break;
                    }

                    for (int i = 0; i < bytesRead; i++) {
                        int currentByte = readBuffer[i] & 0xFF;

                        // Detect JPEG start: 0xFF 0xD8
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

                            // Detect JPEG end: 0xFF 0xD9
                            if (prevByte == 0xFF && currentByte == 0xD9) {
                                final byte[] frameData = new byte[frameBufferIndex];
                                System.arraycopy(frameBuffer, 0, frameData, 0, frameBufferIndex);

                                Bitmap bitmap = BitmapFactory.decodeByteArray(
                                        frameData, 0, frameData.length
                                );

                                if (bitmap != null) {
                                    frameCount++;

                                    if (callback != null) {
                                        callback.onFrameReceived(bitmap);
                                    }

                                    if (frameCount > 1) {
                                        bitmap.recycle(); // Free memory
                                    }

                                    if (frameCount % 30 == 0) {
                                        Log.d(TAG, "✓ " + frameCount + " frames received");
                                    }

                                } else {
                                    Log.w(TAG, "Failed to decode frame, size: " + frameBufferIndex);
                                }

                                inFrame = false;
                                frameBufferIndex = 0;
                            }
                        }

                        prevByte = currentByte;
                    }
                }

                Log.d(TAG, "Stream stopped. Total frames: " + frameCount);

            } catch (Exception e) {
                if (isStreaming) {
                    Log.e(TAG, "Stream error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    if (callback != null) {
                        callback.onError("Stream error: " + e.getMessage());
                    }
                } else {
                    Log.d(TAG, "Stream stopped intentionally");
                }
            } finally {
                try { if (inputStream != null) inputStream.close(); } catch (Exception ignored) {}
                try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
                Log.d(TAG, "Socket cleaned up");
            }
        }).start();
    }

    public void stopStream() {
        Log.d(TAG, "Stop requested");
        isStreaming = false;
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Exception ignored) {}
    }
}