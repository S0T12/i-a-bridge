package com.ia.callbridge.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.ia.callbridge.service.CallBridgeService;

public class PhoneCallReceiver extends BroadcastReceiver {
    private static final String TAG = "PhoneCallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }

        String stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

        Log.d(TAG, "Phone State Changed: " + stateStr + ", Number: " + incomingNumber);

        Intent serviceIntent = new Intent(context, CallBridgeService.class);
        serviceIntent.setAction(CallBridgeService.ACTION_PHONE_STATE);
        serviceIntent.putExtra(CallBridgeService.EXTRA_STATE, stateStr);
        serviceIntent.putExtra(CallBridgeService.EXTRA_NUMBER, incomingNumber);
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service from receiver", e);
        }
    }
}
