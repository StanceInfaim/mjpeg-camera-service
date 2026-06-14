package com.stanceinfaim.mjpegcamera;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
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
import android.util.Range;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class CameraStreamService extends Service {

    private static final String TAG = "MJPEGCamera";
    private static final String CHANNEL_ID = "mjpeg_camera";
    private static final String PREFS_NAME = "camera_settings";
    private static final int CLIENT_SO_TIMEOUT_MS = 10000;

    private ServerSocket serverSocket;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private PowerManager.WakeLock wakeLock;

    // Frame handoff: latestFrame guarded together with frameSeq via frameLock for
    // new-frame signalling; latestFrame stays atomic for the lock-free snapshot read.
    private final Object frameLock = new Object();
    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>(null);
    private long frameSeq = 0;

    private volatile boolean running = false;
    private volatile boolean isCameraConnected = false;

    // Settings — all volatile for cross-thread visibility
    private SharedPreferences prefs;
    private volatile String currentCameraId = "0";
    private volatile int settingResW = 1280;
    private volatile int settingResH = 720;
    private volatile int settingFps = 15;
    private volatile int settingQuality = 80;
    private volatile int settingRotation = 0;
    private volatile boolean settingFlip = false;
    private volatile int settingExposure = 0;
    private volatile boolean settingAfContinuous = true;
    private volatile int settingPort = 8080;

    // Populated on camera open, read from HTTP threads — acceptable staleness
    private volatile int exposureMin = -4;
    private volatile int exposureMax = 4;
    private volatile boolean usingYuv = false;
    private volatile List<Size> availableSizes = new ArrayList<>();
    private volatile Set<String> jpegSizeKeys = new HashSet<>(); // "WxH" sizes with hardware JPEG

    // Reused only on cameraHandler (single thread) to avoid per-frame allocation
    private byte[] nv21Buffer;

    private final Runnable retryRunnable = () -> openCamera(currentCameraId);

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Критический краш! Перезапуск через 1 сек...", throwable);
            Intent amIntent = new Intent(getApplicationContext(), CameraStreamService.class);
            PendingIntent pi = PendingIntent.getForegroundService(
                    getApplicationContext(), 0, amIntent, PendingIntent.FLAG_IMMUTABLE);
            AlarmManager mgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (mgr != null) mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pi);
            System.exit(2);
        });

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadPrefs();

        startForegroundCompat();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MJPEGCamera::WakeLock");
            wakeLock.acquire();
        }

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        cameraHandler.post(() -> openCamera(currentCameraId));
        startHttpServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
        closeCamera();
        if (cameraThread != null) cameraThread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        // Wake any streaming threads so they can exit promptly
        synchronized (frameLock) { frameSeq++; frameLock.notifyAll(); }
        super.onDestroy();
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(1, n);
        }
    }

    // ── Preferences ──────────────────────────────────────────────────────────

    private void loadPrefs() {
        currentCameraId = prefs.getString("camera_id", "0");
        settingResW     = prefs.getInt("res_w", 1280);
        settingResH     = prefs.getInt("res_h", 720);
        settingFps      = prefs.getInt("fps", 15);
        settingQuality  = prefs.getInt("quality", 80);
        settingRotation = prefs.getInt("rotation", 0);
        settingFlip     = prefs.getBoolean("flip", false);
        settingExposure = prefs.getInt("exposure", 0);
        settingAfContinuous = prefs.getBoolean("af_continuous", true);
        settingPort     = prefs.getInt("port", 8080);
    }

    private void savePrefs() {
        prefs.edit()
            .putString("camera_id", currentCameraId)
            .putInt("res_w", settingResW)
            .putInt("res_h", settingResH)
            .putInt("fps", settingFps)
            .putInt("quality", settingQuality)
            .putInt("rotation", settingRotation)
            .putBoolean("flip", settingFlip)
            .putInt("exposure", settingExposure)
            .putBoolean("af_continuous", settingAfContinuous)
            .putInt("port", settingPort)
            .apply();
    }

    // ── Camera (all camera ops run on cameraHandler) ──────────────────────────

    private void openCamera(String cameraId) {
        try {
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(cameraId);
            Range<Integer> expRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (expRange != null) {
                exposureMin = expRange.getLower();
                exposureMax = expRange.getUpper();
            }

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    Log.i(TAG, "Камера открыта: " + cameraId);
                    isCameraConnected = true;
                    cameraDevice = camera;
                    currentCameraId = cameraId;
                    startCaptureSession();
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    isCameraConnected = false;
                    closeCamera();
                    scheduleCameraRetry();
                }
                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "Ошибка камеры: " + error);
                    isCameraConnected = false;
                    closeCamera();
                    scheduleCameraRetry();
                }
            }, cameraHandler);

        } catch (Exception e) {
            Log.e(TAG, "openCamera failed, retry in 3s", e);
            scheduleCameraRetry();
        }
    }

    private void scheduleCameraRetry() {
        if (!isCameraConnected) {
            cameraHandler.removeCallbacks(retryRunnable);
            cameraHandler.postDelayed(retryRunnable, 3000);
        }
    }

    private void startCaptureSession() {
        try {
            closeCameraStructures();

            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Size[] jpegSizes = map != null ? map.getOutputSizes(ImageFormat.JPEG) : new Size[0];
            Size[] yuvSizes  = map != null ? map.getOutputSizes(ImageFormat.YUV_420_888) : new Size[0];
            if (jpegSizes.length == 0 && yuvSizes.length == 0)
                jpegSizes = new Size[]{new Size(1280, 720)};

            // Track which sizes can use hardware JPEG
            Set<String> jpegKeys = new HashSet<>();
            for (Size s : jpegSizes) jpegKeys.add(key(s.getWidth(), s.getHeight()));
            jpegSizeKeys = jpegKeys;

            // Combined sorted list (JPEG first so duplicates keep JPEG preference)
            LinkedHashSet<Size> combined = new LinkedHashSet<>(Arrays.asList(jpegSizes));
            combined.addAll(Arrays.asList(yuvSizes));
            List<Size> sorted = new ArrayList<>(combined);
            sorted.sort((a, b) -> b.getWidth() * b.getHeight() - a.getWidth() * a.getHeight());
            availableSizes = sorted;

            Size optimal = pickSize(sorted, settingResW, settingResH);
            settingResW = optimal.getWidth();
            settingResH = optimal.getHeight();

            boolean useYuv = !jpegKeys.contains(key(optimal.getWidth(), optimal.getHeight()));
            usingYuv = useYuv;
            int format = useYuv ? ImageFormat.YUV_420_888 : ImageFormat.JPEG;
            nv21Buffer = null; // size may have changed; reallocated lazily in yuvToJpeg
            Log.i(TAG, "Capture: " + optimal.getWidth() + "x" + optimal.getHeight()
                + " format=" + (useYuv ? "YUV" : "JPEG"));

            imageReader = ImageReader.newInstance(optimal.getWidth(), optimal.getHeight(), format, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null) return;
                    byte[] bytes = usingYuv ? yuvToJpeg(image) : jpegFromImage(image);
                    if (bytes != null) publishFrame(bytes);
                } catch (Exception e) {
                    Log.e(TAG, "frame read error", e);
                }
            }, cameraHandler);

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            applyRequestParams(builder, ch);

            cameraDevice.createCaptureSession(
                Arrays.asList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        } catch (Exception e) {
                            Log.e(TAG, "setRepeatingRequest failed", e);
                        }
                    }
                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        Log.e(TAG, "Session config failed");
                        scheduleCameraRetry();
                    }
                }, cameraHandler);

        } catch (Exception e) {
            Log.e(TAG, "startCaptureSession error", e);
            scheduleCameraRetry();
        }
    }

    private void publishFrame(byte[] bytes) {
        synchronized (frameLock) {
            latestFrame.set(bytes);
            frameSeq++;
            frameLock.notifyAll();
        }
    }

    private void applyRequestParams(CaptureRequest.Builder b, CameraCharacteristics ch) {
        if (!usingYuv) {
            b.set(CaptureRequest.JPEG_QUALITY, (byte) settingQuality);
            b.set(CaptureRequest.JPEG_ORIENTATION, settingRotation);
        }
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            Math.max(exposureMin, Math.min(exposureMax, settingExposure)));
        b.set(CaptureRequest.CONTROL_AF_MODE, settingAfContinuous
            ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            : CaptureRequest.CONTROL_AF_MODE_OFF);

        // Lock camera to our target FPS so AE doesn't slow exposure below it
        Range<Integer>[] fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (fpsRanges != null && fpsRanges.length > 0) {
            Range<Integer> best = null;
            for (Range<Integer> r : fpsRanges) {
                if (r.getLower() <= settingFps && r.getUpper() >= settingFps) {
                    if (best == null || r.getLower() > best.getLower()) best = r;
                }
            }
            if (best == null) {
                for (Range<Integer> r : fpsRanges) {
                    if (best == null || Math.abs(r.getUpper() - settingFps) < Math.abs(best.getUpper() - settingFps))
                        best = r;
                }
            }
            if (best != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best);
        }
    }

    private void applyLiveSettings() {
        cameraHandler.post(() -> {
            if (captureSession == null || cameraDevice == null || imageReader == null) return;
            try {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(currentCameraId);
                CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(imageReader.getSurface());
                applyRequestParams(b, ch);
                captureSession.setRepeatingRequest(b.build(), null, cameraHandler);
            } catch (Exception e) {
                Log.e(TAG, "applyLiveSettings error", e);
            }
        });
    }

    private void switchCamera(String cameraId) {
        cameraHandler.post(() -> {
            isCameraConnected = false;
            closeCamera();
            openCamera(cameraId);
        });
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

    // ── Frame encoding (runs only on cameraHandler) ───────────────────────────

    private byte[] jpegFromImage(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buf = planes[0].getBuffer();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        // Rotation handled by JPEG_ORIENTATION (hardware); only flip needs software
        if (settingFlip) bytes = transformJpeg(bytes, 0, true);
        return bytes;
    }

    private byte[] yuvToJpeg(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRowStride  = planes[0].getRowStride();
        int uvPixStride = planes[1].getPixelStride();
        int uvRowStride = planes[1].getRowStride();

        int needed = w * h + 2 * (w / 2) * (h / 2);
        if (nv21Buffer == null || nv21Buffer.length != needed) nv21Buffer = new byte[needed];
        byte[] nv21 = nv21Buffer;

        // Convert YUV_420_888 → NV21 (Y plane + interleaved VU)
        int pos = 0;
        for (int row = 0; row < h; row++) {
            yBuf.position(row * yRowStride);
            yBuf.get(nv21, pos, w);
            pos += w;
        }
        for (int row = 0; row < h / 2; row++) {
            int base = row * uvRowStride;
            for (int col = 0; col < w / 2; col++) {
                int i = base + col * uvPixStride;
                nv21[pos++] = vBuf.get(i);
                nv21[pos++] = uBuf.get(i);
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(needed / 4);
        new YuvImage(nv21, ImageFormat.NV21, w, h, null)
            .compressToJpeg(new Rect(0, 0, w, h), settingQuality, baos);
        byte[] jpeg = baos.toByteArray();

        // YUV capture ignores JPEG_ORIENTATION, apply rotation/flip in software
        if (settingRotation != 0 || settingFlip)
            jpeg = transformJpeg(jpeg, settingRotation, settingFlip);
        return jpeg;
    }

    private byte[] transformJpeg(byte[] input, int rotation, boolean flip) {
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(input, 0, input.length);
            if (bmp == null) return input;
            Matrix m = new Matrix();
            if (rotation != 0) m.postRotate(rotation);
            if (flip) m.postScale(-1f, 1f, bmp.getWidth() / 2f, bmp.getHeight() / 2f);
            Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, false);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length);
            out.compress(Bitmap.CompressFormat.JPEG, settingQuality, baos);
            if (out != bmp) out.recycle();
            bmp.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "transformJpeg error", e);
            return input;
        }
    }

    private static String key(int w, int h) { return w + "x" + h; }

    private Size pickSize(List<Size> sizes, int tw, int th) {
        return sizes.stream()
            .min(Comparator.comparingInt(s -> Math.abs(s.getWidth() - tw) + Math.abs(s.getHeight() - th)))
            .orElse(sizes.get(0));
    }

    // ── HTTP Server ──────────────────────────────────────────────────────────

    private void startHttpServer() {
        running = true;
        new Thread(() -> {
            ServerSocket ss;
            try {
                ss = new ServerSocket(settingPort);
                ss.setReuseAddress(true);
            } catch (IOException e) {
                Log.e(TAG, "Cannot bind port " + settingPort, e);
                return;
            }
            serverSocket = ss;
            Log.i(TAG, "HTTP server on port " + settingPort);
            while (running && ss == serverSocket) {
                try {
                    Socket client = ss.accept();
                    new Thread(() -> handleClient(client), "HttpClient").start();
                } catch (IOException e) {
                    if (running && ss == serverSocket) Log.e(TAG, "accept error", e);
                }
            }
            try { ss.close(); } catch (Exception ignored) {}
        }, "HttpServer").start();
    }

    private void restartHttpServer() {
        ServerSocket old = serverSocket;
        serverSocket = null; // signals the old accept loop to exit
        try { if (old != null) old.close(); } catch (Exception ignored) {}
        startHttpServer();
        updateNotification();
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(CLIENT_SO_TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] tokens = requestLine.split(" ");
            if (tokens.length < 2) return;
            String method = tokens[0];
            String path = tokens[1].split("\\?")[0];
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.length() >= 15 && line.regionMatches(true, 0, "content-length:", 0, 15)) {
                    try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
                }
            }

            String body = "";
            if ("POST".equals(method) && contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = reader.read(buf, read, contentLength - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read);
            }

            switch (path) {
                case "/settings":
                    serveSettingsPage(client);
                    break;
                case "/api/settings":
                    if ("GET".equals(method))       serveSettingsJson(client);
                    else if ("POST".equals(method)) applySettings(client, body);
                    else sendText(client, "405 Method Not Allowed", "Method not allowed");
                    break;
                case "/front":
                    currentCameraId = "1"; savePrefs(); switchCamera("1");
                    sendJson(client, "200 OK", "{\"ok\":true,\"camera\":\"1\"}");
                    break;
                case "/back":
                    currentCameraId = "0"; savePrefs(); switchCamera("0");
                    sendJson(client, "200 OK", "{\"ok\":true,\"camera\":\"0\"}");
                    break;
                case "/shot":
                case "/capture":
                    serveSnapshot(client);
                    break;
                case "/":
                case "/video":
                case "/stream":
                    serveMjpeg(client);
                    break;
                case "/status":
                    sendJson(client, "200 OK",
                        "{\"camera\":\"" + currentCameraId + "\",\"port\":" + settingPort +
                        ",\"resolution\":\"" + settingResW + "x" + settingResH +
                        "\",\"fps\":" + settingFps + "}");
                    break;
                default:
                    sendText(client, "404 Not Found", "Not found");
            }
        } catch (Exception e) {
            Log.e(TAG, "handleClient error", e);
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void serveMjpeg(Socket client) throws IOException {
        OutputStream out = new BufferedOutputStream(client.getOutputStream(), 65536);
        out.write(("HTTP/1.1 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        long lastSeq = -1;
        long lastSend = 0;
        while (running) {
            byte[] frame;
            // Block until a genuinely new frame is published — no duplicate sends, no busy-poll
            synchronized (frameLock) {
                while (running && frameSeq == lastSeq) {
                    try { frameLock.wait(1000); } catch (InterruptedException e) { return; }
                }
                if (!running) break;
                frame = latestFrame.get();
                lastSeq = frameSeq;
            }
            if (frame == null) continue;

            // Pace to the configured FPS cap (camera may run faster than we want to send)
            long interval = 1000L / Math.max(1, settingFps);
            long since = System.currentTimeMillis() - lastSend;
            if (since < interval) {
                try { Thread.sleep(interval - since); } catch (InterruptedException e) { return; }
                frame = latestFrame.get(); // grab freshest after the pacing sleep
                if (frame == null) continue;
            }

            try {
                out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                    + frame.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(frame);
                out.write(CRLF);
                out.flush();
                lastSend = System.currentTimeMillis();
            } catch (IOException e) {
                break; // client disconnected
            }
        }
    }

    private void serveSnapshot(Socket client) throws IOException {
        byte[] frame = latestFrame.get();
        if (frame == null) { sendText(client, "503 Service Unavailable", "No frame yet"); return; }
        OutputStream out = client.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "
            + frame.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(frame);
        out.flush();
    }

    private void serveSettingsPage(Socket client) throws IOException {
        byte[] html = SETTINGS_HTML.getBytes(StandardCharsets.UTF_8);
        OutputStream out = client.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: " + html.length + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        out.write(html);
        out.flush();
    }

    private void serveSettingsJson(Socket client) throws Exception {
        Set<String> jpegKeys = jpegSizeKeys;
        JSONArray sizes = new JSONArray();
        for (Size s : availableSizes) {
            JSONObject o = new JSONObject();
            o.put("w", s.getWidth());
            o.put("h", s.getHeight());
            o.put("yuv", !jpegKeys.contains(key(s.getWidth(), s.getHeight())));
            sizes.put(o);
        }
        JSONObject json = new JSONObject();
        json.put("camera_id", currentCameraId);
        json.put("resolution_w", settingResW);
        json.put("resolution_h", settingResH);
        json.put("fps", settingFps);
        json.put("quality", settingQuality);
        json.put("rotation", settingRotation);
        json.put("flip", settingFlip);
        json.put("exposure", settingExposure);
        json.put("exposure_min", exposureMin);
        json.put("exposure_max", exposureMax);
        json.put("af_mode", settingAfContinuous ? "continuous" : "fixed");
        json.put("port", settingPort);
        json.put("resolutions", sizes);
        sendJson(client, "200 OK", json.toString());
    }

    private void applySettings(Socket client, String body) throws Exception {
        JSONObject req;
        try { req = new JSONObject(body); }
        catch (Exception e) { sendJson(client, "400 Bad Request", "{\"ok\":false,\"error\":\"invalid JSON\"}"); return; }

        String newCameraId  = req.optString("camera_id", currentCameraId);
        int newResW         = req.optInt("resolution_w", settingResW);
        int newResH         = req.optInt("resolution_h", settingResH);
        int newFps          = clamp(req.optInt("fps", settingFps), 1, 60);
        int newQuality      = clamp(req.optInt("quality", settingQuality), 10, 95);
        int newRotation     = snapRotation(req.optInt("rotation", settingRotation));
        boolean newFlip     = req.optBoolean("flip", settingFlip);
        int newExposure     = clamp(req.optInt("exposure", settingExposure), exposureMin, exposureMax);
        boolean newAfCont   = "continuous".equals(req.optString("af_mode", settingAfContinuous ? "continuous" : "fixed"));
        int newPort         = clamp(req.optInt("port", settingPort), 1024, 65535);

        boolean switchCam      = !newCameraId.equals(currentCameraId);
        boolean restartSession = !switchCam && (newResW != settingResW || newResH != settingResH);
        boolean restartPort    = newPort != settingPort;

        currentCameraId     = newCameraId;
        settingResW         = newResW;
        settingResH         = newResH;
        settingFps          = newFps;
        settingQuality      = newQuality;
        settingRotation     = newRotation;
        settingFlip         = newFlip;
        settingExposure     = newExposure;
        settingAfContinuous = newAfCont;
        settingPort         = newPort;
        savePrefs();

        if (switchCam)           switchCamera(newCameraId);
        else if (restartSession) cameraHandler.post(this::startCaptureSession);
        else                     applyLiveSettings();

        JSONObject resp = new JSONObject();
        resp.put("ok", true);
        resp.put("port_changed", restartPort);
        if (restartPort) resp.put("new_port", newPort);
        sendJson(client, "200 OK", resp.toString());

        if (restartPort) new Handler(Looper.getMainLooper()).postDelayed(this::restartHttpServer, 300);
    }

    private void sendText(Socket client, String status, String body) throws IOException {
        sendBody(client, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void sendJson(Socket client, String status, String body) throws IOException {
        sendBody(client, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void sendBody(Socket client, String status, String contentType, byte[] bytes) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + contentType
            + "\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int snapRotation(int r) {
        r = ((r % 360) + 360) % 360;
        if (r < 45 || r >= 315) return 0;
        if (r < 135) return 90;
        if (r < 225) return 180;
        return 270;
    }

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    // ── Notification ─────────────────────────────────────────────────────────

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "MJPEG Camera", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MJPEG Camera")
            .setContentText("Streaming on :" + settingPort)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(1, buildNotification());
    }

    // ── Settings HTML (static, compiled to single string constant) ───────────

    private static final String SETTINGS_HTML =
        "<!DOCTYPE html>\n" +
        "<html lang='en'>\n" +
        "<head>\n" +
        "<meta charset='utf-8'>\n" +
        "<meta name='viewport' content='width=device-width,initial-scale=1'>\n" +
        "<title>Camera Settings</title>\n" +
        "<style>\n" +
        "*{box-sizing:border-box;margin:0;padding:0}\n" +
        "body{font-family:sans-serif;background:#0d0d0d;color:#ddd;padding:24px 16px;max-width:460px;margin:auto}\n" +
        "h1{font-size:1.05em;font-weight:600;color:#fff;margin-bottom:20px;letter-spacing:.02em}\n" +
        ".f{margin-bottom:13px}\n" +
        "label{display:block;font-size:.72em;color:#666;margin-bottom:4px;text-transform:uppercase;letter-spacing:.05em}\n" +
        "select,input[type=number]{width:100%;padding:8px 10px;background:#1a1a1a;color:#ddd;border:1px solid #2e2e2e;border-radius:5px;font-size:.93em}\n" +
        "input[type=range]{width:100%;cursor:pointer;accent-color:#1a7a4a}\n" +
        ".row{display:flex;gap:10px;align-items:center}\n" +
        ".row input{flex:1}\n" +
        ".rv{min-width:34px;text-align:right;font-size:.88em;color:#888}\n" +
        ".sep{border:none;border-top:1px solid #1e1e1e;margin:16px 0}\n" +
        ".btn{display:block;width:100%;margin-top:20px;padding:11px;background:#1a7a4a;color:#fff;border:none;border-radius:5px;font-size:.97em;cursor:pointer;font-weight:600}\n" +
        ".btn:hover{background:#1e9058}.btn:active{background:#156038}\n" +
        "#msg{margin-top:10px;text-align:center;font-size:.85em;min-height:18px}\n" +
        ".ok{color:#4caf82}.err{color:#e05252}\n" +
        ".links{margin-top:22px;font-size:.82em}\n" +
        ".links a{color:#4a9eff;text-decoration:none;margin-right:14px}\n" +
        ".links a:hover{text-decoration:underline}\n" +
        "</style>\n" +
        "</head>\n" +
        "<body>\n" +
        "<h1>Camera Settings</h1>\n" +
        "<div class='f'><label>Camera</label>\n" +
        "<select id='camera_id'><option value='0'>Back (0)</option><option value='1'>Front (1)</option></select></div>\n" +
        "<div class='f'><label>Resolution</label><select id='resolution'><option>Loading...</option></select></div>\n" +
        "<div class='f'><label>FPS</label>\n" +
        "<select id='fps'><option>5</option><option>10</option><option>15</option><option>20</option><option>25</option><option>30</option></select></div>\n" +
        "<div class='f'><label>JPEG Quality</label>\n" +
        "<select id='quality'><option value='50'>50%</option><option value='65'>65%</option><option value='75'>75%</option>" +
        "<option value='80'>80%</option><option value='85'>85%</option><option value='90'>90%</option><option value='95'>95%</option></select></div>\n" +
        "<div class='f'><label>Rotation</label>\n" +
        "<select id='rotation'><option value='0'>0°</option><option value='90'>90°</option><option value='180'>180°</option><option value='270'>270°</option></select></div>\n" +
        "<div class='f'><label>Horizontal Flip</label>\n" +
        "<select id='flip'><option value='false'>Off</option><option value='true'>On</option></select></div>\n" +
        "<hr class='sep'>\n" +
        "<div class='f'><label>Exposure Compensation</label>\n" +
        "<div class='row'><input type='range' id='exposure' step='1' min='-4' max='4' value='0'><span class='rv' id='expv'>0</span></div></div>\n" +
        "<div class='f'><label>Autofocus</label>\n" +
        "<select id='af_mode'><option value='continuous'>Continuous</option><option value='fixed'>Fixed</option></select></div>\n" +
        "<hr class='sep'>\n" +
        "<div class='f'><label>HTTP Port</label><input type='number' id='port' min='1024' max='65535' value='8080'></div>\n" +
        "<button class='btn' onclick='save()'>Apply</button>\n" +
        "<div id='msg'></div>\n" +
        "<div class='links'><a href='/stream'>Stream</a><a href='/shot'>Snapshot</a><a href='/status'>Status</a></div>\n" +
        "<script>\n" +
        "var D={};\n" +
        "function sv(id,v){var e=document.getElementById(id);if(e)e.value=v;}\n" +
        "fetch('/api/settings').then(function(r){return r.json();}).then(function(d){\n" +
        "  D=d;\n" +
        "  var sel=document.getElementById('resolution');\n" +
        "  sel.innerHTML='';\n" +
        "  var res=d.resolutions||[];\n" +
        "  if(res.length===0){\n" +
        "    var o=document.createElement('option');\n" +
        "    o.value=d.resolution_w+'x'+d.resolution_h;\n" +
        "    o.text=d.resolution_w+'×'+d.resolution_h;\n" +
        "    sel.appendChild(o);\n" +
        "  } else {\n" +
        "    res.forEach(function(r){\n" +
        "      var o=document.createElement('option');\n" +
        "      o.value=r.w+'x'+r.h;\n" +
        "      o.text=r.w+'×'+r.h+(r.yuv?' (SW)':'');\n" +
        "      if(r.w===d.resolution_w&&r.h===d.resolution_h)o.selected=true;\n" +
        "      sel.appendChild(o);\n" +
        "    });\n" +
        "  }\n" +
        "  sv('camera_id',d.camera_id);\n" +
        "  sv('fps',d.fps);\n" +
        "  sv('quality',d.quality);\n" +
        "  sv('rotation',d.rotation);\n" +
        "  sv('flip',''+d.flip);\n" +
        "  sv('af_mode',d.af_mode);\n" +
        "  sv('port',d.port);\n" +
        "  var exp=document.getElementById('exposure');\n" +
        "  exp.min=d.exposure_min;exp.max=d.exposure_max;exp.value=d.exposure;\n" +
        "  document.getElementById('expv').textContent=d.exposure;\n" +
        "}).catch(function(){document.getElementById('msg').className='err';document.getElementById('msg').textContent='Cannot reach device';});\n" +
        "document.getElementById('exposure').oninput=function(){document.getElementById('expv').textContent=this.value;};\n" +
        "function save(){\n" +
        "  var rv=(document.getElementById('resolution').value||'').split('x');\n" +
        "  var b=JSON.stringify({\n" +
        "    camera_id:document.getElementById('camera_id').value,\n" +
        "    resolution_w:parseInt(rv[0])||D.resolution_w,\n" +
        "    resolution_h:parseInt(rv[1])||D.resolution_h,\n" +
        "    fps:parseInt(document.getElementById('fps').value),\n" +
        "    quality:parseInt(document.getElementById('quality').value),\n" +
        "    rotation:parseInt(document.getElementById('rotation').value),\n" +
        "    flip:document.getElementById('flip').value==='true',\n" +
        "    exposure:parseInt(document.getElementById('exposure').value),\n" +
        "    af_mode:document.getElementById('af_mode').value,\n" +
        "    port:parseInt(document.getElementById('port').value)\n" +
        "  });\n" +
        "  var msg=document.getElementById('msg');\n" +
        "  fetch('/api/settings',{method:'POST',headers:{'Content-Type':'application/json'},body:b})\n" +
        "    .then(function(r){return r.json();})\n" +
        "    .then(function(d){\n" +
        "      if(d.ok){\n" +
        "        msg.className='ok';\n" +
        "        if(d.port_changed){\n" +
        "          msg.textContent='Applied. Redirecting to :'+d.new_port+'...';\n" +
        "          setTimeout(function(){window.location.href='http://'+window.location.hostname+':'+d.new_port+'/settings';},1500);\n" +
        "        } else {\n" +
        "          msg.textContent='Applied';\n" +
        "          setTimeout(function(){msg.textContent='';},3000);\n" +
        "        }\n" +
        "      } else {\n" +
        "        msg.className='err';msg.textContent='Error: '+(d.error||'unknown');\n" +
        "      }\n" +
        "    }).catch(function(){msg.className='err';msg.textContent='Request failed';});\n" +
        "}\n" +
        "</script>\n" +
        "</body>\n" +
        "</html>\n";
}
