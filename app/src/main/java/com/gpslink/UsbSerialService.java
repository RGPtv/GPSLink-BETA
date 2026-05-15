package com.gpslink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class UsbSerialService extends Service implements SerialInputOutputManager.Listener {

    private static final String TAG              = "GPSlink";
    public  static final String CHANNEL_ID       = "GPS_Link";
    public  static final int    NOTIFICATION_ID  = 1;
    public  static final String ACTION_STATUS    = "com.gpslink.STATUS";
    public  static final String ACTION_SET_HZ    = "com.gpslink.SET_HZ";
    private static final String ACTION_USB_PERM  = "com.gpslink.USB_PERMISSION";
    public  static final String EXTRA_HZ         = "hz";
    public  static final String EXTRA_BAUD       = "baud";
    public  static final String ACTION_NTRIP_CONFIG_CHANGED = "com.gpslink.ACTION_NTRIP_CONFIG_CHANGED";

    private static final int[]  SUPPORTED_HZ     = {1, 5}; // P3-2: 10 removed; not exposed in UI
    public  static final int[]  SUPPORTED_BAUD   = {4800, 9600, 19200, 38400, 57600, 115200};
    private static final int    SERIAL_LOG_MAX   = 12;
    private static final int    UBLOX_VID        = 0x1546;
    private static final int[]  UBLOX_PIDS       = {0x01A7, 0x01A8, 0x01A9, 0x01AA};
    private static final int    DEFAULT_BAUD     = 9600;
    private static final int    MAX_AUTO_RETRIES = 10;
    private static final long   RETRY_DELAY_MS   = 3_000;
    private static final long   STALE_FIX_MS     = 10_000;

    // -- Shared state (guarded by STATE_LOCK where noted) ----------------------
    public static final Object STATE_LOCK = new Object();

    public static volatile boolean isRunning            = false;
    public static volatile int     currentHz            = 1;
    public static volatile int     currentBaud          = DEFAULT_BAUD;
    public static volatile String  lastConn             = "Not started";
    public static volatile String  lastSignal           = "\u2014";
    public static volatile String  lastPos              = "\u2014";
    public static volatile String  lastMovement         = "\u2014";
    public static volatile String  lastSerialLog        = "\u2014";
    public static volatile String  lastSatellites       = "\u2014";
    public static volatile String  lastSatsInView       = "\u2014";
    public static volatile String  lastSatsUsed         = "\u2014";
    public static volatile String  lastHeading          = "0\u00B0";
    public static volatile String  lastConstellationJson = "";
    public static volatile long    totalBytes           = 0;
    public static volatile int     totalSents           = 0;
    private static volatile long   lastBroadcastTime    = 0;
    public static volatile long    lastFixTime          = 0;
    public static volatile String  lastNtripStatus      = "";

    // -- Instance fields -------------------------------------------------------
    private LocationManager           locationManager;
    private PowerManager.WakeLock     wakeLock;
    private volatile SerialInputOutputManager ioManager;
    private volatile UsbSerialPort            serialPort;
    private String                    connectedDevName = "";
    private int                       retryCount       = 0;
    private final AtomicBoolean       active           = new AtomicBoolean(false);
    private final AtomicBoolean       connecting       = new AtomicBoolean(false);
    private ScheduledExecutorService  retryExecutor;
    private NtripClient               ntripClient;
    private int                       ubxWriteFailCount    = 0;
    private static final int          UBX_MAX_FAIL         = 3;
    // [Impr-9] Incremental serial log builder
    private final StringBuilder       serialLogBuilder     = new StringBuilder();

    // NMEA parsing: all fields below are only accessed inside nmeaLock.
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
    private final Map<String, Integer> gsvReportedTotal    = new LinkedHashMap<>();
    private final Map<String, java.util.Set<String>> gsvCurrentCyclePrns = new LinkedHashMap<>();
    private int     satsTrackedCount = 0;
    private boolean hasGGASatCount   = false;

    // -- UBX packet builders ---------------------------------------------------

    private static byte[] ubx(int cls, int id, byte[] payload) {
        byte[] msg = new byte[6 + payload.length + 2];
        msg[0] = (byte) 0xB5;
        msg[1] = (byte) 0x62;
        msg[2] = (byte) cls;
        msg[3] = (byte) id;
        msg[4] = (byte) (payload.length & 0xFF);
        msg[5] = (byte) ((payload.length >> 8) & 0xFF);
        System.arraycopy(payload, 0, msg, 6, payload.length);
        int a = 0, b = 0;
        for (int i = 2; i < 6 + payload.length; i++) {
            a = (a + (msg[i] & 0xFF)) & 0xFF;
            b = (b + a) & 0xFF;
        }
        msg[6 + payload.length]     = (byte) a;
        msg[6 + payload.length + 1] = (byte) b;
        return msg;
    }

    private static byte[] ubxCfgRate(int hz) {
        // BUG FIX: guard against hz==0 before division (already present, kept)
        int ms = (hz > 0) ? (1000 / hz) : 1000;
        return ubx(0x06, 0x08, new byte[]{
            (byte) (ms & 0xFF), (byte) (ms >> 8),
            0x01, 0x00,   // navRate = 1 (every measurement cycle)
            0x01, 0x00    // timeRef = 1 (GPS time)
        });
    }

    private static byte[] ubxCfgMsg(int msgCls, int msgId, int rate) {
        return ubx(0x06, 0x01, new byte[]{
            (byte) msgCls, (byte) msgId,
            0, (byte) rate, 0, (byte) rate, 0, 0
        });
    }

    /**
     * UBX-CFG-GNSS: enables GPS, GLONASS, Galileo, BeiDou.
     * M7 silently ignores Galileo/BeiDou blocks and applies GPS+GLONASS only.
     */
    private static byte[] ubxCfgGnss() {
        return ubx(0x06, 0x3E, new byte[]{
            0x00,              // msgVer
            0x00,              // numTrkChHw (0 = read from module)
            (byte) 0xFF,       // numTrkChUse (0xFF = use all available)
            0x04,              // numConfigBlocks = 4
            // GPS (gnssId=0): channels 8-16, enable, L1C/A
            0x00, 0x08, 0x10, 0x00,  (byte)0x01,0x00,0x01,0x01,
            // GLONASS (gnssId=6): channels 4-8, enable, L1OF
            0x06, 0x04, 0x08, 0x00,  (byte)0x01,0x00,0x01,0x01,
            // Galileo (gnssId=2): channels 4-8, enable, E1OS
            0x02, 0x04, 0x08, 0x00,  (byte)0x01,0x00,0x01,0x01,
            // BeiDou (gnssId=3): channels 2-4, enable, B1I
            0x03, 0x02, 0x04, 0x00,  (byte)0x01,0x00,0x01,0x01,
        });
    }

    /**
     * UBX-CFG-SBAS: enables SBAS ranging, correction, and integrity.
     * usage=0x07 enables all three: ranging + correction + integrity.
     * Integrity is important for MSAS (Japan/Philippines region) which
     * provides integrity signals even where ranging/correction is limited.
     * scanmode=0 means auto-scan all PRNs (WAAS, EGNOS, MSAS, GAGAN).
     */
    private static byte[] ubxCfgSbas() {
        return ubx(0x06, 0x16, new byte[]{
            0x01,                                           // mode: enable
            0x07,                                           // usage: ranging + correction + integrity
            0x03,                                           // maxSBAS channels
            0x00,                                           // scanmode2
            (byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00    // scanmode1: auto
        });
    }

    /**
     * UBX-CFG-CFG: save current config to flash so it survives power cycles.
     */
    private static byte[] ubxCfgCfg() {
        return ubx(0x06, 0x09, new byte[]{
            0x00, 0x00, 0x00, 0x00,                         // clearMask
            (byte)0xFF,(byte)0xFF, 0x00, 0x00,              // saveMask (all)
            0x00, 0x00, 0x00, 0x00                          // loadMask
        });
    }

    // -- Broadcast receivers ---------------------------------------------------

    private final BroadcastReceiver usbPermReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERM.equals(intent.getAction())) return;
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                broadcastConn("USB permission granted - connecting...");
                startConnectThread();
            } else {
                broadcastConn("USB permission denied - tap Allow when prompted");
            }
        }
    };

    private final BroadcastReceiver setHzReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_SET_HZ.equals(intent.getAction())) return;
            int hz = intent.getIntExtra(EXTRA_HZ, 1);
            if (isValidHz(hz) && hz != currentHz) {
                synchronized (STATE_LOCK) { currentHz = hz; }
                // Run on a background thread; never block a BroadcastReceiver.
                new Thread(UsbSerialService.this::applyHzConfig).start();
            }
        }
    };

    // [Issue-2] Receiver for NTRIP config changes from settings dialog
    private final BroadcastReceiver ntripConfigReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_NTRIP_CONFIG_CHANGED.equals(intent.getAction())) return;
            Log.d(TAG, "NTRIP config changed — restarting NtripClient");
            synchronized (UsbSerialService.this) {
                if (ntripClient != null) { ntripClient.stop(); ntripClient = null; }
            }
            startNtripIfEnabled();
        }
    };

    private static boolean isValidHz(int hz) {
        if (hz <= 0) return false;
        for (int h : SUPPORTED_HZ) if (h == hz) return true;
        return false;
    }

    private static boolean isValidBaud(int baud) {
        for (int b : SUPPORTED_BAUD) if (b == baud) return true;
        return false;
    }

    // -- Lifecycle -------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        active.set(true);
        retryExecutor = Executors.newSingleThreadScheduledExecutor();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        // BUG FIX: pm could theoretically be null; guard it
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GPSLink:serial");
        }
        createNotificationChannel();

        IntentFilter usbPermFilter = new IntentFilter(ACTION_USB_PERM);
        IntentFilter setHzFilter = new IntentFilter(ACTION_SET_HZ);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermReceiver, usbPermFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(setHzReceiver, setHzFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(ntripConfigReceiver, new IntentFilter(ACTION_NTRIP_CONFIG_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermReceiver, usbPermFilter);
            registerReceiver(setHzReceiver, setHzFilter);
            registerReceiver(ntripConfigReceiver, new IntentFilter(ACTION_NTRIP_CONFIG_CHANGED));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean wasRunning = isRunning;
        isRunning = true;
        if (intent != null) {
            int hz = intent.getIntExtra(EXTRA_HZ, 1);
            int requestedHz = isValidHz(hz) ? hz : 1;
            int baud = intent.getIntExtra(EXTRA_BAUD, DEFAULT_BAUD);
            int requestedBaud = isValidBaud(baud) ? baud : DEFAULT_BAUD;
            boolean hzChanged;
            boolean baudChanged;
            synchronized (STATE_LOCK) {
                hzChanged = currentHz != requestedHz;
                baudChanged = currentBaud != requestedBaud;
                currentHz = requestedHz;
                currentBaud = requestedBaud;
                if (!wasRunning) {
                    totalBytes  = 0;
                    totalSents  = 0;
                    lastFixTime = 0;
                    retryCount  = 0;
                }
            }
            if (!wasRunning) {
                synchronized (nmeaLock) {
                    serialLines.clear();
                    seenSats.clear();
                    gsvReportedTotal.clear();
                    gsvCurrentCyclePrns.clear();
                    satsTrackedCount  = 0;
                    hasGGASatCount    = false;
                    lastSatellites    = "\u2014";
                    lastSatsInView    = "\u2014";
                    lastSatsUsed      = "\u2014";
                }
            } else if (hzChanged) {
                new Thread(this::applyHzConfig).start();
            }
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10L * 60 * 60 * 1000); // 10-hour cap
        }
        String notificationText = (wasRunning && serialPort != null) ? lastConn : "Connecting...";
        startForeground(NOTIFICATION_ID, buildNotification(notificationText));
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
            try {
                retryExecutor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            retryExecutor = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try { unregisterReceiver(usbPermReceiver); } catch (Exception e) { Log.w(TAG, "Unregister usbPerm", e); }
        try { unregisterReceiver(setHzReceiver);   } catch (Exception e) { Log.w(TAG, "Unregister setHz", e);   }
        try { unregisterReceiver(ntripConfigReceiver); } catch (Exception e) { Log.w(TAG, "Unregister ntripCfg", e); }
        stopIo();
        removeProviders();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -- USB connection --------------------------------------------------------

    private void startConnectThread() {
        if (!active.get()) return;
        if (serialPort != null) return;
        if (!connecting.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                connectUsb();
            } finally {
                connecting.set(false);
            }
        }).start();
    }

    private UsbSerialProber buildProber() {
        ProbeTable t = UsbSerialProber.getDefaultProbeTable();
        for (int pid : UBLOX_PIDS) t.addProduct(UBLOX_VID, pid, CdcAcmSerialDriver.class);
        return new UsbSerialProber(t);
    }

    private void connectUsb() {
        if (!active.get()) return;

        UsbManager usbManager = (UsbManager) getSystemService(USB_SERVICE);
        // BUG FIX: usbManager can be null on some devices
        if (usbManager == null) {
            broadcastConn("USB service unavailable on this device");
            return;
        }

        List<UsbSerialDriver> drivers = buildProber().findAllDrivers(usbManager);

        // Fallback: try all connected USB devices as CDC-ACM
        if (drivers.isEmpty()) {
            ProbeTable fb = new ProbeTable();
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                fb.addProduct(d.getVendorId(), d.getProductId(), CdcAcmSerialDriver.class);
            }
            drivers = new UsbSerialProber(fb).findAllDrivers(usbManager);
        }

        if (drivers.isEmpty()) {
            broadcastConn("No UBLOX device found");
            return;
        }

        UsbSerialDriver driver     = drivers.get(0);
        UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
        if (connection == null) {
            broadcastConn("Waiting for USB permission...");
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERM).setPackage(getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(driver.getDevice(), pi);
            return;
        }

        // BUG FIX: driver.getPorts() may be empty on malformed drivers
        List<UsbSerialPort> ports = driver.getPorts();
        if (ports.isEmpty()) {
            broadcastConn("USB driver has no ports - unsupported device");
            connection.close();
            return;
        }

        try {
            synchronized (this) {
                if (!active.get()) {
                    connection.close();
                    return;
                }
                serialPort = ports.get(0);
                serialPort.open(connection);
                serialPort.setParameters(currentBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                serialPort.setDTR(true);
            }

            broadcastConn("Configuring u-blox (" + currentHz + " Hz)...");
            configureUblox();
            setupMockProviders();

            synchronized (this) {
                if (!active.get()) return;
                ioManager = new SerialInputOutputManager(serialPort, this);
                ioManager.setReadBufferSize(4096);
                ioManager.start();
            }

            connectedDevName = driver.getDevice().getDeviceName();
            retryCount = 0;
            broadcastConn("Connected \u00B7 " + connectedDevName + " \u00B7 " + currentBaud + " baud \u00B7 " + currentHz + " Hz");
            Log.d(TAG, "Connected: " + connectedDevName);
            
            startNtripIfEnabled();
        } catch (IOException e) {
            broadcastConn("USB open error: " + e.getMessage());
            Log.e(TAG, "connect error", e);
            stopIo();
        }
    }

    // -- UBX configuration -----------------------------------------------------

    private void configureUblox() {
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        sendUbx(ubxCfgRate(currentHz));

        // Enable all constellations (M7 silently ignores Galileo/BeiDou blocks)
        sendUbx(ubxCfgGnss());
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        // Enable SBAS (MSAS has partial Philippines coverage; others auto-scanned)
        sendUbx(ubxCfgSbas());
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        sendUbx(ubxCfgMsg(0xF0, 0x41, 0)); // disable GPTXT
        sendUbx(ubxCfgMsg(0xF0, 0x01, 0)); // disable GLL
        sendUbx(ubxCfgMsg(0xF0, 0x05, 0)); // disable VTG
        sendUbx(ubxCfgMsg(0xF0, 0x00, 1)); // GGA on
        sendUbx(ubxCfgMsg(0xF0, 0x04, 1)); // RMC on
        // GSA must be ON — it is the authoritative source of 2D/3D fix mode and
        // DOP values. Disabling it left fixMode stuck at 1 (no fix), causing the
        // fix type label to flicker between "No fix" and "GPS" on every GGA.
        sendUbx(ubxCfgMsg(0xF0, 0x02, 1)); // GSA on
        sendUbx(ubxCfgMsg(0xF0, 0x03, 1)); // GSV on  (all constellations)

        // Save config to flash so it survives power cycles
        sendUbx(ubxCfgCfg());
        Log.d(TAG, "UBX configured at " + currentHz + " Hz with multi-constellation + SBAS");
    }

    private void applyHzConfig() {
        UsbSerialPort port = serialPort; // local snapshot prevents disconnect race
        if (port == null) return;
        sendUbx(ubxCfgRate(currentHz));
        broadcastConn("Connected \u00B7 " + connectedDevName + " \u00B7 " + currentBaud + " baud \u00B7 " + currentHz + " Hz");
        Log.d(TAG, "Hz updated to " + currentHz);
    }

    // [Impr-5] Surface write failures after 3 consecutive retries
    private void sendUbx(byte[] msg) {
        UsbSerialPort port = serialPort;
        if (port == null) return;
        try {
            port.write(msg, 500);
            Thread.sleep(80);
            ubxWriteFailCount = 0; // reset on success
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            ubxWriteFailCount++;
            Log.w(TAG, "UBX write failed (" + ubxWriteFailCount + "/" + UBX_MAX_FAIL + "): " + e.getMessage());
            if (ubxWriteFailCount >= UBX_MAX_FAIL) {
                Log.e(TAG, "UBX write failed " + UBX_MAX_FAIL + " times — triggering reconnect");
                ubxWriteFailCount = 0;
                stopIo();
                onRunError(new java.io.IOException("UBX write failed " + UBX_MAX_FAIL + " times"));
            }
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
                UsbSerialPort port = serialPort;
                if (port == null) return;
                try {
                    byte[] toWrite = new byte[length];
                    System.arraycopy(data, 0, toWrite, 0, length);
                    int timeoutMs = Math.max(500, (length * 10 * 1000) / currentBaud + 200);
                    port.write(toWrite, timeoutMs);
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

    // -- Mock location providers -----------------------------------------------

    private void setupMockProviders() {
        if (locationManager == null) {
            Log.w(TAG, "Location service unavailable, skipping mock provider setup");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission missing, skipping mock provider setup");
            return;
        }
        tryAddProvider(LocationManager.GPS_PROVIDER,
                Criteria.POWER_HIGH,
                Criteria.ACCURACY_FINE);
        tryAddProvider(LocationManager.NETWORK_PROVIDER,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_COARSE);
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

    // -- Serial data reception -------------------------------------------------

    /**
     * Called by SerialInputOutputManager on a background thread whenever new
     * bytes arrive from the USB serial port.
     *
     * BUG FIX: totalBytes was incremented outside nmeaLock, creating a data
     * race with broadcastAll() which reads it under STATE_LOCK.  We use a
     * volatile long which is sufficient for the single-writer / multiple-reader
     * pattern here (SerialInputOutputManager calls onNewData serially).
     */
    @Override
    public void onNewData(byte[] data) {
        totalBytes += data.length; // volatile write, single writer thread

        synchronized (nmeaLock) {
            nmeaBuffer.append(new String(data, StandardCharsets.ISO_8859_1));
            int idx;
            while ((idx = nmeaBuffer.indexOf("\n")) >= 0) {
                String sentence = nmeaBuffer.substring(0, idx).trim();
                nmeaBuffer.delete(0, idx + 1);
                if (!sentence.isEmpty()) processSentence(sentence);
            }
            // Guard against a pathological stream with no newlines
            if (nmeaBuffer.length() > 512) {
                Log.w(TAG, "NMEA buffer overflow - discarding partial line");
                int lastNL = nmeaBuffer.lastIndexOf("\n");
                if (lastNL > 0) nmeaBuffer.delete(0, lastNL + 1);
                else            nmeaBuffer.setLength(0);
            }
        }
    }

    // -- NMEA sentence processing (called inside nmeaLock) --------------------

    private void processSentence(String sentence) {
        // Diagnostic TXT messages: log but don't parse as GPS data.
        if (sentence.startsWith("$GPTXT") || sentence.startsWith("$GNTXT")) {
            appendSerialLine(NmeaParser.verifyChecksum(sentence)
                    ? "[TXT] " + sentence : "[BAD CRC] " + sentence);
            broadcastSerial();
            return;
        }

        if (!NmeaParser.verifyChecksum(sentence)) {
            appendSerialLine("[BAD CRC] " + sentence);
            broadcastSerial();
            Log.d(TAG, "BAD CRC: " + sentence);
            return;
        }

        // GSV satellite-in-view data
        if (sentence.contains("GSV")) {
            List<NmeaParser.SatInfo> sats = NmeaParser.parseGSV(sentence);
            String[] parts = sentence.split(",", -1);
            if (parts.length > 2) {
                String talker = parts[0];
                String talkerConstellation = NmeaParser.constellationFromTalker(talker);
                boolean isFirstMsg = "1".equals(parts[2].trim());
                boolean isLastMsg  = parts.length > 1 && parts[1].trim().equals(parts[2].trim());

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
            // fixQuality comes from GGA field 6 (0=none,1=GPS,2=DGPS…) and uses
            // a completely different scale from GSA fixMode (1=none,2=2D,3=3D).
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
                // Any valid fix mode resets the counter immediately.
                noFixCount = 0;
            }

            // Only adopt GSA DOP when it's more precise than current GGA value.
            if (d.hdop < hdop) {
                hdop     = d.hdop;
                accuracy = Math.max(1.0f, d.hdop * 4.0f);
            }
            // Do NOT push location here — GSA doesn't update lat/lon.
            // Update UI only so DOP/fix-mode label refreshes.
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

    // -- Satellite summary -----------------------------------------------------

    private void updateSatelliteSummary() {
        Map<String, Integer> byConstellation   = new LinkedHashMap<>();
        Map<String, int[]>   snrByConstellation = new LinkedHashMap<>(); // [snrSum, satCount]

        for (NmeaParser.SatInfo s : seenSats.values()) {
            String key = constellationAbbr(s.constellation);
            byConstellation.put(key, byConstellation.getOrDefault(key, 0) + 1);
            if (s.snr > 0) {
                int[] acc = snrByConstellation.get(key);
                if (acc == null) { acc = new int[]{0, 0}; snrByConstellation.put(key, acc); }
                acc[0] += s.snr;
                acc[1]++;
            } else {
                snrByConstellation.putIfAbsent(key, new int[]{0, 0});
            }
        }

        // Build JSON for SignalBarsView: [{"label":"GPS","avgSnr":38,"count":6}, ...]
        StringBuilder json = new StringBuilder("[");
        boolean firstEntry = true;
        for (Map.Entry<String, Integer> e : byConstellation.entrySet()) {
            String label    = e.getKey();
            int    totalSat = e.getValue();
            int[]  snrAcc   = snrByConstellation.get(label);
            int    avgSnr   = (snrAcc != null && snrAcc[1] > 0) ? (snrAcc[0] / snrAcc[1]) : 0;
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

    // -- Location injection ----------------------------------------------------

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

    // -- Broadcast helpers -----------------------------------------------------

    private void broadcastConn(String msg) {
        synchronized (STATE_LOCK) { lastConn = msg; }
        updateNotification(msg);
        sendStatusBroadcast(new Intent(ACTION_STATUS).putExtra("conn", msg));
        Log.d(TAG, msg);
    }

    // [Impr-8] Cache getDisplayAccuracy() in a local variable
    private void broadcastAll() {
        long    nowMs   = System.currentTimeMillis();
        boolean stale   = (lastFixTime > 0) && (nowMs - lastFixTime > STALE_FIX_MS);
        String  satStr  = satellites == 1 ? "1 sat" : satellites + " sats";
        String  fixStr  = stale ? "Stale" : fixLabel(fixQuality);
        String  latDir  = latitude  >= 0 ? "N" : "S";
        String  lonDir  = longitude >= 0 ? "E" : "W";
        float   displayAcc = getDisplayAccuracy(); // compute once

        synchronized (STATE_LOCK) {
            String diffStr = "";
            if (fixQuality >= 2 && diffAge >= 0) {
                diffStr = String.format(Locale.US, "  Age: %.1fs", diffAge) + (!refStationId.isEmpty() ? " [" + refStationId + "]" : "");
            }
            lastSignal   = satStr + "  Fix: " + fixStr
                         + (fixMode == 3 ? " 3D" : fixMode == 2 ? " 2D" : "")
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
                .putExtra("signal",           lastSignal)
                .putExtra("position",         lastPos)
                .putExtra("movement",         lastMovement)
                .putExtra("serial",           lastSerialLog)
                .putExtra("satellites",       lastSatellites)
                .putExtra("bytes",            totalBytes)
                .putExtra("sents",            totalSents)
                .putExtra("fixtime",          lastFixTime)
                .putExtra("satsInView",       lastSatsInView)
                .putExtra("satsUsed",         lastSatsUsed)
                .putExtra("heading",          String.format("%.1f\u00B0", bearing))
                .putExtra("constellationJson", lastConstellationJson)
                .putExtra("ntripStatus",      lastNtripStatus)
                .putExtra("lat",              latitude)
                .putExtra("lon",              longitude));
    }

    private long lastSerialBroadcast = 0;

    private void broadcastSerial() {
        long now = System.currentTimeMillis();
        if (now - lastSerialBroadcast < 200) return;
        lastSerialBroadcast = now;
        sendStatusBroadcast(new Intent(ACTION_STATUS)
                .putExtra("serial",           lastSerialLog)
                .putExtra("bytes",            totalBytes)
                .putExtra("sents",            totalSents)
                .putExtra("satsInView",       lastSatsInView)
                .putExtra("satsUsed",         lastSatsUsed)
                .putExtra("satellites",       lastSatellites)
                .putExtra("constellationJson", lastConstellationJson)
                .putExtra("ntripStatus",      lastNtripStatus));
    }

    // -- Serial log ------------------------------------------------------------

    // [Impr-9] Incremental StringBuilder — rebuild only when a line is evicted
    private void appendSerialLine(String line) {
        boolean evicted = false;
        serialLines.addLast(line);
        if (serialLines.size() > SERIAL_LOG_MAX) {
            serialLines.removeFirst();
            evicted = true;
        }
        if (evicted) {
            // Must rebuild after eviction
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

    // -- Fix / compass labels --------------------------------------------------

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

    // -- Serial I/O error handling ---------------------------------------------

    @Override
    public void onRunError(Exception e) {
        Log.e(TAG, "Serial I/O error", e);
        synchronized (nmeaLock) {
            hasGGA = false;
            hasRMC = false;
            gpsTimeMs  = 0;
            noFixCount = 0;
        }
        stopIo();
        if (!active.get()) return;

        retryCount++;
        if (retryCount > MAX_AUTO_RETRIES) {
            broadcastConn("USB disconnected - tap Start to reconnect");
            retryCount = 0;
            return;
        }
        broadcastConn("Disconnected - retrying in 3 s (" + retryCount + "/" + MAX_AUTO_RETRIES + ")");
        if (retryExecutor != null && !retryExecutor.isShutdown()) {
            retryExecutor.schedule(
                    () -> { if (active.get()) startConnectThread(); },
                    RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void stopIo() {
        if (ntripClient != null) { ntripClient.stop(); ntripClient = null; }
        if (ioManager != null)  { ioManager.stop(); ioManager = null; }
        if (serialPort != null) {
            try { serialPort.close(); } catch (IOException ignored) {}
            serialPort = null;
        }
    }

    // -- Notification ----------------------------------------------------------

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "GPSLink GPS", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Live GPS from u-blox receiver");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPSLink \u00B7 " + currentHz + " Hz")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
