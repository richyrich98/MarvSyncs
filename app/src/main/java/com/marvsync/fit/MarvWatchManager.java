package com.marvsync.fit;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.Looper;

import com.jieli.jl_rcsp.constant.StateCode;
import com.jieli.jl_rcsp.impl.HealthOpImpl;
import com.jieli.jl_rcsp.impl.RcspAuth;
import com.jieli.jl_rcsp.impl.WatchOpImpl;
import com.jieli.jl_rcsp.interfaces.OnOperationCallback;
import com.jieli.jl_rcsp.interfaces.rcsp.OnRcspEventListener;
import com.jieli.jl_rcsp.model.HealthDataQuery;
import com.jieli.jl_rcsp.model.base.BaseError;
import com.jieli.jl_rcsp.model.device.health.HeartRate;
import com.jieli.jl_rcsp.model.device.health.OxygenSaturation;
import com.jieli.jl_rcsp.model.device.health.SportsSteps;
import com.jieli.jl_rcsp.task.SimpleTaskListener;
import com.jieli.jl_rcsp.task.smallfile.QueryFileTask;
import com.jieli.jl_rcsp.task.smallfile.ReadFileTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

/**
 * Thin adapter between Android BLE and the public JieLi watch SDK.
 * The BLE layer is deliberately kept outside the SDK so we can support the
 * Marv Aura's AE00/AE01/AE02 GATT transport directly.
 */
public class MarvWatchManager extends WatchOpImpl {
    public interface Transport {
        boolean send(BluetoothDevice device, byte[] data);
    }

    public interface Listener {
        void onStatus(String text);
        void onHeartRate(int value);
        void onSteps(int value, double distanceKm, int calories);
        void onSpO2(int value);
        void onSleep(int deep, int light, int rem, int awake);
        void onError(String text);
    }

    private final Transport transport;
    private final Listener listener;
    private final RcspAuth rcspAuth;
    private final HealthOpImpl healthOp;
    private BluetoothDevice device;
    private boolean authenticated;
    private boolean released;
    private final Handler main = new Handler(Looper.getMainLooper());

    public MarvWatchManager(Transport transport, Listener listener) {
        super(FUNC_WATCH);
        this.transport = transport;
        this.listener = listener;

        rcspAuth = new RcspAuth(new RcspAuth.IRcspAuthOp() {
            @Override
            public boolean sendAuthDataToDevice(BluetoothDevice device, byte[] data) {
                return transport.send(device, data);
            }
        }, new RcspAuth.OnRcspAuthListener() {
            @Override public void onInitResult(boolean result) {
                postStatus(result ? "Security handshake ready" : "Security handshake could not initialize");
            }

            @Override public void onAuthSuccess(BluetoothDevice device) {
                authenticated = true;
                MarvWatchManager.this.device = device;
                postStatus("Watch authenticated ✓");
                notifyBtDeviceConnection(device, StateCode.CONNECTION_OK);
            }

            @Override public void onAuthFailed(BluetoothDevice device, int code, String message) {
                authenticated = false;
                postError("Watch authentication failed: " + code + (message == null ? "" : " · " + message));
            }
        });

        healthOp = new HealthOpImpl(this);
        healthOp.getRcspOp().registerOnRcspEventListener(new OnRcspEventListener() {
            @Override
            public void onHealthDataChange(BluetoothDevice device, com.jieli.jl_rcsp.model.device.health.HealthData data) {
                if (data == null) return;
                try {
                    switch (data.type) {
                        case 0x00:
                            if (data instanceof HeartRate) {
                                HeartRate hr = (HeartRate) data;
                                listener.onHeartRate(hr.getRealTimeValue());
                            }
                            break;
                        case 0x03:
                            if (data instanceof SportsSteps) {
                                SportsSteps s = (SportsSteps) data;
                                listener.onSteps(s.getStepNum(), s.getDistance(), s.getCalorie());
                            }
                            break;
                        case 0x05:
                            if (data instanceof OxygenSaturation) {
                                listener.onSpO2(((OxygenSaturation) data).getPercent());
                            }
                            break;
                    }
                } catch (Throwable t) {
                    postError("Health data parse error: " + t.getClass().getSimpleName());
                }
            }
        });
    }

