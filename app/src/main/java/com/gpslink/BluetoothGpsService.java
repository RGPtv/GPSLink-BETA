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
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothGpsService extends Service {

    private static final String TAG             = "GPSlink-BT";
    public  static final String CHANNEL_ID      = "GPS_Link_BT";
    public  static final int    NOTIFICATION_ID = 2;
    public  static final String ACTION_STATUS   = "com.gpslink.STATUS";   // same as USB
    private static final String SPP_UUID        = "00001101-0000-1000-8000-00805F9B34FB";

    public  static final String EXTRA_BT_ADDRESS = "bt_address";
    public  static final String EXTRA_BT_NAME    = "bt_name";
    public  static final String ACTION_NTRIP_CONFIG_CHANGED = "com.gpslink.ACTION_NTRIP_CONFIG_CHANGED";

    private static final int  SERIAL_LOG_MAX  = 12;
    private static final int  MAX_AUTO_RETRIES = 10;
    private static final long RETRY_DELAY_MS  = 3_000;
    private static final long STALE_FIX_MS    = 10_000;

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
    private LocationManager          locationManager;
    private PowerManager.WakeLock    wakeLock;
    private BluetoothSocket          btSocket;
    private InputStream              btInputStream;
    private Thread                   readerThread;
    private String                   connectedDevName = "";
    private String                   targetAddress    = "";
    private int                      retryCount       = 0;
    private final AtomicBoolean      active           = new AtomicBoolean(false);
    private final AtomicBoolean      connecting       = new AtomicBoolean(false);
    private ScheduledExecutorService retryExecutor;
    private NtripClient              ntripClient;
    // [Impr-9] Incremental serial log builder
    private final StringBuilder      serialLogBuilder     = new StringBuilder();

    // [Issue-2] Receiver for NTRIP config changes from settings dialog
    private final BroadcastReceiver ntripConfigReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_NTRIP_CONFIG_CHANGED.equals(intent.getAction())) return;
            Log.d(TAG, "NTRIP config changed \u2014 restarting NtripClient");
            synchronized (BluetoothGpsService.this) {
                if (ntripClient != null) { ntripClient.stop(); ntripClient = null; }
            }
            startNtripIfEnabled();
        }
    };

    // NMEA parsing fields (accessed inside nmeaLock) --------------------------
    private final StringBuilder      nmeaBuffer  = new StringBuilder(512);
    private final ArrayDeque<String> serialLines = new ArrayDeque<>();
    private final Object             nmeaLock    = new Object();

    private double  latitude = 0, longitude = 0, altitude = 0;
    private boolean hasPosition = false; // [Bug-4] explicit position flag
    private float   speed = 0, bearing = 0, accuracy = 5.0f, hdop = 99.0f;
    private int     satellites = 0, fixQuality = 0, fixMode = 1;
    private int     noFixCount = 0; // consecutive GSA no-fix reports before clearing state
    private long    gpsTimeMs  = 0;
    private boolean hasGGA = false, hasRMC = false;
    private float   gstAccuracy = -1.0f;
    private long    lastGstTime = 0;
    private float   diffAge = -1.0f;
    private String  refStationId = "";

    private final Map<String, NmeaParser.SatInfo> seenSats = new LinkedHashMap<>();
    // Receiver-reported total satellites in view per GSV talker (GSV field 3).
    // Summed in updateSatelliteSummary() for the authoritative in-view count.
    private final Map<String, Integer> gsvReportedTotal    = new LinkedHashMap<>();
    // PRN keys seen in the current GSV cycle per talker, used to prune stale seenSats entries.
    private final Map<String, java.util.Set<String>> gsvCurrentCyclePrns = new LinkedHashMap<>();
    private int     satsTrackedCount = 0;
    private boolean hasGGASatCount   = false;

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
            registerReceiver(ntripConfigReceiver, new IntentFilter(ACTION_NTRIP_CONFIG_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(ntripConfigReceiver, new IntentFilter(ACTION_NTRIP_CONFIG_CHANGED));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
            broadcastConn("Bluetooth is disabled — please enable it");
            return;
        }

        // Check BLUETOOTH_CONNECT permission on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                broadcastConn("Bluetooth permission denied — grant in App Settings");
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

    // [Impr-4] Use NtripConfig value object
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
            broadcastConn("BT disconnected — tap Start to reconnect");
            retryCount = 0;
            return;
        }
        broadcastConn("Disconnected — retrying in 3 s (" + retryCount + "/" + MAX_AUTO_RETRIES + ")");
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (retryExecutor != null && !retryExecutor.isShutdown()) {
            retryExecutor.schedule(
                    () -> { if (active.get()) startConnectThread(); },
                    RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void stopIo() {
        if (ntripClient != null) { ntripClient.stop(); ntripClient = null; }
        // Close socket FIRST so a blocked read() throws IOException immediately
        if (btSocket != null) { try { btSocket.close(); } catch (IOException ignored) {} btSocket = null; }
        if (readerThread != null) { readerThread.interrupt(); readerThread = null; }
        if (btInputStream != null) { try { btInputStream.close(); } catch (IOException ignored) {} btInputStream = null; }
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
                Log.w(TAG, "NMEA buffer overflow — discarding partial line");
                int lastNL = nmeaBuffer.lastIndexOf("\n");
                if (lastNL > 0) nmeaBuffer.delete(0, lastNL + 1);
                else            nmeaBuffer.setLength(0);
            }
        }
    }

    // -- NMEA sentence processing (called inside nmeaLock) --------------------

    private void processSentence(String sentence) {
        if (sentence.startsWith("$GPTXT") || sentence.startsWith("$GNTXT")) {
            appendSerialLine(NmeaParser.verifyChecksum(sentence)
                    ? "[TXT] " + sentence : "[BAD CRC] " + sentence);
            broadcastSerial();
            return;
        }

        if (!NmeaParser.verifyChecksum(sentence)) {
            appendSerialLine("[BAD CRC] " + sentence);
            broadcastSerial();
            return;
        }

        if (sentence.contains("GSV")) {
            List<NmeaParser.SatInfo> sats = NmeaParser.parseGSV(sentence);
            String[] parts = sentence.split(",", -1);
            if (parts.length > 2) {
                String talker = parts[0];
                String talkerConstellation = NmeaParser.constellationFromTalker(talker);
                boolean isFirstMsg = "1".equals(parts[2].trim());
                boolean isLastMsg  = parts.length > 1 && parts[1].trim().equals(parts[2].trim());

                // Capture receiver's own total-in-view count from GSV field 3.
                if (isFirstMsg && parts.length > 3 && !parts[3].trim().isEmpty()) {
                    try {
                        gsvReportedTotal.put(talker, Integer.parseInt(parts[3].trim()));
                    } catch (NumberFormatException ignored) {}
                }

                if (isFirstMsg) {
                    if ("GNSS".equals(talkerConstellation)) {
                        gsvCurrentCyclePrns.clear();
                    }
                    gsvCurrentCyclePrns.put(talker, new java.util.HashSet<>());
                }

                if (!sats.isEmpty()) {
                    java.util.Set<String> cyclePrns = gsvCurrentCyclePrns.computeIfAbsent(
                            talker, k -> new java.util.HashSet<>());
                    for (NmeaParser.SatInfo s : sats) {
                        String key = s.constellation + ":" + s.prn;
                        seenSats.put(key, s);
                        cyclePrns.add(key);
                    }
                }

                // Prune seenSats entries for this talker that weren't in this cycle.
                if (isLastMsg) {
                    java.util.Set<String> seen = gsvCurrentCyclePrns.getOrDefault(
                            talker, java.util.Collections.emptySet());
                    String prefix = talkerConstellation + ":";
                    seenSats.entrySet().removeIf(
                            e -> e.getKey().startsWith(prefix) && !seen.contains(e.getKey()));
                }

                updateSatelliteSummary();
            }
            appendSerialLine(sentence);
            broadcastSerial();
            return;
        }

        appendSerialLine(sentence);
        broadcastSerial();

        NmeaParser.GpsData d = NmeaParser.parse(sentence);
        if (d == null || !d.valid) return;

        totalSents++;

        if ("GGA".equals(d.type)) {
            satellites       = d.satellites;
            fixQuality       = d.fixQuality;
            satsTrackedCount = d.satellites;
            hasGGASatCount   = true;

            if (d.satellites < 3 || d.fixQuality == 0) {
                hasGGA = false;
            } else {
                latitude         = d.latitude;
                longitude        = d.longitude;
                altitude         = d.altitude;
                hasPosition      = true; // [Bug-4]
                accuracy         = d.accuracy;
                hdop             = d.hdop;
                diffAge          = !Float.isNaN(d.diffAge) ? d.diffAge : diffAge;
                refStationId     = (d.refStationId != null) ? d.refStationId : refStationId;
                hasGGA           = true;
                noFixCount       = 0; // a valid GGA immediately resets the no-fix debounce counter
                
                if (!hasRMC && d.gpsTimeMs > 0) {
                    gpsTimeMs   = d.gpsTimeMs;
                }
                lastFixTime = System.currentTimeMillis();
                
                if (System.currentTimeMillis() - lastBroadcastTime > 200) {
                    pushLocation();
                    broadcastAll();
                    lastBroadcastTime = System.currentTimeMillis();
                }
            }
        } else if ("RMC".equals(d.type)) {
            if (d.fixMode == 1) {
                hasRMC = false;
            } else {
                speed   = d.speed;
                bearing = d.bearing;
                if (d.gpsTimeMs > 0) {
                    gpsTimeMs   = d.gpsTimeMs;
                }
                lastFixTime = System.currentTimeMillis();
                
                if (d.latitude != 0 || d.longitude != 0) {
                    latitude  = d.latitude;
                    longitude = d.longitude;
                }
                hasRMC = true;
                if (System.currentTimeMillis() - lastBroadcastTime > 200) {
                    pushLocation();
                    broadcastAll();
                    lastBroadcastTime = System.currentTimeMillis();
                }
            }
        } else if ("GSA".equals(d.type)) {
            // FIX: store fix mode separately — do NOT mix it into fixQuality.
            fixMode = d.fixMode;

            if (d.fixMode == 1) {
                // FIX: debounce — require several consecutive no-fix GSA reports
                // before clearing hasGGA. A single GSA no-fix during acquisition
                // or a momentary signal dip was previously enough to wipe a valid
                // GGA fix, causing the visible flicker between "No fix" and "GPS".
                noFixCount++;
                if (noFixCount >= 3) {
                    fixQuality = 0;
                    fixMode    = 1;
                    hasGGA     = false;
                    hasRMC     = false;
                    Log.d(TAG, "GSA: " + noFixCount + " consecutive no-fix reports — clearing fix state");
                }
            } else {
                noFixCount = 0;
            }

            // Only adopt GSA DOP when it's more precise than current GGA value.
            if (d.hdop < hdop) {
                hdop     = d.hdop;
                accuracy = Math.max(1.0f, d.hdop * 4.0f);
            }
            // FIX: removed GSA satellite count override that didn't exist
            // in USB service. GGA field 7 is the authoritative sat count.
            // Keeping both services consistent.
            // Do NOT push location here — GSA doesn't update lat/lon.
            broadcastAll();
        } else if ("VTG".equals(d.type)) {
            // VTG provides direct speed/course; use as supplement.
            speed   = d.speed;
            bearing = d.bearing;
            // FIX: do NOT push location here — VTG doesn't update lat/lon.
            broadcastAll();
        } else if ("GST".equals(d.type)) {
            gstAccuracy = d.accuracy;
            lastGstTime = System.currentTimeMillis();
            broadcastAll();
        }
    }

    private float getDisplayAccuracy() {
        if (gstAccuracy >= 0 && (System.currentTimeMillis() - lastGstTime < 5000)) {
            return gstAccuracy;
        }
        return accuracy;
    }

    // -- Satellite summary (identical logic to UsbSerialService) --------------

    private void updateSatelliteSummary() {
        Map<String, Integer> byConstellation   = new LinkedHashMap<>();
        Map<String, int[]>   snrByConstellation = new LinkedHashMap<>();

        for (NmeaParser.SatInfo s : seenSats.values()) {
            String key = constellationAbbr(s.constellation);
            byConstellation.put(key, byConstellation.getOrDefault(key, 0) + 1);
            if (s.snr > 0) {
                int[] acc = snrByConstellation.get(key);
                if (acc == null) { acc = new int[]{0, 0}; snrByConstellation.put(key, acc); }
                acc[0] += s.snr; acc[1]++;
            } else {
                snrByConstellation.putIfAbsent(key, new int[]{0, 0});
            }
        }

        StringBuilder json = new StringBuilder("[");
        boolean firstEntry = true;
        for (Map.Entry<String, Integer> e : byConstellation.entrySet()) {
            String label = e.getKey();
            int totalSat = e.getValue();
            int[] snrAcc = snrByConstellation.get(label);
            int avgSnr   = (snrAcc != null && snrAcc[1] > 0) ? (snrAcc[0] / snrAcc[1]) : 0;
            if (!firstEntry) json.append(",");
            json.append("{\"label\":\"").append(label)
                .append("\",\"avgSnr\":").append(avgSnr)
                .append(",\"count\":").append(totalSat)
                .append("}");
            firstEntry = false;
        }
        json.append("]");
        lastConstellationJson = json.toString();

        int displayUsed = hasGGASatCount ? satsTrackedCount : 0;
        // Sum the receiver's own reported totals across all talkers for the true in-view count.
        // Falling back to seenSats.size() only when no GSV totals have been received yet.
        int reportedTotal = 0;
        for (int v : gsvReportedTotal.values()) reportedTotal += v;
        int seen = reportedTotal > 0 ? reportedTotal : seenSats.size();
        int displaySeen = Math.max(seen, displayUsed);

        StringBuilder sb = new StringBuilder();
        sb.append(displaySeen).append(" seen \u00B7 ").append(displayUsed).append(" in use");
        if (!byConstellation.isEmpty()) {
            sb.append("  ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : byConstellation.entrySet()) {
                if (!first) sb.append(" ");
                sb.append(e.getKey()).append(":").append(e.getValue());
                first = false;
            }
        }
        lastSatellites = sb.toString();
        lastSatsInView = String.valueOf(displaySeen);
        lastSatsUsed   = hasGGASatCount ? String.valueOf(displayUsed) : "\u2014";
    }

    private static String constellationAbbr(String name) {
        if (name == null) return "UNK";
        switch (name) {
            case "GPS":     return "GPS";
            case "GLONASS": return "GLO";
            case "Galileo": return "GAL";
            case "BeiDou":  return "BDU";
            case "QZSS":    return "QZS";
            case "SBAS":    return "SBS";
            case "GNSS":    return "GNS";
            default:        return name.substring(0, Math.min(3, name.length())).toUpperCase();
        }
    }

    // -- Mock location injection ----------------------------------------------

    private void setupMockProviders() {
        if (locationManager == null) return;
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        tryAddProvider(LocationManager.GPS_PROVIDER,     Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
        tryAddProvider(LocationManager.NETWORK_PROVIDER, Criteria.POWER_LOW,  Criteria.ACCURACY_COARSE);
    }

    private void tryAddProvider(String p, int power, int acc) {
        if (locationManager == null) return;
        try { locationManager.removeTestProvider(p); } catch (Exception ignored) {}
        try {
            locationManager.addTestProvider(p, false, false, false, false,
                    true, true, true, power, acc);
            locationManager.setTestProviderEnabled(p, true);
        } catch (SecurityException se) {
            Log.e(TAG, "MOCK_LOCATION permission denied for " + p + " — GPS injection disabled");
        } catch (Exception e) {
            Log.w(TAG, "addTestProvider " + p + ": " + e.getMessage());
        }
    }

    private void removeProviders() {
        if (locationManager == null) return;
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);     } catch (Exception ignored) {}
        try { locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
    }

    private void pushLocation() {
        if (!hasGGA && !hasRMC) return;
        if (locationManager == null) return;
        // FIX: do not inject stale positions into the mock provider. Android's
        // location engine will reject them anyway once their elapsed-nanos age
        // exceeds its internal threshold, and doing so ourselves avoids feeding
        // apps a last-known position that is clearly outdated.
        long nowMs = System.currentTimeMillis();
        if (lastFixTime > 0 && nowMs - lastFixTime > STALE_FIX_MS) {
            Log.d(TAG, "Skipping pushLocation: fix is stale ("
                    + (nowMs - lastFixTime) + " ms old)");
            return;
        }
        long time  = gpsTimeMs > 0 ? gpsTimeMs : System.currentTimeMillis();
        long nanos = SystemClock.elapsedRealtimeNanos();
        pushToProvider(LocationManager.GPS_PROVIDER,     time, nanos);
        pushToProvider(LocationManager.NETWORK_PROVIDER, time, nanos);
    }

    private void pushToProvider(String provider, long time, long nanos) {
        try {
            Location loc = new Location(provider);
            loc.setLatitude(latitude);
            loc.setLongitude(longitude);
            loc.setAltitude(altitude);
            loc.setAccuracy(getDisplayAccuracy());
            loc.setSpeed(speed);
            loc.setBearing(bearing);
            loc.setTime(time);
            loc.setElapsedRealtimeNanos(nanos);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                float acc = getDisplayAccuracy();
                loc.setVerticalAccuracyMeters(acc * 1.5f);
                loc.setSpeedAccuracyMetersPerSecond(acc * 0.1f);
                loc.setBearingAccuracyDegrees(acc * 2.0f);
            }
            
            locationManager.setTestProviderLocation(provider, loc);
        } catch (Exception e) {
            Log.w(TAG, "push " + provider + ": " + e.getMessage());
        }
    }

    // -- Broadcast helpers ----------------------------------------------------

    private void broadcastConn(String msg) {
        synchronized (STATE_LOCK) { lastConn = msg; }
        updateNotification(msg);
        sendStatusBroadcast(new Intent(ACTION_STATUS).putExtra("conn", msg));
        Log.d(TAG, msg);
    }

    // [Impr-8] Cache getDisplayAccuracy() in a local variable
    private void broadcastAll() {
        long    nowMs  = System.currentTimeMillis();
        boolean stale  = (lastFixTime > 0) && (nowMs - lastFixTime > STALE_FIX_MS);
        String  satStr = satellites == 1 ? "1 sat" : satellites + " sats";
        String  fixStr = stale ? "Stale" : fixLabel(fixQuality);
        int     displayFixMode = stale ? 1 : fixMode;
        String  latDir = latitude  >= 0 ? "N" : "S";
        String  lonDir = longitude >= 0 ? "E" : "W";
        float   displayAcc = getDisplayAccuracy(); // compute once

        synchronized (STATE_LOCK) {
            String diffStr = "";
            if (fixQuality >= 2 && diffAge >= 0) {
                diffStr = String.format(Locale.US, "  Age: %.1fs", diffAge) + (!refStationId.isEmpty() ? " [" + refStationId + "]" : "");
            }
            lastSignal   = satStr + "  Fix: " + fixStr
                         + (displayFixMode == 3 ? " 3D" : displayFixMode == 2 ? " 2D" : "")
                         + "  HDOP: " + String.format("%.1f", hdop)
                         + "  \u00B1" + String.format(displayAcc < 1.0f ? "%.2f" : "%.0f", displayAcc) + " m"
                         + diffStr;
            if (lastFixTime > 0) {
                lastPos = String.format("%.6f\u00B0 %s\n%.6f\u00B0 %s\nAlt: %.1f m",
                        Math.abs(latitude),  latDir,
                        Math.abs(longitude), lonDir,
                        altitude);
            } else {
                lastPos = "\u2014";
            }
            lastMovement = String.format("Speed: %.1f km/h\nCourse: %.1f\u00B0\nHDOP: %.2f\nFix: %s",
                           speed * 3.6f, bearing, hdop, fixStr);
            lastHeading  = String.format("%.1f\u00B0", bearing);
        }

        updateNotification(String.format("%.5f, %.5f  %s  \u00B1%s m",
                latitude, longitude, fixStr, String.format(displayAcc < 1.0f ? "%.2f" : "%.0f", displayAcc)));

        sendStatusBroadcast(new Intent(ACTION_STATUS)
                .putExtra("signal",            lastSignal)
                .putExtra("position",          lastPos)
                .putExtra("movement",          lastMovement)
                .putExtra("serial",            lastSerialLog)
                .putExtra("satellites",        lastSatellites)
                .putExtra("bytes",             totalBytes)
                .putExtra("sents",             totalSents)
                .putExtra("fixtime",           lastFixTime)
                .putExtra("satsInView",        lastSatsInView)
                .putExtra("satsUsed",          lastSatsUsed)
                .putExtra("heading",           String.format("%.1f\u00B0", bearing))
                .putExtra("constellationJson", lastConstellationJson)
                .putExtra("ntripStatus",       lastNtripStatus)
                .putExtra("lat",               latitude)
                .putExtra("lon",               longitude));
    }

    private long lastSerialBroadcast = 0;

    private void broadcastSerial() {
        long now = System.currentTimeMillis();
        if (now - lastSerialBroadcast < 200) return;
        lastSerialBroadcast = now;
        sendStatusBroadcast(new Intent(ACTION_STATUS)
                .putExtra("serial",            lastSerialLog)
                .putExtra("bytes",             totalBytes)
                .putExtra("sents",             totalSents)
                .putExtra("satsInView",        lastSatsInView)
                .putExtra("satsUsed",          lastSatsUsed)
                .putExtra("satellites",        lastSatellites)
                .putExtra("constellationJson", lastConstellationJson)
                .putExtra("ntripStatus",       lastNtripStatus));
    }

    // [Impr-9] Incremental StringBuilder — rebuild only when a line is evicted
    private void appendSerialLine(String line) {
        boolean evicted = false;
        serialLines.addLast(line);
        if (serialLines.size() > SERIAL_LOG_MAX) {
            serialLines.removeFirst();
            evicted = true;
        }
        if (evicted) {
            serialLogBuilder.setLength(0);
            for (String l : serialLines) {
                if (serialLogBuilder.length() > 0) serialLogBuilder.append('\n');
                serialLogBuilder.append(l);
            }
        } else {
            if (serialLogBuilder.length() > 0) serialLogBuilder.append('\n');
            serialLogBuilder.append(line);
        }
        lastSerialLog = serialLogBuilder.toString();
    }

    private void sendStatusBroadcast(Intent intent) {
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    // -- Fix label ------------------------------------------------------------

    private static String fixLabel(int q) {
        switch (q) {
            case 1:  return "GPS";
            case 2:  return "DGPS";
            case 3:  return "PPS";
            case 4:  return "RTK Fixed";
            case 5:  return "RTK Float";
            case 6:  return "Dead Reckoning";
            default: return "No fix";
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

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
