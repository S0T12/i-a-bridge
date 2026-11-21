package com.ia.callbridge;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class CallBridgeApp extends Application {
    public static final String CHANNEL_ID = "call_bridge_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Call Bridge Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background bridge service for iPhone call forwarding");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
