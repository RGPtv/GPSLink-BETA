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

public class UsbSerialService extends AbstractGpsService implements SerialInputOutputManager.Listener {

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
    // SERIAL_LOG_MAX, MAX_AUTO_RETRIES, RETRY_DELAY_MS, STALE_FIX_MS inherited from AbstractGpsService
    private static final int    UBLOX_VID        = 0x1546;
    private static final int[]  UBLOX_PIDS       = {0x01A7, 0x01A8, 0x01A9, 0x01AA};
    private static final int    DEFAULT_BAUD      = 9600;

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
    // locationManager inherited as protected from AbstractGpsService
    private PowerManager.WakeLock     wakeLock;
    private volatile SerialInputOutputManager ioManager;
    private volatile UsbSerialPort            serialPort;
    private String                    connectedDevName = "";
    private int                       retryCount       = 0;
    private final AtomicBoolean       active           = new AtomicBoolean(false);
    private final AtomicBoolean       connecting       = new AtomicBoolean(false);
    private ScheduledExecutorService  retryExecutor;
    private NtripClient               ntripClient;
    private int                       ubxWriteFailCount = 0;
    private static final int          UBX_MAX_FAIL      = 3;
    // NMEA parse state (nmeaBuffer, serialLines, nmeaLock, GPS fields, sat maps)
    // all inherited as protected from AbstractGpsService.

    // -- Abstract method implementations (bridge to public static volatile fields) --

    @Override protected String getTag()          { return TAG; }
    @Override protected Object getStateLock()    { return STATE_LOCK; }
    @Override protected String getActionStatus() { return ACTION_STATUS; }

    @Override protected long getLastFixTime()          { return lastFixTime; }
    @Override protected void setLastFixTime(long t)    { lastFixTime = t; }
    @Override protected long getLastBroadcastTime()    { return lastBroadcastTime; }
    @Override protected void setLastBroadcastTime(long t) { lastBroadcastTime = t; }
    @Override protected void setLastConn(String v)     { lastConn = v; }
    @Override protected long getTotalBytes()            { return totalBytes; }
    @Override protected int  getTotalSents()            { return totalSents; }
    @Override protected void setTotalSents(int v)       { totalSents = v; }
    @Override protected String getLastSerialLog()       { return lastSerialLog; }
    @Override protected void   setLastSerialLog(String v)       { lastSerialLog = v; }
    @Override protected String getLastSatellites()              { return lastSatellites; }
    @Override protected void   setLastSatellites(String v)      { lastSatellites = v; }
    @Override protected String getLastSatsInView()              { return lastSatsInView; }
    @Override protected void   setLastSatsInView(String v)      { lastSatsInView = v; }
    @Override protected String getLastSatsUsed()                { return lastSatsUsed; }
    @Override protected void   setLastSatsUsed(String v)        { lastSatsUsed = v; }
    @Override protected String getLastConstellationJson()       { return lastConstellationJson; }
    @Override protected void   setLastConstellationJson(String v) { lastConstellationJson = v; }
    @Override protected String getLastNtripStatus()             { return lastNtripStatus; }

    /** kept concrete: startIo wraps USB connect/start. */
    @Override protected void startIo() { startConnectThread(); }

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
            Log.d(TAG, "NTRIP config changed â€” restarting NtripClient");
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

