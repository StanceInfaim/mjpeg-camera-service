package com.stanceinfaim.mjpegcamera;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

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
    private PowerManager.WakeLock wakeLock;

    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>(null);
    private String currentCameraId = "0";
    private volatile boolean running = false;
    
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private boolean isCameraConnected = false;

    @Override
    public void onCreate() {
        super.onCreate();
        
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Критический краш! Перезапуск сервиса через 1 сек...", throwable);
            Intent amIntent = new Intent(getApplicationContext(), CameraStreamService.class);
            PendingIntent pendingIntent = PendingIntent.getForegroundService(
                    getApplicationContext(), 0, amIntent, PendingIntent.FLAG_IMMUTABLE);
            AlarmManager mgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (mgr != null) {
                mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent);
            }
            System.exit(2);
        });

        startForeground(1, buildNotification());
        
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MJPEGCamera::WakeLock");
            wakeLock.acquire();
        }

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
        if (cameraThread != null) cameraThread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        retryHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void openCamera(String cameraId) {
        try {
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.i(TAG, "Камера успешно открыта!");
                    isCameraConnected = true;
                    cameraDevice = camera;
                    currentCameraId = cameraId;
                    startCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "Камера отключена, переподключение...");
                    isCameraConnected = false;
                    closeCamera();
                    scheduleCameraRetry();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Ошибка камеры: " + error);
                    isCameraConnected = false;
                    closeCamera();
                    scheduleCameraRetry();
                }
            }, cameraHandler);

        } catch (Exception e) {
            Log.e(TAG, "Не удалось вызвать openCamera (система блокирует из фона). Пробуем снова...", e);
            scheduleCameraRetry();
        }
    }

    private void scheduleCameraRetry() {
        if (!isCameraConnected) {
            retryHandler.removeCallbacksAndMessages(null);
            retryHandler.postDelayed(() -> openCamera(currentCameraId), 3000); 
        }
    }
    
    private void startCaptureSession() {
        try {
            closeCameraStructures();

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map != null ? map.getOutputSizes(ImageFormat.JPEG) : new Size[]{new Size(1280, 720)};
            Size optimalSize = pickSize(sizes, 1280, 720);

            imageReader = ImageReader.newInstance(optimalSize.getWidth(), optimalSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image != null) {
                        Image.Plane[] planes = image.getPlanes();
                        if (planes.length > 0) {
                            ByteBuffer buffer = planes[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            latestFrame.set(bytes);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка чтения кадра из ImageReader", e);
                }
            }, cameraHandler);

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            cameraDevice.createCaptureSession(
                Arrays.asList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        } catch (Exception e) {
                            Log.e(TAG, "setRepeatingRequest failed", e);
                        }
                    }
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.e(TAG, "Конфигурация сессии камеры провалилась");
                        scheduleCameraRetry();
                    }
                }, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "Ошибка в startCaptureSession", e);
            scheduleCameraRetry();
        }
    }

    private void closeCameraStructures() {
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
        try { if (imageReader != null) { imageReader.close(); imageReader = null; } } catch (Exception ignored) {}
    }

    private void closeCamera() {
        closeCameraStructures();
        try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } } catch (Exception ignored) {}
        latestFrame.set(null);
    }

    private void switchCamera(String cameraId) {
        cameraHandler.post(() -> {
            isCameraConnected = false;
            closeCamera();
            openCamera(cameraId);
        });
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
            String[] tokens = requestLine.split(" ");
            if (tokens.length < 2) { client.close(); return; }
            String path = tokens[1];

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
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MJPEG Camera")
            .setContentText("Streaming on port " + PORT)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build();
    }
}
