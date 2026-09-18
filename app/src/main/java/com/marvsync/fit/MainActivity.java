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
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
    private static final long CONNECTION_TIMEOUT_MS = 15000;
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
    private Runnable connectionTimeout;
    private MarvWatchManager watch;

    private TextView connectionText, deviceText, statusText, timeText;
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
        root.setPadding(pad, dp(22), pad, dp(28));
        root.setBackgroundColor(Color.rgb(247, 248, 250));

        TextView title = text("MarvSync", 31, Color.rgb(17,24,39));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, lp(-1, -2));
        TextView subtitle = text("Simple Bluetooth companion for Marv Aura", 14, Color.rgb(102,112,133));
        root.addView(subtitle, lp(-1, -2));

        LinearLayout deviceCard = roundedCard();
        connectionText = text("●  Not connected", 19, Color.rgb(52,64,84));
        connectionText.setTypeface(null, android.graphics.Typeface.BOLD);
        deviceCard.addView(connectionText, lp(-1, -2));
        deviceText = text("Your watch will appear here", 14, Color.rgb(102,112,133));
        deviceText.setPadding(0, dp(8), 0, 0);
        deviceCard.addView(deviceText, lp(-1, -2));
        statusText = text("Ready to scan", 13, Color.rgb(71,84,103));
        statusText.setPadding(0, dp(12), 0, 0);
        deviceCard.addView(statusText, lp(-1, -2));
        root.addView(deviceCard, lp(-1, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        connectButton = primaryButton("SCAN & CONNECT");
        connectButton.setOnClickListener(v -> {
            if (gatt != null) disconnect(); else startScan();
        });
        buttons.addView(connectButton, weightLp(0, 1));

        syncButton = secondaryButton("SYNC TIME");
        syncButton.setEnabled(false);
        syncButton.setOnClickListener(v -> {
            if (watch != null) watch.syncClockNow();
        });
        LinearLayout.LayoutParams sp = weightLp(0, 1);
        sp.leftMargin = dp(10);
        buttons.addView(syncButton, sp);
        root.addView(buttons, lp(-1, -2));

        LinearLayout timeCard = roundedCard();
        TextView timeLabel = text("WATCH TIME", 12, Color.rgb(102,112,133));
        timeCard.addView(timeLabel, lp(-1, -2));
        timeText = text("Not synced", 23, Color.rgb(17,24,39));
        timeText.setTypeface(null, android.graphics.Typeface.BOLD);
        timeText.setPadding(0, dp(6), 0, 0);
        timeCard.addView(timeText, lp(-1, -2));
        TextView timeHint = text("MarvSync will send your phone's current date and time after connection.", 12, Color.rgb(102,112,133));
        timeHint.setPadding(0, dp(5), 0, 0);
        timeCard.addView(timeHint, lp(-1, -2));
        root.addView(timeCard, lp(-1, -2));

        LinearLayout info = roundedCard();
        TextView head = text("MARV AURA", 12, Color.rgb(102,112,133));
        info.addView(head, lp(-1, -2));
        TextView body = text("Bluetooth only\nNo OTP • No account • No cloud", 16, Color.rgb(17,24,39));
        body.setPadding(0, dp(7), 0, 0);
        info.addView(body, lp(-1, -2));
        root.addView(info, lp(-1, -2));

        TextView footer = text("Keep the watch nearby while connecting. Android may show a pairing prompt on some Marv firmware versions.", 12, Color.rgb(102,112,133));
        footer.setPadding(0, dp(18), 0, 0);
        root.addView(footer, lp(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout roundedCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(17), dp(16), dp(17), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(232,234,238));
        c.setBackground(bg);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(16);
        c.setLayoutParams(p);
        return c;
    }

    private Button primaryButton(String s) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(12); b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(17,24,39)); bg.setCornerRadius(dp(14));
        b.setBackground(bg); b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private Button secondaryButton(String s) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(12); b.setTextColor(Color.rgb(17,24,39));
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE); bg.setCornerRadius(dp(14)); bg.setStroke(dp(1), Color.rgb(210,214,220));
        b.setBackground(bg); b.setPadding(dp(8), 0, dp(8), 0);
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
        if (adapter == null || !adapter.isEnabled()) { Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_SHORT).show(); return; }
        if (!hasScanPermission()) { requestPermissionsIfNeeded(); return; }
        if (scanning) return;
        scanning = true; userDisconnect = false;
        connectionText.setText("●  Scanning…");
        statusText.setText("Looking for beatXP Marv Aura…");
        connectButton.setEnabled(false);
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        adapter.getBluetoothLeScanner().startScan(null, settings, scanCallback);
        main.postDelayed(() -> {
            if (scanning) { stopScan(); connectButton.setEnabled(true); connectionText.setText("●  Not connected"); statusText.setText("No Marv Aura found"); }
        }, 12000);
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
        statusText.setText("Opening Bluetooth link…");
        connectButton.setText("DISCONNECT");
        connectButton.setEnabled(true);
        syncButton.setEnabled(false);
        watch = new MarvWatchManager(this::sendBleData, this);
        connectionTimeout = () -> {
            if (gatt != null && !notificationReady) {
                statusText.setText("Connection timed out. Tap CONNECT again.");
                userDisconnect = true;
                try { gatt.disconnect(); } catch (Exception ignored) {}
            }
        };
        main.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS);
        gatt = Build.VERSION.SDK_INT >= 23 ? device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE) : device.connectGatt(this, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            runOnUiThread(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectionText.setText("●  Bluetooth connected");
                    statusText.setText("Preparing watch protocol…");
                    if (Build.VERSION.SDK_INT >= 21) g.requestMtu(247);
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (connectionTimeout != null) main.removeCallbacks(connectionTimeout);
                    if (watch != null) watch.onBleDisconnected(g.getDevice());
                    notificationReady = false; sending = false; sendQueue.clear(); syncButton.setEnabled(false);
                    connectionText.setText("●  Not connected");
                    statusText.setText(userDisconnect ? "Disconnected" : "Bluetooth connection ended");
                    connectButton.setText("SCAN & CONNECT");
                    connectButton.setEnabled(true);
                    closeGattOnly();
                }
            });
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtuPayload = Math.max(20, mtu - 3);
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { statusText.setText("Could not prepare watch services"); return; }
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
            if (w == null || n == null) { statusText.setText("Watch transport not found"); return; }
            writeCharacteristic = w; notifyCharacteristic = n;
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            if (g.setCharacteristicNotification(n, true)) {
                BluetoothGattDescriptor d = n.getDescriptor(CCCD);
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (!g.writeDescriptor(d)) { statusText.setText("Could not enable notifications"); return; }
                } else { notificationReady = true; beginAuth(); }
            } else statusText.setText("Could not enable watch notifications");
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (CCCD.equals(d.getUuid()) && status == BluetoothGatt.GATT_SUCCESS) {
                notificationReady = true; beginAuth();
            } else if (CCCD.equals(d.getUuid())) {
                statusText.setText("Watch notification setup failed");
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (notifyCharacteristic != null && notifyCharacteristic.getUuid().equals(c.getUuid()) && watch != null) watch.onBleData(g.getDevice(), c.getValue());
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (writeCharacteristic != null && writeCharacteristic.getUuid().equals(c.getUuid())) {
                sending = false;
                if (status == BluetoothGatt.GATT_SUCCESS) pumpQueue(); else { sendQueue.clear(); statusText.setText("Bluetooth data transfer failed"); }
            }
        }
    };

    private void beginAuth() {
        runOnUiThread(() -> statusText.setText("Authenticating watch…"));
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
        if (connectionTimeout != null) main.removeCallbacks(connectionTimeout);
        if (gatt != null) { try { gatt.disconnect(); } catch (Exception ignored) {} }
    }

    private void closeGattOnly() {
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) {} }
        gatt = null; writeCharacteristic = null; notifyCharacteristic = null;
    }

    private boolean hasConnectPermission() { return Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }

    @Override public void onStatus(String text) {
        statusText.setText(text);
        if (text != null && text.toLowerCase().contains("sync") && text.toLowerCase().contains("time")) timeText.setText("Synced ✓");
        if (text != null && (text.contains("authenticated") || text.contains("Watch authenticated"))) syncButton.setEnabled(true);
    }
    @Override public void onHeartRate(int value) { }
    @Override public void onSteps(int value, double distanceKm, int calories) { }
    @Override public void onSpO2(int value) { }
    @Override public void onSleep(int deep, int light, int rem, int awake) { }
    @Override public void onError(String text) { statusText.setText(text); syncButton.setEnabled(gatt != null && notificationReady); }

    @Override protected void onDestroy() {
        stopScan();
        if (connectionTimeout != null) main.removeCallbacks(connectionTimeout);
        if (watch != null) watch.releaseManager();
        userDisconnect = true; closeGattOnly();
        super.onDestroy();
    }
}