    @Override
    public BluetoothDevice getConnectedDevice() {
        return device;
    }

    @Override
    public boolean sendDataToDevice(BluetoothDevice device, byte[] data) {
        return transport.send(device, data);
    }

    public void onBleConnected(BluetoothDevice device) {
        this.device = device;
        authenticated = false;
        postStatus("Bluetooth connected · authenticating…");
        rcspAuth.stopAuth(device, false);
        if (!rcspAuth.startAuth(device)) {
            postError("Could not start watch authentication");
        }
    }

    public void onBleData(BluetoothDevice device, byte[] data) {
        if (!authenticated) {
            rcspAuth.handleAuthData(device, data);
        } else {
            notifyReceiveDeviceData(device, data);
        }
    }

    public void onBleDisconnected(BluetoothDevice device) {
        if (device != null) {
            notifyBtDeviceConnection(device, StateCode.CONNECTION_DISCONNECT);
        }
        authenticated = false;
        this.device = null;
        postStatus("Disconnected");
    }

    /** Start the JieLi watch/RCSP initialization after authentication. */
    public void initializeAndSync() {
        if (device == null || !authenticated) {
            postError("Watch is not authenticated yet");
            return;
        }
        postStatus("Initializing watch protocol…");
        // The SDK starts its RCSP/watch initialization when CONNECTION_OK is received.
        // Give its internal state machine a moment before issuing operations.
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (released || device == null || !authenticated) return;
                syncClock();
                requestRealtimeHealth();
            }
        }, 1200);
    }

    private void requestRealtimeHealth() {
        if (device == null) return;
        postStatus("Reading steps, heart rate and SpO₂…");
        int mask = (1 << 0) | (1 << 3) | (1 << 5);
        byte[] subMask = new byte[]{0x01, 0x07, 0x01};
        healthOp.readHealthData(device, new HealthDataQuery((byte) 0, mask, subMask), new OnOperationCallback<Boolean>() {
            @Override public void onSuccess(Boolean result) {
                postStatus("Health sync complete");
            }
            @Override public void onFailed(BaseError error) {
                postError("Health sync failed: " + (error == null ? "unknown error" : error.toString()));
            }
        });
    }

    public void syncClockNow() {
        if (device == null || !authenticated) {
            postError("Connect the watch first");
            return;
        }
        syncClock();
    }

    public void syncNow() {
        if (device == null || !authenticated) {
            postError("Connect the watch first");
            return;
        }
        syncClock();
        requestRealtimeHealth();
        syncSleep();
    }

    /**
     * RTCOpImpl has had small API changes across JieLi SDK releases. Use the
     * documented syncTime operation reflectively so the app stays compatible
     * with the Maven-published SDK without hard-coding a callback interface.
     */
    private void syncClock() {
        if (device == null) return;
        try {
            Class<?> rtcClass = Class.forName("com.jieli.jl_rcsp.impl.RTCOpImpl");
            Object rtc = null;
            for (Constructor<?> c : rtcClass.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 1 && p[0].isAssignableFrom(getClass().getSuperclass())) {
                    rtc = c.newInstance(this);
                    break;
                }
                if (p.length == 1 && p[0].isAssignableFrom(getClass())) {
                    rtc = c.newInstance(this);
                    break;
                }
            }
            if (rtc == null) {
                for (Constructor<?> c : rtcClass.getConstructors()) {
                    if (c.getParameterTypes().length == 0) {
                        rtc = c.newInstance();
                        break;
                    }
                }
            }
            if (rtc == null) throw new IllegalStateException("RTCOpImpl constructor not found");

            Method[] methods = rtcClass.getMethods();
            for (Method m : methods) {
                if (!"syncTime".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && BluetoothDevice.class.isAssignableFrom(p[0])) {
                    m.invoke(rtc, device);
                    postStatus("Phone time synced ✓");
                    return;
                }
                if (p.length == 2 && BluetoothDevice.class.isAssignableFrom(p[0]) && p[1].isInterface()) {
                    Object callback = Proxy.newProxyInstance(
                            p[1].getClassLoader(),
                            new Class[]{p[1]},
                            (proxy, method, args) -> {
                                if ("onSuccess".equals(method.getName())) postStatus("Phone time synced ✓");
                                if ("onError".equals(method.getName())) postError("Time sync failed");
                                return null;
                            });
                    m.invoke(rtc, device, callback);
                    return;
                }
            }
            throw new NoSuchMethodException("syncTime");
        } catch (Throwable t) {
            postError("Time sync unavailable in SDK: " + t.getClass().getSimpleName());
        }
    }

    private void syncSleep() {
        if (device == null) return;
        try {
            QueryFileTask task = new QueryFileTask(this, new QueryFileTask.Param((byte) 0x05));
            task.setListener(new SimpleTaskListener() {
                @Override public void onFinish() {
                    List<QueryFileTask.File> files = task.getFiles();
                    if (files == null || files.isEmpty()) {
                        postStatus("No sleep history stored on watch");
                        return;
                    }
                    Collections.reverse(files);
                    int count = Math.min(3, files.size());
                    for (int i = 0; i < count; i++) readSleepFile(files.get(i));
                }
                @Override public void onError(int code, String msg) {
                    postError("Sleep history unavailable: " + code + (msg == null ? "" : " · " + msg));
                }
            });
            task.start();
        } catch (Throwable t) {
            postError("Sleep history error: " + t.getClass().getSimpleName());
        }
    }

    private void readSleepFile(QueryFileTask.File file) {
        try {
            ReadFileTask task = new ReadFileTask(this,
                    new ReadFileTask.Param(file.type, file.id, file.size, 0));
            task.setListener(new SimpleTaskListener() {
                @Override public void onFinish() {
                    parseSleep(task.getReadData());
                }
            });
            task.start();
        } catch (Throwable t) {
            postError("Sleep file read error: " + t.getClass().getSimpleName());
        }
    }

    private void parseSleep(byte[] data) {
        if (data == null || data.length < 11) return;
        int deep = 0, light = 0, rem = 0, awake = 0;
        int p = 11;
        while (p + 4 <= data.length) {
            int hh = data[p] & 0xFF;
            int mm = data[p + 1] & 0xFF;
            int len = (data[p + 2] & 0xFF) | ((data[p + 3] & 0xFF) << 8);
            p += 4;
            if (len < 0 || p + len > data.length) break;
            if (hh == 0xFF && mm == 0xFF) { p += len; continue; }
            for (int i = 0; i + 1 < len; i += 2) {
                int type = data[p + i] & 0xFF;
                int minutes = data[p + i + 1] & 0xFF;
                if (type == 0x01) light += minutes;
                else if (type == 0x02) deep += minutes;
                else if (type == 0x03) rem += minutes;
                else if (type == 0xFF) awake += minutes;
            }
            p += len;
        }
        if (deep + light + rem + awake > 0) {
            listener.onSleep(deep, light, rem, awake);
        }
    }

    public void releaseManager() {
        if (released) return;
        released = true;
        try { healthOp.getRcspOp().unregisterOnRcspEventListener(null); } catch (Throwable ignored) { }
        try { rcspAuth.destroy(); } catch (Throwable ignored) { }
        try { super.release(); } catch (Throwable ignored) { }
    }

    private void postStatus(final String s) { main.post(() -> listener.onStatus(s)); }
    private void postError(final String s) { main.post(() -> listener.onError(s)); }
}
