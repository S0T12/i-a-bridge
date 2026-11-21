package com.ia.callbridge.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.ia.callbridge.CallBridgeApp;
import com.ia.callbridge.MainActivity;
import com.ia.callbridge.audio.AudioBridgeEngine;
import com.ia.callbridge.network.BridgeServer;

public class CallBridgeService extends Service implements BridgeServer.CallActionListener, InCallServiceImpl.CallListener {
    private static final String TAG = "CallBridgeService";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_PHONE_STATE = "com.ia.callbridge.ACTION_PHONE_STATE";
    public static final String EXTRA_STATE = "extra_state";
    public static final String EXTRA_NUMBER = "extra_number";

    public interface UiUpdateListener {
        void onStatusChanged(String status, boolean connected);
        void onCallStateChanged(String state);
    }

    private static UiUpdateListener uiListener;

    public static void setUiListener(UiUpdateListener listener) {
        uiListener = listener;
    }

    private final IBinder binder = new LocalBinder();
    private AudioBridgeEngine audioEngine;
    private BridgeServer bridgeServer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private boolean isIphoneConnected = false;
    private String currentCallState = "IDLE";

    public class LocalBinder extends Binder {
        public CallBridgeService getService() {
            return CallBridgeService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "CallBridgeService onCreate");

        acquireLocks();

        audioEngine = new AudioBridgeEngine(this);
        bridgeServer = new BridgeServer(this, audioEngine, this);
        bridgeServer.start();

        InCallServiceImpl.setCallListener(this);

        startForeground(NOTIFICATION_ID, buildNotification("پل ارتباطی فعال - در انتظار آیفون"));
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallBridge:WakeLock");
                wakeLock.acquire();
            }

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CallBridge:WifiLock");
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring locks", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_PHONE_STATE.equals(intent.getAction())) {
            String stateStr = intent.getStringExtra(EXTRA_STATE);
            String number = intent.getStringExtra(EXTRA_NUMBER);
            handlePhoneState(stateStr, number);
        }
        return START_STICKY;
    }

    private void handlePhoneState(String stateStr, String number) {
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(stateStr)) {
            currentCallState = "RINGING";
            String name = resolveContactName(number);
            onCallIncoming(number, name);
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(stateStr)) {
            currentCallState = "ACTIVE";
            onCallActive();
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(stateStr)) {
            currentCallState = "IDLE";
            onCallDisconnected();
        }
    }

    @Override
    public void onCallIncoming(String number, String name) {
        Log.i(TAG, "onCallIncoming: " + number + " (" + name + ")");
        currentCallState = "RINGING: " + (number != null ? number : "");
        updateNotification("تماس ورودی از: " + (name != null && !name.isEmpty() ? name : number));

        if (bridgeServer != null) {
            bridgeServer.sendCallIncoming(number, name);
        }

        if (uiListener != null) {
            uiListener.onCallStateChanged(currentCallState);
        }
    }

    @Override
    public void onCallActive() {
        Log.i(TAG, "onCallActive");
        currentCallState = "ACTIVE";
        updateNotification("مکالمه فعال در جریان");

        if (bridgeServer != null) {
            bridgeServer.sendCallAnswered();
        }

        // Start Audio Engine
        if (audioEngine != null) {
            audioEngine.start();
        }

        if (uiListener != null) {
            uiListener.onCallStateChanged("ACTIVE");
        }
    }

    @Override
    public void onCallDisconnected() {
        Log.i(TAG, "onCallDisconnected");
        currentCallState = "IDLE";
        updateNotification(isIphoneConnected ? "آیفون ۱۵ پرو متصل است" : "در انتظار اتصال آیفون");

        if (bridgeServer != null) {
            bridgeServer.sendCallEnded();
        }

        // Stop Audio Engine
        if (audioEngine != null) {
            audioEngine.stop();
        }

        if (uiListener != null) {
            uiListener.onCallStateChanged("IDLE");
        }
    }

    @Override
    public void onAnswerCall() {
        Log.i(TAG, "Action: Answer Call received from iPhone");
        boolean answered = InCallServiceImpl.answerCall();
        if (!answered) {
            // Fallback: TelecomManager.acceptRingingCall()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                    if (tm != null) {
                        tm.acceptRingingCall();
                        Log.i(TAG, "Call answered via TelecomManager.acceptRingingCall()");
                    }
                } catch (SecurityException se) {
                    Log.e(TAG, "Missing permission to answer call", se);
                }
            }
        }
    }

    @Override
    public void onDeclineCall() {
        Log.i(TAG, "Action: Decline/End Call received from iPhone");
        boolean disconnected = InCallServiceImpl.disconnectCall();
        if (!disconnected) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                    if (tm != null) {
                        tm.endCall();
                        Log.i(TAG, "Call ended via TelecomManager.endCall()");
                    }
                } catch (SecurityException se) {
                    Log.e(TAG, "Missing permission to end call", se);
                }
            }
        }
    }

    @Override
    public void onClientConnected(String clientIp) {
        Log.i(TAG, "iPhone client connected: " + clientIp);
        isIphoneConnected = true;
        updateNotification("آیفون ۱۵ پرو متصل است (" + clientIp + ")");
        if (uiListener != null) {
            uiListener.onStatusChanged("آیفون متصل شد (" + clientIp + ")", true);
        }
    }

    @Override
    public void onClientDisconnected() {
        Log.i(TAG, "iPhone client disconnected");
        isIphoneConnected = false;
        updateNotification("در انتظار اتصال آیفون...");
        if (uiListener != null) {
            uiListener.onStatusChanged("در انتظار اتصال آیفون...", false);
        }
    }

    public void sendTestCall() {
        if (bridgeServer != null) {
            bridgeServer.sendCallIncoming("09121234567", "تست تماس CallBridge");
        }
    }

    private String resolveContactName(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) return "";
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
            Cursor cursor = getContentResolver().query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        String name = cursor.getString(nameIndex);
                        cursor.close();
                        return name;
                    }
                }
                cursor.close();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        return new NotificationCompat.Builder(this, CallBridgeApp.CHANNEL_ID)
                .setContentTitle("CallBridge Service")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            Notification notification = buildNotification(text);
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "CallBridgeService onDestroy");

        if (bridgeServer != null) {
            bridgeServer.stop();
        }
        if (audioEngine != null) {
            audioEngine.stop();
        }

        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }
}
