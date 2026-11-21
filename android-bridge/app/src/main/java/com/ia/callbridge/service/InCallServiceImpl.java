package com.ia.callbridge.service;

import android.telecom.Call;
import android.telecom.InCallService;
import android.telecom.VideoProfile;
import android.util.Log;

public class InCallServiceImpl extends InCallService {
    private static final String TAG = "InCallServiceImpl";
    private static InCallServiceImpl instance;
    private static Call currentCall;

    public interface CallListener {
        void onCallIncoming(String number, String name);
        void onCallActive();
        void onCallDisconnected();
    }

    private static CallListener callListener;

    public static void setCallListener(CallListener listener) {
        callListener = listener;
    }

    public static InCallServiceImpl getInstance() {
        return instance;
    }

    public static Call getCurrentCall() {
        return currentCall;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "InCallServiceImpl created.");
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        currentCall = call;
        Log.i(TAG, "onCallAdded: " + call);

        call.registerCallback(new Call.Callback() {
            @Override
            public void onStateChanged(Call call, int state) {
                super.onStateChanged(call, state);
                Log.d(TAG, "Call state changed: " + stateToString(state));

                if (state == Call.STATE_ACTIVE) {
                    if (callListener != null) callListener.onCallActive();
                } else if (state == Call.STATE_DISCONNECTED) {
                    if (callListener != null) callListener.onCallDisconnected();
                    currentCall = null;
                }
            }
        });

        String number = "";
        String name = "";
        if (call.getDetails() != null) {
            if (call.getDetails().getHandle() != null) {
                number = call.getDetails().getHandle().getSchemeSpecificPart();
            }
            if (call.getDetails().getCallerDisplayName() != null) {
                name = call.getDetails().getCallerDisplayName();
            }
        }

        if (call.getState() == Call.STATE_RINGING) {
            if (callListener != null) {
                callListener.onCallIncoming(number, name);
            }
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Log.i(TAG, "onCallRemoved");
        if (currentCall == call) {
            currentCall = null;
            if (callListener != null) {
                callListener.onCallDisconnected();
            }
        }
    }

    public static boolean answerCall() {
        if (currentCall != null && currentCall.getState() == Call.STATE_RINGING) {
            currentCall.answer(VideoProfile.STATE_AUDIO_ONLY);
            Log.i(TAG, "Call answered via InCallService.");
            return true;
        }
        return false;
    }

    public static boolean disconnectCall() {
        if (currentCall != null) {
            currentCall.disconnect();
            Log.i(TAG, "Call disconnected via InCallService.");
            return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        currentCall = null;
    }

    private static String stateToString(int state) {
        switch (state) {
            case Call.STATE_NEW: return "NEW";
            case Call.STATE_RINGING: return "RINGING";
            case Call.STATE_DIALING: return "DIALING";
            case Call.STATE_ACTIVE: return "ACTIVE";
            case Call.STATE_HOLDING: return "HOLDING";
            case Call.STATE_DISCONNECTED: return "DISCONNECTED";
            default: return "UNKNOWN_" + state;
        }
    }
}