    /**
     * [PossIss-4] Polls the serial port for a UBX-ACK-ACK (0x05/0x01) or
     * UBX-ACK-NAK (0x05/0x00) frame that references the given msgClass/msgId.
     *
     * Called before SerialInputOutputManager is started, so we read directly
     * from the open UsbSerialPort rather than going through onNewData().
     *
     * @param msgClass  UBX class byte of the command we are waiting to be ACK'd
     * @param msgId     UBX id byte of the command we are waiting to be ACK'd
     * @param timeoutMs maximum time to wait in milliseconds
     * @return true on ACK-ACK, false on ACK-NAK or timeout
     */
    private boolean waitForUbxAck(byte msgClass, byte msgId, long timeoutMs) {
        UsbSerialPort port = serialPort; // local snapshot â€” avoids race with stopIo()
        if (port == null) return false;

        // A UBX frame is: sync1(B5) sync2(62) cls(05) id(01|00) len_lo(02)
        //                 len_hi(00) ackCls ackId ckA ckB  â€” 10 bytes total.
        // We walk byte-by-byte through incoming data rather than assuming
        // frame boundaries align with read() call boundaries.
        final int BUF = 256; // enough to drain any NMEA chatter between writes
        byte[] buf = new byte[BUF];

        // Parser state for the minimal UBX-ACK frame
        int  state    = 0;   // position inside the expected frame header
        byte clsField = 0;   // cls byte of the pending ACK frame (always 0x05)
        byte idField  = 0;   // id byte: 0x01=ACK, 0x00=NAK
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int n;
            try {
                // Use remaining budget as the per-call read timeout.
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                n = port.read(buf, (int) Math.min(remaining, 100));
            } catch (IOException e) {
                Log.w(TAG, "waitForUbxAck: read error â€” " + e.getMessage());
                return false;
            }
            if (n <= 0) continue; // timeout slice elapsed, loop and re-check deadline

            // Walk every received byte through the frame state machine.
            for (int i = 0; i < n; i++) {
                byte b = buf[i];
                switch (state) {
                    case 0: if (b == (byte) 0xB5) state = 1; break;          // sync1
                    case 1: state = (b == (byte) 0x62) ? 2 : (b == (byte) 0xB5 ? 1 : 0); break; // sync2
                    case 2: clsField = b; state = (b == (byte) 0x05) ? 3 : 0; break; // cls=0x05
                    case 3: idField  = b; state = 4; break;                   // id: 0x01 ACK / 0x00 NAK
                    case 4: state = (b == 0x02) ? 5 : 0; break;              // len_lo=2
                    case 5: state = (b == 0x00) ? 6 : 0; break;              // len_hi=0
                    case 6: // ackCls â€” the class of the command being ACK'd
                        if (b == msgClass) { state = 7; }
                        else              { state = 0; } // ACK for a different command
                        break;
                    case 7: // ackId â€” the id of the command being ACK'd
                        if (b == msgId) {
                            // Frame matched: check whether it was ACK or NAK
                            boolean ack = (idField == (byte) 0x01);
                            return ack; // checksum bytes skipped â€” we trust the port framing
                        }
                        state = 0; // id mismatch â€” keep scanning
                        break;
                    default: state = 0;
                }
            }
        }
        return false; // deadline exceeded without a matching ACK
    }

    private void configureUblox() {
        // [PossIss-4] Every sendUbx() is immediately followed by waitForUbxAck()
        // so the driver blocks only as long as the module needs (â‰¤500 ms each)
        // rather than using fixed-duration sleep() calls.

        // Set measurement / navigation rate
        sendUbx(ubxCfgRate(currentHz)); // CFG-RATE: class 0x06, id 0x08
        if (!waitForUbxAck((byte) 0x06, (byte) 0x08, 500)) {
            Log.w(TAG, "CFG-RATE ACK timeout or NAK â€” rate may not have applied");
        }

        // Enable all constellations (M7 silently ignores Galileo/BeiDou blocks)
        sendUbx(ubxCfgGnss()); // CFG-GNSS: class 0x06, id 0x3E
        if (!waitForUbxAck((byte) 0x06, (byte) 0x3E, 500)) {
            Log.w(TAG, "CFG-GNSS ACK timeout or NAK â€” constellation config may be incomplete");
        }

        // Enable SBAS (MSAS has partial Philippines coverage; others auto-scanned)
        sendUbx(ubxCfgSbas()); // CFG-SBAS: class 0x06, id 0x16
        if (!waitForUbxAck((byte) 0x06, (byte) 0x16, 500)) {
            Log.w(TAG, "CFG-SBAS ACK timeout or NAK â€” SBAS may be disabled");
        }

        // NMEA message enable/disable â€” CFG-MSG (class 0x06, id 0x01).
        // Each call re-uses the same class/id so ACK polling uses those bytes.
        sendUbx(ubxCfgMsg(0xF0, 0x41, 0)); // disable GPTXT
        if (!waitForUbxAck((byte) 0x06, (byte) 0x01, 500)) {
            Log.w(TAG, "CFG-MSG(GPTXT) ACK timeout or NAK");
        }
        sendUbx(ubxCfgMsg(0xF0, 0x01, 0)); // disable GLL
        if (!waitForUbxAck((byte) 0x06, (byte) 0x01, 500)) {
            Log.w(TAG, "CFG-MSG(GLL) ACK timeout or NAK");
        }
        sendUbx(ubxCfgMsg(0xF0, 0x05, 0)); // disable VTG
        if (!waitForUbxAck((byte) 0x06, (byte) 0x01, 500)) {
            Log.w(TAG, "CFG-MSG(VTG) ACK timeout or NAK");
        }
        sendUbx(ubxCfgMsg(0xF0, 0x00, 1)); // GGA on
        if (!waitForUbxAck((byte) 0x06, (byte) 0x01, 500)) {
            Log.w(TAG, "CFG-MSG(GGA) ACK timeout or NAK");
        }
        sendUbx(ubxCfgMsg(0xF0, 0x04, 1)); // RMC on
        if (!waitForUbxAck((byte) 0x06, (byte) 0x01, 500)) {
            Log.w(TAG, "CFG-MSG(RMC) ACK timeout or NAK");
        }
        // GSA must be ON â€” it is the authoritative source of 2D/3D fix mode and
        // DOP values. Disabling it left fixMode stuck at 1 (no fix), causing the
        // fix type label to flicker between "No fix" and "GPS" on every GGA.
        sendUbx(ubxCfgMsg(0xF0, 0x02, 1)); // GSA on
        if (!waitForUbxAck((byte) 0x06, (byte) 0x01, 500)) {
            Log.w(TAG, "CFG-MSG(GSA) ACK timeout or NAK");
        }
        sendUbx(ubxCfgMsg(0xF0, 0x03, 1)); // GSV on (all constellations)
        if (!waitForUbxAck((byte) 0x06, (byte) 0x01, 500)) {
            Log.w(TAG, "CFG-MSG(GSV) ACK timeout or NAK");
        }

        // Save config to flash so it survives power cycles
        sendUbx(ubxCfgCfg()); // CFG-CFG: class 0x06, id 0x09
        if (!waitForUbxAck((byte) 0x06, (byte) 0x09, 500)) {
            Log.w(TAG, "CFG-CFG ACK timeout or NAK â€” config may not be persisted");
        }
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
                Log.e(TAG, "UBX write failed " + UBX_MAX_FAIL + " times â€” triggering reconnect");
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

    // -- Broadcast all GPS state to MainActivity --------------------------------

    @Override
    protected void broadcastAll() {
        synchronized (nmeaLock) {
            boolean stale = getLastFixTime() > 0
                    && System.currentTimeMillis() - getLastFixTime() > STALE_FIX_MS;

            String signal;
            String pos;
            String movement;

            if (!hasGGA && !hasRMC) {
                signal   = "\u2014";
                pos      = "\u2014";
                movement = "\u2014";
            } else if (stale) {
                signal   = "Stale";
                pos      = lastPos;     // keep last known
                movement = "Fix: Stale";
            } else {
                signal = fixLabel(fixQuality);
                pos = String.format(Locale.ROOT, "%.6f\u00B0 %s\n%.6f\u00B0 %s\n%.1f m MSL",
                        Math.abs(latitude),  latitude  >= 0 ? "N" : "S",
                        Math.abs(longitude), longitude >= 0 ? "E" : "W",
                        altitude);
                String fixStr = fixMode == 3 ? "3D " + fixLabel(fixQuality)
                        : fixMode == 2 ? "2D " + fixLabel(fixQuality)
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

    // -- Mock location providers -----------------------------------------------

    @Override
    protected void setupMockProviders() {
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

    @Override
    protected void tryAddProvider(String p, int power, int acc) {
        if (locationManager == null) return;
        try { locationManager.removeTestProvider(p); } catch (Exception ignored) {}
        try {
            locationManager.addTestProvider(p, false, false, false, false,
                    true, true, true, power, acc);
            locationManager.setTestProviderEnabled(p, true);
        } catch (SecurityException se) {
            Log.e(TAG, "MOCK_LOCATION permission denied for " + p + " â€” GPS injection disabled");
        } catch (Exception e) {
            Log.w(TAG, "addTestProvider " + p + ": " + e.getMessage());
        }
    }

    @Override
    protected void removeProviders() {
        if (locationManager == null) return;
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);     } catch (Exception ignored) {}
        try { locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
    }

    // -- Serial data reception -------------------------------------------------

    /**
     * Called by SerialInputOutputManager on a background thread whenever new
     * bytes arrive from the USB serial port.
     * nmeaBuffer, nmeaLock, processSentence() all inherited from AbstractGpsService.
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

    // processSentence(), getDisplayAccuracy(), updateSatelliteSummary(), constellationAbbr(),
    // pushLocation(), pushToProvider(), broadcastConn(), broadcastSerial(),
    // appendSerialLine(), sendStatusBroadcast(), fixLabel() â€” all in AbstractGpsService.

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

    @Override
    protected synchronized void stopIo() {
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

    @Override
    protected void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
