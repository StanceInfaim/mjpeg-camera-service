package com.stanceinfaim.mjpegcamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

public class CameraStreamService extends Service {

    private static final String TAG = "MJPEGCamera";
    private static final int PORT = 8080;
    private static final String CHANNEL_ID = "mjpeg_camera";

    private ServerSocket serverSocket;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;

    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>(null);
    private String currentCameraId = "0";
    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        openCamera(currentCameraId);
        startHttpServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("camera")) {
            String cam = intent.getStringExtra("camera");
            if (!cam.equals(currentCameraId)) {
                switchCamera(cam);
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        closeCamera();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        cameraThread.quitSafely();
        super.onDestroy();
    }

    private void openCamera(String cameraId) {
        closeCamera();
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            Size chosen = pickSize(sizes, 1280, 720);

            imageReader = ImageReader.newInstance(chosen.getWidth(), chosen.getHeight(), ImageFormat.JPEG, 3);
            imageReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null) return;
                    ByteBuffer buf = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    latestFrame.set(bytes);
                }
            }, cameraHandler);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
                }
                @Override
                public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                }
            }, cameraHandler);

            currentCameraId = cameraId;
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed", e);
        }
    }

    private void startPreview() {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            cameraDevice.createCaptureSession(
                Arrays.asList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "setRepeatingRequest failed", e);
                        }
                    }
                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        Log.e(TAG, "Session configure failed");
                    }
                }, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "startPreview failed", e);
        }
    }

    private void closeCamera() {
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
        try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } } catch (Exception ignored) {}
        try { if (imageReader != null) { imageReader.close(); imageReader = null; } } catch (Exception ignored) {}
        latestFrame.set(null);
    }

    private void switchCamera(String cameraId) {
        cameraHandler.post(() -> openCamera(cameraId));
    }

    private void startHttpServer() {
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        new Thread(() -> handleClient(client)).start();
                    } catch (Exception e) {
                        if (running) Log.e(TAG, "accept error", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "server error", e);
            }
        }, "HttpServer").start();
    }

    private void handleClient(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) { client.close(); return; }
            String path = requestLine.split(" ")[1];

            if (path.startsWith("/front")) {
                switchCamera("1");
                sendText(client, "200 OK", "Switched to front camera");
            } else if (path.startsWith("/back")) {
                switchCamera("0");
                sendText(client, "200 OK", "Switched to back camera");
            } else if (path.startsWith("/shot") || path.startsWith("/capture")) {
                serveSnapshot(client);
            } else if (path.startsWith("/video") || path.equals("/") || path.startsWith("/stream")) {
                serveMjpeg(client);
            } else if (path.startsWith("/status")) {
                sendText(client, "200 OK", "camera=" + currentCameraId + " port=" + PORT);
            } else {
                sendText(client, "404 Not Found", "Unknown endpoint");
            }
        } catch (Exception e) {
            Log.e(TAG, "handleClient error", e);
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void serveMjpeg(Socket client) throws IOException {
        OutputStream out = client.getOutputStream();
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n\r\n";
        out.write(header.getBytes());
        out.flush();

        while (running && !client.isClosed()) {
            byte[] frame = latestFrame.get();
            if (frame == null) {
                try { Thread.sleep(50); } catch (Exception ignored) {}
                continue;
            }
            try {
                String part = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n";
                out.write(part.getBytes());
                out.write(frame);
                out.write("\r\n".getBytes());
                out.flush();
                Thread.sleep(66);
            } catch (Exception e) {
                break;
            }
        }
    }

    private void serveSnapshot(Socket client) throws IOException {
        byte[] frame = latestFrame.get();
        if (frame == null) {
            sendText(client, "503 Service Unavailable", "No frame yet");
            return;
        }
        OutputStream out = client.getOutputStream();
        String header = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n";
        out.write(header.getBytes());
        out.write(frame);
        out.flush();
    }

    private void sendText(Socket client, String status, String body) throws IOException {
        OutputStream out = client.getOutputStream();
        String resp = "HTTP/1.1 " + status + "\r\nContent-Type: text/plain\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        out.write(resp.getBytes());
        out.flush();
    }

    private Size pickSize(Size[] sizes, int targetW, int targetH) {
        return Arrays.stream(sizes)
            .min(Comparator.comparingInt(s ->
                Math.abs(s.getWidth() - targetW) + Math.abs(s.getHeight() - targetH)))
            .orElse(sizes[0]);
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "MJPEG Camera", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MJPEG Camera")
            .setContentText("Streaming on port " + PORT)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build();
    }
}
