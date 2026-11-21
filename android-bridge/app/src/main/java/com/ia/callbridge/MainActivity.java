package com.ia.callbridge;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.telecom.TelecomManager;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.ia.callbridge.service.CallBridgeService;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity implements CallBridgeService.UiUpdateListener {
    private static final int PERMISSION_REQ_CODE = 101;
    private static final int REQUEST_ROLE_DIALER = 102;

    private TextView tvStatus;
    private TextView tvIpAddress;
    private TextView tvCallState;
    private android.view.View statusIndicator;
    private Button btnPermissions;
    private Button btnSetDefaultDialer;
    private Button btnTestCall;

    private CallBridgeService bridgeService;
    private boolean isBound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CallBridgeService.LocalBinder binder = (CallBridgeService.LocalBinder) service;
            bridgeService = binder.getService();
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridgeService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvIpAddress = findViewById(R.id.tvIpAddress);
        tvCallState = findViewById(R.id.tvCallState);
        statusIndicator = findViewById(R.id.statusIndicator);
        btnPermissions = findViewById(R.id.btnPermissions);
        btnSetDefaultDialer = findViewById(R.id.btnSetDefaultDialer);
        btnTestCall = findViewById(R.id.btnTestCall);

        CallBridgeService.setUiListener(this);

        btnPermissions.setOnClickListener(v -> checkAndRequestPermissions());
        btnSetDefaultDialer.setOnClickListener(v -> requestDefaultDialerRole());
        btnTestCall.setOnClickListener(v -> {
            if (bridgeService != null) {
                bridgeService.sendTestCall();
                Toast.makeText(this, "سیگنال تماس تستی به آیفون ارسال شد", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "سرویس فعال نیست", Toast.LENGTH_SHORT).show();
            }
        });

        updateIpAddressDisplay();
        checkAndRequestPermissions();
        startBridgeService();
    }

    private void startBridgeService() {
        Intent serviceIntent = new Intent(this, CallBridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    private void updateIpAddressDisplay() {
        String ip = getLocalIpAddress();
        tvIpAddress.setText("آدرس هات‌اسپات اندروید: " + (ip != null ? ip : "192.168.43.1") + ":8765");
    }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.getName().contains("wlan") || intf.getName().contains("ap")) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.43.1";
    }

    private void checkAndRequestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        permissions.add(Manifest.permission.READ_CALL_LOG);
        permissions.add(Manifest.permission.READ_CONTACTS);
        permissions.add(Manifest.permission.ANSWER_PHONE_CALLS);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQ_CODE);
        } else {
            Toast.makeText(this, "تمام دسترسی‌ها داده شده است", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                    startActivityForResult(intent, REQUEST_ROLE_DIALER);
                } else {
                    Toast.makeText(this, "هم‌اکنون پاسخ‌دهنده پیش‌فرض است", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (telecomManager != null && !getPackageName().equals(telecomManager.getDefaultDialerPackage())) {
                Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
                intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
                startActivity(intent);
            }
        }
    }

    @Override
    public void onStatusChanged(String status, boolean connected) {
        runOnUiThread(() -> {
            tvStatus.setText(status);
            statusIndicator.setBackgroundResource(connected ? R.drawable.circle_green : R.drawable.circle_orange);
        });
    }

    @Override
    public void onCallStateChanged(String state) {
        runOnUiThread(() -> {
            tvCallState.setText("وضعیت تماس: " + state);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
    }
}
