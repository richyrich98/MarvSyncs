package com.marvsync.fit;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.UUID;

public class MainActivity extends ComponentActivity implements MarvWatchManager.Listener {
    private static final int REQUEST_BLE = 42;
    private static final UUID AE_SERVICE = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb");
    private static final UUID AE_WRITE = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb");
    private static final UUID AE_NOTIFY = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb");
    private static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private int mtuPayload = 20;
    private boolean notificationReady;
    private boolean scanning;
    private boolean userDisconnect;
    private final Queue<byte[]> sendQueue = new ArrayDeque<>();
    private boolean sending;

    private MarvWatchManager watch;

    private TextView connectionText, deviceText, statusText;
    private TextView stepsValue, hrValue, spo2Value, distanceValue, caloriesValue, sleepValue;
    private Button connectButton, syncButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        adapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        requestPermissionsIfNeeded();
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(18), pad, pad);
        root.setBackgroundColor(0xFFF5F7FA);

        TextView title = text("MarvSync", 30, 0xFF111827);
        root.addView(title, lp(-1, -2));
        TextView subtitle = text("Direct Bluetooth sync for Marv Aura", 14, 0xFF667085);
        root.addView(subtitle, lp(-1, -2));

        LinearLayout deviceCard = card();
        connectionText = text("●  Not connected", 18, 0xFF344054);
        deviceCard.addView(connectionText, lp(-1, -2));
        deviceText = text("Scan for your beatXP Marv Aura", 13, 0xFF667085);
        deviceCard.addView(deviceText, lp(-1, -2));
        statusText = text("Ready", 13, 0xFF475467);
        statusText.setPadding(0, dp(10), 0, 0);
        deviceCard.addView(statusText, lp(-1, -2));
        root.addView(deviceCard, lp(-1, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        connectButton = button("CONNECT WATCH");
        connectButton.setOnClickListener(v -> {
            if (gatt != null) disconnect(); else startScan();
        });
        buttons.addView(connectButton, weightLp(0, 1));
        syncButton = button("SYNC NOW");
        syncButton.setEnabled(false);
        syncButton.setOnClickListener(v -> { if (watch != null) watch.syncNow(); });
        LinearLayout.LayoutParams sp = weightLp(0, 1);
        sp.leftMargin = dp(10);
        buttons.addView(syncButton, sp);
        root.addView(buttons, lp(-1, -2));

        TextView section = text("TODAY", 13, 0xFF667085);
        section.setPadding(0, dp(24), 0, dp(8));
        root.addView(section, lp(-1, -2));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.addView(metricRow("STEPS", stepsValue = value("--"), "HEART RATE", hrValue = value("--")));
        grid.addView(metricRow("SpO₂", spo2Value = value("--"), "DISTANCE", distanceValue = value("-- km")));
        grid.addView(metricRow("CALORIES", caloriesValue = value("-- kcal"), "SLEEP", sleepValue = value("--")));
        root.addView(grid, lp(-1, -2));

        TextView footer = text("MarvSync does not use beatXP OTP or the beatXP cloud.\nBluetooth data stays on this phone unless you add your own export later.", 12, 0xFF667085);
        footer.setPadding(0, dp(22), 0, dp(20));
        root.addView(footer, lp(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout metricRow(String a, TextView av, String b, TextView bv) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout ca = metricCard(a, av), cb = metricCard(b, bv);
        row.addView(ca, weightLp(0, 1));
        LinearLayout.LayoutParams p = weightLp(0, 1); p.leftMargin = dp(10); row.addView(cb, p);
        return row;
    }

    private LinearLayout metricCard(String label, TextView value) {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView l = text(label, 11, 0xFF667085);
        c.addView(l, lp(-1, -2));
        c.addView(value, lp(-1, -2));
        return c;
    }

    private TextView value(String s) {
        TextView t = text(s, 22, 0xFF101828);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(14);
        c.setLayoutParams(p);
        return c;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        return b;
    }

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams weightLp(int h, float weight) { return new LinearLayout.LayoutParams(0, h, weight); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private void requestPermissionsIfNeeded() {
        ArrayList<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!p.isEmpty()) ActivityCompat.requestPermissions(this, p.toArray(new String[0]), REQUEST_BLE);
    }

    private void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_SHORT).show(); return;
        }
        if (!hasScanPermission()) { requestPermissionsIfNeeded(); return; }
        if (scanning) return;
        scanning = true; userDisconnect = false;
        connectionText.setText("●  Scanning…");
        statusText.setText("Looking for BEATXP / MARV AURA");
        connectButton.setEnabled(false);
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        adapter.getBluetoothLeScanner().startScan(null, settings, scanCallback);
        main.postDelayed(() -> {
            if (scanning) { stopScan(); connectButton.setEnabled(true); connectionText.setText("●  Not connected"); statusText.setText("No Marv Aura found"); }
        }, 15000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
            if (name == null) name = safeName(d);
            if ((name != null && (name.toUpperCase().contains("BEATXP") || name.toUpperCase().contains("MARV")))) {
                stopScan(); connectButton.setEnabled(true); connect(d, name);
            }
        }
        @Override public void onScanFailed(int errorCode) { scanning = false; connectButton.setEnabled(true); statusText.setText("BLE scan failed: " + errorCode); }
    };

    private String safeName(BluetoothDevice d) { try { return d.getName(); } catch (Exception e) { return "Marv Aura"; } }
    private void stopScan() { if (!scanning) return; scanning = false; if (hasScanPermission()) adapter.getBluetoothLeScanner().stopScan(scanCallback); }
    private boolean hasScanPermission() { return Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED; }

    private void connect(BluetoothDevice device, String name) {
        if (!hasConnectPermission()) { requestPermissionsIfNeeded(); return; }
        closeGattOnly();
        notificationReady = false; sending = false; sendQueue.clear();
        connectionText.setText("●  Connecting…");
        deviceText.setText(name + "\n" + device.getAddress());
        statusText.setText("Opening BLE transport…");
        connectButton.setText("DISCONNECT");
        watch = new MarvWatchManager(this::sendBleData, this);
        gatt = Build.VERSION.SDK_INT >= 23 ? device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE) : device.connectGatt(this, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            runOnUiThread(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectionText.setText("●  Connected · preparing secure link…");
                    statusText.setText("Discovering watch services…");
                    if (Build.VERSION.SDK_INT >= 21) g.requestMtu(247);
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (watch != null) watch.onBleDisconnected(g.getDevice());
                    notificationReady = false; sending = false; sendQueue.clear(); syncButton.setEnabled(false);
                    connectionText.setText("●  Not connected");
                    statusText.setText(userDisconnect ? "Disconnected" : "Watch disconnected");
                    connectButton.setText("CONNECT WATCH");
                    connectButton.setEnabled(true);
                    closeGattOnly();
                }
            });
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtuPayload = Math.max(20, mtu - 3);
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { statusText.setText("Service discovery failed: " + status); return; }
            BluetoothGattCharacteristic w = null, n = null;
            if (g.getService(AE_SERVICE) != null) {
                w = g.getService(AE_SERVICE).getCharacteristic(AE_WRITE);
                n = g.getService(AE_SERVICE).getCharacteristic(AE_NOTIFY);
            }
            if (w == null || n == null) {
                if (g.getService(NUS_SERVICE) != null) {
                    w = g.getService(NUS_SERVICE).getCharacteristic(NUS_WRITE);
                    n = g.getService(NUS_SERVICE).getCharacteristic(NUS_NOTIFY);
                }
            }
            if (w == null || n == null) { statusText.setText("JieLi transport characteristics not found"); return; }
            writeCharacteristic = w; notifyCharacteristic = n;
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            if (g.setCharacteristicNotification(n, true)) {
                BluetoothGattDescriptor d = n.getDescriptor(CCCD);
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                } else {
                    notificationReady = true;
                    beginAuth();
                }
            } else statusText.setText("Could not enable watch notifications");
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (CCCD.equals(d.getUuid()) && status == BluetoothGatt.GATT_SUCCESS) {
                notificationReady = true; beginAuth();
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (notifyCharacteristic != null && notifyCharacteristic.getUuid().equals(c.getUuid()) && watch != null) {
                watch.onBleData(g.getDevice(), c.getValue());
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (writeCharacteristic != null && writeCharacteristic.getUuid().equals(c.getUuid())) {
                sending = false;
                if (status == BluetoothGatt.GATT_SUCCESS) pumpQueue(); else { sendQueue.clear(); statusText.setText("BLE write failed: " + status); }
            }
        }
    };

    private void beginAuth() {
        runOnUiThread(() -> statusText.setText("Secure handshake with watch…"));
        if (watch != null && gatt != null) watch.onBleConnected(gatt.getDevice());
    }

    private synchronized boolean sendBleData(BluetoothDevice device, byte[] data) {
        if (gatt == null || writeCharacteristic == null || !notificationReady || data == null) return false;
        for (int off = 0; off < data.length; off += mtuPayload) {
            int len = Math.min(mtuPayload, data.length - off);
            byte[] part = new byte[len];
            System.arraycopy(data, off, part, 0, len);
            sendQueue.add(part);
        }
        main.post(this::pumpQueue);
        return true;
    }

    private synchronized void pumpQueue() {
        if (sending || gatt == null || writeCharacteristic == null || sendQueue.isEmpty()) return;
        byte[] next = sendQueue.poll();
        if (next == null) return;
        writeCharacteristic.setValue(next);
        sending = gatt.writeCharacteristic(writeCharacteristic);
        if (!sending) sendQueue.clear();
    }

    private void disconnect() {
        userDisconnect = true;
        if (gatt != null) { try { gatt.disconnect(); } catch (Exception ignored) {} }
    }

    private void closeGattOnly() {
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) {} }
        gatt = null; writeCharacteristic = null; notifyCharacteristic = null;
    }

    private boolean hasConnectPermission() { return Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }

    @Override public void onStatus(String text) { statusText.setText(text); }
    @Override public void onHeartRate(int value) { if (value > 0) hrValue.setText(value + " bpm"); }
    @Override public void onSteps(int value, double distanceKm, int calories) {
        if (value >= 0) stepsValue.setText(String.valueOf(value));
        if (distanceKm >= 0) distanceValue.setText(String.format(java.util.Locale.US, "%.2f km", distanceKm));
        if (calories >= 0) caloriesValue.setText(calories + " kcal");
        syncButton.setEnabled(true);
    }
    @Override public void onSpO2(int value) { if (value > 0) spo2Value.setText(value + "%"); }
    @Override public void onSleep(int deep, int light, int rem, int awake) {
        int total = deep + light + rem + awake;
        sleepValue.setText((total / 60) + "h " + (total % 60) + "m");
    }
    @Override public void onError(String text) { statusText.setText(text); syncButton.setEnabled(gatt != null); }

    @Override protected void onDestroy() {
        stopScan();
        if (watch != null) watch.releaseManager();
        userDisconnect = true; closeGattOnly();
        super.onDestroy();
    }
}
