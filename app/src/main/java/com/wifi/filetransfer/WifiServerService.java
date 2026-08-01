package com.wifi.filetransfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class WifiServerService extends Service {

    private static final String CHANNEL_ID = "wifi_server_channel";
    private static final int NOTIFICATION_ID = 4224;
    
    private static HttpServer server = null;
    private static PowerManager.WakeLock wakeLock = null;
    private static boolean isRunning = false;

    public static boolean isRunning() {
        return isRunning;
    }

    public static int getPort(Context context) {
        if (server != null) {
            return server.getPort();
        }
        android.content.SharedPreferences prefs = context.getSharedPreferences("wifi_transfer_prefs", Context.MODE_PRIVATE);
        return prefs.getInt("server_port", 8000);
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        
        if ("START".equals(action)) {
            startServer();
        } else if ("STOP".equals(action)) {
            stopServer();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startServer() {
        if (isRunning) return;

        // Create notification channel for Oreo+
        createNotificationChannel();

        // Create foreground notification
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = null;
        builder = new Notification.Builder(this, CHANNEL_ID);

        Notification notification = null;
        notification = builder
                .setContentTitle("Wifi File Transfer Running")
                .setContentText("Access the server via your web browser")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // Acquire WakeLock
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiFileTransfer::ServerWakeLock");
                wakeLock.acquire(10*60*1000L /*10 minutes*/);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Start HTTP Server
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("wifi_transfer_prefs", Context.MODE_PRIVATE);
            int port = prefs.getInt("server_port", 8000);
            server = new HttpServer(getApplicationContext(), port);
            server.start();
            isRunning = true;
            
            // Notify MainActivity to update status
            Intent intent = new Intent("com.wifi.filetransfer.SERVER_STATUS_CHANGED");
            sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
            stopServer();
            stopSelf();
        }
    }

    private void stopServer() {
        if (!isRunning) return;

        // Release WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            wakeLock = null;
        }

        // Stop HTTP Server
        if (server != null) {
            server.stop();
            server = null;
        }

        isRunning = false;

        // Notify MainActivity
        Intent intent = new Intent("com.wifi.filetransfer.SERVER_STATUS_CHANGED");
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Wifi File Transfer Server Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
