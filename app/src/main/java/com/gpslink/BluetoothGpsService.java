package com.gpslink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.Locale;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothGpsService extends AbstractGpsService {

    private static final String TAG             = "GPSlink-BT";
    public  static final String CHANNEL_ID      = "GPS_Link_BT";
    public  static final int    NOTIFICATION_ID = 2;
    public  static final String ACTION_STATUS   = "com.gpslink.STATUS";   // same as USB
    private static final String SPP_UUID        = "00001101-0000-1000-8000-00805F9B34FB";

    public  static final String EXTRA_BT_ADDRESS = "bt_address";
    public  static final String EXTRA_BT_NAME    = "bt_name";
    // ACTION_NTRIP_CONFIG_CHANGED lives in NtripConfig to avoid duplicates

    // SERIAL_LOG_MAX, MAX_AUTO_RETRIES, RETRY_DELAY_MS, STALE_FIX_MS inherited from AbstractGpsService

    // Shared with UsbSerialService so MainActivity can acquire a single lock for both services.
    public static final Object STATE_LOCK = UsbSerialService.STATE_LOCK;

    public static volatile boolean isRunning             = false;
    public static volatile String  lastConn              = "Not started";
    public static volatile String  lastSignal            = "\u2014";
    public static volatile String  lastPos               = "\u2014";
    public static volatile String  lastMovement          = "\u2014";
    public static volatile String  lastSerialLog         = "\u2014";
    public static volatile String  lastSatellites        = "\u2014";
    public static volatile String  lastSatsInView        = "\u2014";
    public static volatile String  lastSatsUsed          = "\u2014";
    public static volatile String  lastHeading           = "0\u00B0";
    public static volatile String  lastConstellationJson = "";
    public static volatile long    totalBytes            = 0;
    public static volatile int     totalSents            = 0;
    private static volatile long   lastBroadcastTime     = 0;
    public static volatile long    lastFixTime           = 0;
    public static volatile String  lastNtripStatus       = "";

    // -- Instance fields -------------------------------------------------------
    // locationManager inherited as protected from AbstractGpsService
    private PowerManager.WakeLock    wakeLock;
    private BluetoothSocket          btSocket;
    private InputStream              btInputStream;
    private Thread                   readerThread;
    private String                   connectedDevName = "";
    private static volatile String   targetAddress    = "";
    private volatile int             retryCount       = 0;
    private final AtomicBoolean      active           = new AtomicBoolean(false);
    private final AtomicBoolean      connecting       = new AtomicBoolean(false);
    private ScheduledExecutorService retryExecutor;
    private NtripClient              ntripClient;
    // NMEA parse state (nmeaBuffer, serialLines, nmeaLock, GPS fields, sat maps)
    // all inherited as protected from AbstractGpsService.

    // -- Abstract method implementations (bridge to public static volatile fields) --

    @Override protected String getTag()          { return TAG; }
    @Override protected Object getStateLock()    { return STATE_LOCK; }
    @Override protected String getActionStatus() { return ACTION_STATUS; }

    @Override protected long getLastFixTime()             { return lastFixTime; }
    @Override protected void setLastFixTime(long t)       { lastFixTime = t; }
    @Override protected long getLastBroadcastTime()       { return lastBroadcastTime; }
    @Override protected void setLastBroadcastTime(long t) { lastBroadcastTime = t; }
    @Override protected void setLastConn(String v)        { lastConn = v; }
    @Override protected long getTotalBytes()               { return totalBytes; }
    @Override protected int  getTotalSents()               { return totalSents; }
    @Override protected void setTotalSents(int v)          { totalSents = v; }
    @Override protected String getLastSerialLog()          { return lastSerialLog; }
    @Override protected void   setLastSerialLog(String v)          { lastSerialLog = v; }
    @Override protected String getLastSatellites()                 { return lastSatellites; }
    @Override protected void   setLastSatellites(String v)         { lastSatellites = v; }
    @Override protected String getLastSatsInView()                 { return lastSatsInView; }
    @Override protected void   setLastSatsInView(String v)         { lastSatsInView = v; }
    @Override protected String getLastSatsUsed()                   { return lastSatsUsed; }
    @Override protected void   setLastSatsUsed(String v)           { lastSatsUsed = v; }
    @Override protected String getLastConstellationJson()          { return lastConstellationJson; }
    @Override protected void   setLastConstellationJson(String v)  { lastConstellationJson = v; }
    @Override protected String getLastNtripStatus()                { return lastNtripStatus; }

    // -- Broadcast all GPS state to MainActivity --------------------------------

    @Override
    protected void broadcastAll() {
        synchronized (nmeaLock) {
            boolean stale = getLastFixTime() > 0
                    && System.currentTimeMillis() - getLastFixTime() > STALE_FIX_MS;

            // BT version zeroes displayFixMode when stale (per base-class comment)
            int displayFixMode = stale ? 1 : fixMode;

            String signal;
            String pos;
            String movement;

            if (!hasGGA && !hasRMC) {
                signal   = "\u2014";
                pos      = "\u2014";
                movement = "\u2014";
            } else if (stale) {
                signal   = "Stale";
                pos      = lastPos;
                movement = "Fix: Stale";
            } else {
                signal = fixLabel(fixQuality);
                pos = String.format(Locale.ROOT, "%.6f\u00B0 %s\n%.6f\u00B0 %s\n%.1f m MSL",
                        Math.abs(latitude),  latitude  >= 0 ? "N" : "S",
                        Math.abs(longitude), longitude >= 0 ? "E" : "W",
                        altitude);
                String fixStr = displayFixMode == 3 ? "3D " + fixLabel(fixQuality)
                        : displayFixMode == 2 ? "2D " + fixLabel(fixQuality)
                        : fixLabel(fixQuality);
                movement = String.format(Locale.ROOT,
                        "Speed: %.1f km/h\nCourse: %.0f\u00B0\nHDOP: %.1f\nFix: %s",
                        speed * 3.6f, bearing, hdop, fixStr);
            }

            lastSignal   = signal;
            lastPos      = pos;
            lastMovement = movement;
            lastHeading  = String.format(Locale.ROOT, "%.0f\u00B0", bearing);

            sendStatusBroadcast(new Intent(ACTION_STATUS)
                    .putExtra("signal",   signal)
                    .putExtra("position", pos)
                    .putExtra("movement", movement)
                    .putExtra("heading",  lastHeading)
                    .putExtra("fixtime",  getLastFixTime())
                    .putExtra("lat",      latitude)
                    .putExtra("lon",      longitude)
                    .putExtra("satsInView",        lastSatsInView)
                    .putExtra("satsUsed",          lastSatsUsed)
                    .putExtra("satellites",        lastSatellites)
                    .putExtra("constellationJson", lastConstellationJson)
                    .putExtra("ntripStatus",       lastNtripStatus)
                    .putExtra("bytes",             totalBytes)
                    .putExtra("sents",             totalSents));
        }
    }

    /** kept concrete: startIo wraps BT connect/start. */
    @Override protected void startIo() { startConnectThread(); }

    // [Issue-2] Receiver for NTRIP config changes from settings dialog
    private final BroadcastReceiver ntripConfigReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!NtripConfig.ACTION_NTRIP_CONFIG_CHANGED.equals(intent.getAction())) return;
            Log.d(TAG, "NTRIP config changed \u2014 restarting NtripClient");
            synchronized (BluetoothGpsService.this) {
                if (ntripClient != null) { ntripClient.stop(); ntripClient = null; }
            }
            startNtripIfEnabled();
        }
    };


    // -- Lifecycle -------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        active.set(true);
        retryExecutor = Executors.newSingleThreadScheduledExecutor();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GPSLink:bt");
        }
        createNotificationChannel();

        // [Issue-2] Register NTRIP config changed receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ntripConfigReceiver, new IntentFilter(NtripConfig.ACTION_NTRIP_CONFIG_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(ntripConfigReceiver, new IntentFilter(NtripConfig.ACTION_NTRIP_CONFIG_CHANGED));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null && targetAddress.isEmpty()) {
            stopSelf(); return START_NOT_STICKY;
        }

        boolean wasRunning = isRunning;
        isRunning = true;

        if (intent != null) {
            String addr = intent.getStringExtra(EXTRA_BT_ADDRESS);
            String name = intent.getStringExtra(EXTRA_BT_NAME);
            if (addr != null && !addr.isEmpty()) {
                targetAddress    = addr;
                connectedDevName = (name != null && !name.isEmpty()) ? name : addr;
            }
            if (!wasRunning) {
                synchronized (STATE_LOCK) {
                    totalBytes  = 0;
                    totalSents  = 0;
                    lastFixTime = 0;
                    retryCount  = 0;
                }
                synchronized (nmeaLock) {
                    serialLines.clear();
                    seenSats.clear();
                    gsvReportedTotal.clear();
                    gsvCurrentCyclePrns.clear();
                    satsTrackedCount = 0;
                    hasGGASatCount   = false;
                    lastSatellites   = "\u2014";
                    lastSatsInView   = "\u2014";
                    lastSatsUsed     = "\u2014";
                }
            }
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10L * 60 * 60 * 1000);
        }

        startForeground(NOTIFICATION_ID, buildNotification("Connecting to " + connectedDevName + "..."));
        startConnectThread();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        active.set(false);
        isRunning = false;
        connecting.set(false);
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
            try { retryExecutor.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            retryExecutor = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try { unregisterReceiver(ntripConfigReceiver); } catch (Exception e) { Log.w(TAG, "Unregister ntripCfg", e); }
        stopIo();
        removeProviders();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -- Connection -----------------------------------------------------------

    private void startConnectThread() {
        if (!active.get()) return;
        if (btSocket != null) return;
        if (!connecting.compareAndSet(false, true)) return;
        new Thread(() -> {
            try { connectBluetooth(); }
            finally { connecting.set(false); }
        }).start();
    }

    private void connectBluetooth() {
        if (!active.get()) return;

        if (targetAddress.isEmpty()) {
            broadcastConn("No Bluetooth device selected");
            return;
        }

        // P2-4: BluetoothAdapter.getDefaultAdapter() deprecated API 31+
        android.bluetooth.BluetoothManager bm =
                (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : null;
        if (adapter == null) {
            broadcastConn("Bluetooth not available on this device");
            return;
        }
        if (!adapter.isEnabled()) {
            broadcastConn("Bluetooth is disabled â€” please enable it");
            return;
        }

        // Check BLUETOOTH_CONNECT permission on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                broadcastConn("Bluetooth permission denied â€” grant in App Settings");
                return;
            }
        }

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(targetAddress);
        } catch (IllegalArgumentException e) {
            broadcastConn("Invalid Bluetooth address: " + targetAddress);
            return;
        }

        broadcastConn("Connecting to " + connectedDevName + "...");

        BluetoothSocket socket = null;
        try {
            // [Impr-7] Log cancelDiscovery() false return at DEBUG
            try {
                if (!adapter.cancelDiscovery()) {
                    Log.d(TAG, "cancelDiscovery: no scan was active");
                }
            } catch (Exception ignored) {}

            socket = device.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID));
            // [Issue-3] Wrap BT connect in timed Future (8s timeout)
            final BluetoothSocket connectSocket = socket;
            java.util.concurrent.ExecutorService connectExec = Executors.newSingleThreadExecutor();
            try {
                Future<?> connectFuture = connectExec.submit(() -> {
                    try { connectSocket.connect(); } catch (IOException e) { throw new RuntimeException(e); }
                });
                connectFuture.get(8, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                try { socket.close(); } catch (IOException ignored2) {}
                throw new IOException("Bluetooth connect timed out (8s)");
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
                throw new IOException("BT connect error: " + cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("BT connect interrupted");
            } finally {
                connectExec.shutdownNow();
            }

            synchronized (this) {
                if (!active.get()) {
                    try { socket.close(); } catch (IOException ignored) {}
                    return;
                }
                btSocket      = socket;
                btInputStream = socket.getInputStream();
            }

            setupMockProviders();
            retryCount = 0;

            broadcastConn("Connected \u00B7 " + connectedDevName);
            Log.d(TAG, "BT connected: " + connectedDevName + " [" + targetAddress + "]");

            startReaderThread();
            startNtripIfEnabled();

        } catch (IOException e) {
            Log.e(TAG, "BT connect error", e);
            broadcastConn("Failed to connect to " + connectedDevName + ": " + e.getMessage());
            if (socket != null) { try { socket.close(); } catch (IOException ignored) {} }
            btSocket      = null;
            btInputStream = null;
            scheduleRetry();
        }
    }

    private void startReaderThread() {
        synchronized (this) {
            if (!active.get()) return;
            readerThread = new Thread(() -> {
                byte[] buf = new byte[1024];
                try {
                    InputStream is = btInputStream;
                    while (active.get() && is != null) {
                        int n = is.read(buf);
                        if (n < 0) break; // stream closed
                        if (n > 0) onNewData(buf, n);
                    }
                } catch (IOException e) {
                    if (active.get()) {
                        Log.e(TAG, "BT read error", e);
                        handleDisconnect(e.getMessage());
                    }
                }
            }, "BT-NMEA-Reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }
    }

    // [Impr-4] kept concrete: startNtripIfEnabled() — RTCM write path differs (BT socket vs USB port).
    private void startNtripIfEnabled() {
        NtripConfig cfg = NtripConfig.load(this);
        if (!cfg.isValid()) return;

        NtripClient client = new NtripClient(cfg.host, cfg.port, cfg.mountpoint,
                cfg.username, cfg.password, new NtripClient.NtripListener() {
            @Override
            public void onRtcmData(byte[] data, int length) {
                BluetoothSocket socket = btSocket;
                if (socket == null) return;
                try {
                    socket.getOutputStream().write(data, 0, length);
                    socket.getOutputStream().flush();
                } catch (Exception e) {
                    Log.w(TAG, "RTCM write failed: " + e.getMessage());
                }
            }

            @Override
            public void onStatusUpdate(String status) {
                Log.d(TAG, "NTRIP: " + status);
                synchronized (STATE_LOCK) { lastNtripStatus = status; }
                sendStatusBroadcast(new Intent(ACTION_STATUS).putExtra("ntripStatus", lastNtripStatus));
            }
        }, cfg.useTls);
        // Supply rover position for GGA sentences sent to VRS/network-RTK casters
        client.setPositionProvider(new NtripClient.PositionProvider() {
            @Override public double getLatitude()  { synchronized (nmeaLock) { return latitude; } }
            @Override public double getLongitude() { synchronized (nmeaLock) { return longitude; } }
            @Override public double getAltitude()  { synchronized (nmeaLock) { return altitude; } }
            @Override public boolean hasPosition() { synchronized (nmeaLock) { return hasPosition; } }
        });

        synchronized (this) {
            if (!active.get()) return;
            ntripClient = client;
            ntripClient.start();
        }
    }

    private void handleDisconnect(String reason) {
        synchronized (nmeaLock) {
            hasGGA     = false;
            hasRMC     = false;
            gpsTimeMs  = 0;
            noFixCount = 0;
        }
        stopIo();
        if (!active.get()) return;

        retryCount++;
        if (retryCount > MAX_AUTO_RETRIES) {
            broadcastConn("BT disconnected â€” tap Start to reconnect");
            retryCount = 0;
            return;
        }
        broadcastConn("Disconnected â€” retrying in 3 s (" + retryCount + "/" + MAX_AUTO_RETRIES + ")");
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (retryExecutor != null && !retryExecutor.isShutdown()) {
            retryExecutor.schedule(
                    () -> { if (active.get()) startConnectThread(); },
                    RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    protected synchronized void stopIo() {
        if (ntripClient != null) { ntripClient.stop(); ntripClient = null; }
        // Close socket FIRST so a blocked read() throws IOException immediately
        if (btSocket != null) { try { btSocket.close(); } catch (IOException ignored) {} btSocket = null; }
        if (readerThread != null) { readerThread.interrupt(); readerThread = null; }
        if (btInputStream != null) {
            try { btInputStream.close(); } catch (IOException ignored) {}
            btInputStream = null;
        }
    }

    // -- NMEA data reception --------------------------------------------------

    private void onNewData(byte[] data, int length) {
        synchronized (nmeaLock) {
            totalBytes += length;
            nmeaBuffer.append(new String(data, 0, length, StandardCharsets.ISO_8859_1));
            int idx;
            while ((idx = nmeaBuffer.indexOf("\n")) >= 0) {
                String sentence = nmeaBuffer.substring(0, idx).trim();
                nmeaBuffer.delete(0, idx + 1);
                if (!sentence.isEmpty()) processSentence(sentence);
            }
            if (nmeaBuffer.length() > 512) {
                Log.w(TAG, "NMEA buffer overflow â€” discarding partial line");
                int lastNL = nmeaBuffer.lastIndexOf("\n");
                if (lastNL > 0) nmeaBuffer.delete(0, lastNL + 1);
                else            nmeaBuffer.setLength(0);
            }
        }
    }


    // -- Notification ---------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "GPSLink Bluetooth GPS", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Live GPS from Bluetooth receiver");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPSLink \u00B7 Bluetooth")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    protected void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
