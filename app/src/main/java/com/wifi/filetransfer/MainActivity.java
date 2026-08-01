package com.wifi.filetransfer;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    private TextView textStatus;
    private TextView textAddress;
    private Button btnStart;
    private Button btnStop;
    private CheckBox checkboxPassword;
    private EditText editPassword;
    private EditText editPort;
    private Button btnSaveSettings;

    private boolean doubleBackToExitPressedOnce = false;
    private final Handler doubleBackHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver serverStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateUiState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind Views
        textStatus = findViewById(R.id.text_status);
        textAddress = findViewById(R.id.text_address);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        checkboxPassword = findViewById(R.id.checkbox_password);
        editPassword = findViewById(R.id.edit_password);
        editPort = findViewById(R.id.edit_port);
        btnSaveSettings = findViewById(R.id.btn_save_settings);

        // Check & Request Storage Permissions
        checkStoragePermissions();

        // Setup Button Listeners
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerServiceAction("START");
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerServiceAction("STOP");
            }
        });

        btnSaveSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
            }
        });

        // Checkbox Checked Listener to enable/disable password input
        checkboxPassword.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                editPassword.setEnabled(isChecked);
                updateDynamicFocus();
            }
        });

        // Initialize Settings from SharedPreferences
        loadSettings();

        // Register Broadcast Receiver for Server Status Updates
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serverStatusReceiver, new IntentFilter("com.wifi.filetransfer.SERVER_STATUS_CHANGED"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serverStatusReceiver, new IntentFilter("com.wifi.filetransfer.SERVER_STATUS_CHANGED"));
        }

        // Initialize / Update UI State
        updateUiState();

        // D-Pad Compatibility: Request focus on Start button initially on TV
        btnStart.requestFocus();
    }

    private void checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage permissions granted.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Storage permissions are required to access files.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void triggerServiceAction(String action) {
        Intent intent = new Intent(this, WifiServerService.class);
        intent.setAction(action);
        if ("START".equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } else {
            startService(intent);
        }
    }

    private void loadSettings() {
        boolean enabled = PasswordUtils.isPasswordEnabled(this);
        checkboxPassword.setChecked(enabled);
        editPassword.setEnabled(enabled);
        
        // Show indicator if password is saved
        if (PasswordUtils.hasSavedPassword(this)) {
            editPassword.setHint("•••••••• (Saved)");
        } else {
            editPassword.setHint("Enter password");
        }

        android.content.SharedPreferences prefs = getSharedPreferences("wifi_transfer_prefs", Context.MODE_PRIVATE);
        int port = prefs.getInt("server_port", 8000);
        editPort.setText(String.valueOf(port));

        updateDynamicFocus();
    }

    private void updateDynamicFocus() {
        boolean enabled = checkboxPassword.isChecked();
        checkboxPassword.setNextFocusUpId(R.id.btn_stop);
        if (enabled) {
            checkboxPassword.setNextFocusDownId(R.id.edit_password);
            editPassword.setNextFocusUpId(R.id.checkbox_password);
            editPassword.setNextFocusDownId(R.id.edit_port);
            editPort.setNextFocusUpId(R.id.edit_password);
        } else {
            checkboxPassword.setNextFocusDownId(R.id.edit_port);
            editPort.setNextFocusUpId(R.id.checkbox_password);
        }
        editPort.setNextFocusDownId(R.id.btn_save_settings);
        btnSaveSettings.setNextFocusUpId(R.id.edit_port);
    }

    private void saveSettings() {
        boolean enabled = checkboxPassword.isChecked();
        String password = editPassword.getText().toString().trim();
        String portStr = editPort.getText().toString().trim();

        if (enabled && password.isEmpty() && !PasswordUtils.hasSavedPassword(this)) {
            Toast.makeText(this, "Please enter a password when protection is enabled.", Toast.LENGTH_SHORT).show();
            return;
        }

        int port = 8000;
        if (!portStr.isEmpty()) {
            try {
                port = Integer.parseInt(portStr);
                if (port < 1024 || port > 65535) {
                    Toast.makeText(this, "Please enter a valid port between 1024 and 65535.", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid port format.", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            Toast.makeText(this, "Please enter a port number (default is 8000).", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save password settings
        PasswordUtils.savePassword(this, password, enabled);
        editPassword.setText("");

        // Save port settings
        android.content.SharedPreferences prefs = getSharedPreferences("wifi_transfer_prefs", Context.MODE_PRIVATE);
        prefs.edit().putInt("server_port", port).apply();

        loadSettings();

        // Update current displayed address if running
        updateUiState();

        Toast.makeText(this, "Settings saved successfully.", Toast.LENGTH_SHORT).show();
    }

    private void updateUiState() {
        boolean running = WifiServerService.isRunning();
        if (running) {
            textStatus.setText("RUNNING");
            textStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            
            String ip = getIpAddress();
            int port = WifiServerService.getPort(this);
            textAddress.setText("http://" + ip + ":" + port);
        } else {
            textStatus.setText("STOPPED");
            textStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));

            android.content.SharedPreferences prefs = getSharedPreferences("wifi_transfer_prefs", Context.MODE_PRIVATE);
            int port = prefs.getInt("server_port", 8000);
            textAddress.setText("http://---.---.---.---:" + port);
        }
    }

    private boolean isPrivate172(String ip) {
        try {
            if (ip.startsWith("172.")) {
                String[] parts = ip.split("\\.");
                if (parts.length >= 2) {
                    int secondOctet = Integer.parseInt(parts[1]);
                    return secondOctet >= 16 && secondOctet <= 31;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    private String getIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());

            // Priority 1: wlan/eth interface (non-p2p) with private IP (192.168.x.x, 10.x.x.x, 172.x.x.x)
            for (NetworkInterface intf : interfaces) {
                try {
                    if (!intf.isUp() || intf.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                String name = intf.getName().toLowerCase();
                if ((name.contains("wlan") || name.contains("eth")) && !name.contains("p2p")) {
                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            String ip = addr.getHostAddress();
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || isPrivate172(ip)) {
                                return ip;
                            }
                        }
                    }
                }
            }

            // Priority 2: Any wlan/eth interface (including p2p as fallback) with any IPv4
            for (NetworkInterface intf : interfaces) {
                try {
                    if (!intf.isUp() || intf.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                String name = intf.getName().toLowerCase();
                if (name.contains("wlan") || name.contains("eth")) {
                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }

            // Priority 3: Any interface starting with 192.168. IPv4
            for (NetworkInterface intf : interfaces) {
                try {
                    if (!intf.isUp() || intf.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip.startsWith("192.168.")) {
                            return ip;
                        }
                    }
                }
            }

            // Priority 4: Any interface with 10.x.x.x or 172.16-31.x.x IPv4
            for (NetworkInterface intf : interfaces) {
                try {
                    if (!intf.isUp() || intf.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip.startsWith("10.") || isPrivate172(ip)) {
                            return ip;
                        }
                    }
                }
            }

            // Priority 5: Any non-loopback IPv4 address
            for (NetworkInterface intf : interfaces) {
                try {
                    if (!intf.isUp() || intf.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        // Keep existing WifiManager fallback just in case
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                int ipAddress = wifiInfo.getIpAddress();
                if (ipAddress != 0) {
                    return String.format("%d.%d.%d.%d",
                            (ipAddress & 0xff),
                            (ipAddress >> 8 & 0xff),
                            (ipAddress >> 16 & 0xff),
                            (ipAddress >> 24 & 0xff));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return "127.0.0.1";
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(serverStatusReceiver);
        } catch (Exception e) {
            // ignore
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (doubleBackToExitPressedOnce) {
            triggerServiceAction("STOP");
            super.onBackPressed();
            return;
        }

        this.doubleBackToExitPressedOnce = true;
        Toast.makeText(this, "Press BACK again to exit & stop server.", Toast.LENGTH_SHORT).show();

        doubleBackHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                doubleBackToExitPressedOnce = false;
            }
        }, 2000);
    }
}
