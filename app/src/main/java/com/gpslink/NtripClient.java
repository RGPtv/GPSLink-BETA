package com.gpslink;

import android.util.Base64;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class NtripClient {
    private static final String TAG = "NtripClient";
    private static final long GGA_INTERVAL_MS = 30_000;  // send GGA every 30 s
    private static final long MAX_BACKOFF_MS = 60_000;    // cap reconnect at 60 s
    private static final long INITIAL_BACKOFF_MS = 5_000; // first retry after 5 s

    public interface NtripListener {
        void onRtcmData(byte[] data, int length);
        void onStatusUpdate(String status);
    }

    // Allows the service to provide the rover's current position for the
    // GGA sentence that must be sent to VRS/network-RTK casters periodically.
    public interface PositionProvider {
        double getLatitude();
        double getLongitude();
        double getAltitude();
        boolean hasPosition(); // [Bug-4] explicit flag instead of lat==0&&lon==0
    }

    private final String host;
    private final int port;
    private final String mountpoint;
    private final String username;
    private final String password;
    private final NtripListener listener;
    private final boolean useTls; // [Impr-1] TLS support
    private volatile PositionProvider positionProvider;

    private Thread thread;
    private volatile boolean isRunning = false;
    private volatile Socket socket; // [Bug-1] volatile for cross-thread visibility

    // [PossIss-3] Scheduled executor for periodic GGA sending
    private final AtomicReference<ScheduledExecutorService> ggaExecutor = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> ggaFuture = new AtomicReference<>();

    /** [Impr-1] Constructor with TLS support param */
    public NtripClient(String host, int port, String mountpoint, String username, String password, NtripListener listener, boolean useTls) {
        this.host = host;
        this.port = port;
        this.mountpoint = mountpoint;
        this.username = username;
        this.password = password;
        this.listener = listener;
        this.useTls = useTls;
    }

    /** Set a position provider so GGA can be sent to VRS/network-RTK casters. */
    public void setPositionProvider(PositionProvider provider) {
        this.positionProvider = provider;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        thread = new Thread(this::runNtrip);
        thread.start();
    }

    public void stop() {
        isRunning = false;
        // [PossIss-3] Shut down GGA executor
        ScheduledFuture<?> f = ggaFuture.getAndSet(null);
        if (f != null) f.cancel(true);
        ScheduledExecutorService exec = ggaExecutor.getAndSet(null);
        if (exec != null) exec.shutdownNow();

        // [Bug-1] close socket before joining so blocked reads throw immediately
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
            socket = null;
        }

        Thread t = thread;
        if (t != null) {
            t.interrupt();
            // [Bug-3] join before nulling to let runNtrip() finish cleanly
            try { t.join(2000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    private void runNtrip() {
        long backoffMs = INITIAL_BACKOFF_MS;

        while (isRunning) {
            try {
                if (listener != null) listener.onStatusUpdate("Connecting to NTRIP...");
                Log.d(TAG, "Connecting to NTRIP caster: " + host + ":" + port);

                // [Impr-1] / [Issue-1] Create plain or TLS socket
                Socket sock;
                if (useTls || port == 443) {
                    SSLSocket sslSocket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
                    sslSocket.connect(new java.net.InetSocketAddress(host, port), 10_000);
                    sslSocket.setSoTimeout(10_000);
                    sslSocket.startHandshake();
                    sock = sslSocket;
                } else {
                    sock = new Socket();
                    sock.connect(new java.net.InetSocketAddress(host, port), 10_000);
                }
                socket = sock;
                socket.setSoTimeout(15_000); // 15 seconds for HTTP handshake

                OutputStream os = socket.getOutputStream();
                InputStream is = socket.getInputStream();

                String auth = username + ":" + password;
                String authBase64 = Base64.encodeToString(auth.getBytes(), Base64.NO_WRAP);

                // Send initial GGA if position is available (needed by VRS/network-RTK)
                String ggaSentence = buildGga();

                StringBuilder request = new StringBuilder();
                request.append("GET /").append(mountpoint).append(" HTTP/1.0\r\n")
                       .append("User-Agent: NTRIP GPSLink\r\n")
                       .append("Authorization: Basic ").append(authBase64).append("\r\n")
                       .append("Accept: */*\r\n")
                       .append("Connection: close\r\n");
                if (ggaSentence != null) {
                    request.append("Ntrip-GGA: ").append(ggaSentence).append("\r\n");
                }
                request.append("\r\n");

                os.write(request.toString().getBytes());
                os.flush();

                // Validate the HTTP response status line before reading data.
                // Valid responses: "ICY 200 OK" or "HTTP/1.x 200 OK".
                // A 401 or 404 should NOT trigger infinite reconnect.
                //
                // FIX: Do NOT wrap InputStream in BufferedReader — its internal
                // buffer would consume the first ~500 bytes of binary RTCM data,
                // which are then lost when we switch to is.read(buffer) below.
                // Instead, read headers byte-by-byte from the raw stream.
                String statusLine;
                do {
                    statusLine = readLine(is);
                } while (statusLine != null && statusLine.trim().isEmpty());

                if (statusLine == null) {
                    throw new java.io.IOException("Empty response from caster");
                }
                statusLine = statusLine.trim();
                Log.d(TAG, "NTRIP response: " + statusLine);

                String upper = statusLine.toUpperCase(Locale.ROOT);
                boolean isIcy200 = upper.startsWith("ICY 200");
                // [PossIss-8] Use regex for robust HTTP 200 check
                boolean isHttp200 = upper.matches("HTTP/[0-9.]+ 200( .*)?");
                
                if (isIcy200 || isHttp200) {
                    socket.setSoTimeout(60_000); // Relax timeout for binary RTCM stream
                }
                
                if (!isIcy200 && !isHttp200) {
                    // Non-recoverable error — don't reconnect for auth/not-found errors
                    String errMsg = "NTRIP Error: " + statusLine;
                    Log.e(TAG, errMsg);
                    if (listener != null) listener.onStatusUpdate(errMsg);
                    if (upper.contains("401") || upper.contains("403")
                            || upper.contains("404")) {
                        // Fatal error — stop reconnect attempts
                        isRunning = false;
                        return;
                    }
                    throw new java.io.IOException(errMsg);
                }

                // Skip remaining headers until blank line
                String headerLine;
                while ((headerLine = readLine(is)) != null) {
                    if (headerLine.trim().isEmpty()) break;
                }

                if (listener != null) listener.onStatusUpdate("NTRIP Connected");
                Log.d(TAG, "NTRIP Connected, waiting for data");

                // Reset backoff on successful connection
                backoffMs = INITIAL_BACKOFF_MS;

                // [PossIss-3] Start periodic GGA sending on a separate scheduled thread
                // so it fires independently of the read loop (which blocks on is.read())
                final OutputStream ggaOs = os;
                ScheduledExecutorService newExec = Executors.newSingleThreadScheduledExecutor();
                ggaExecutor.set(newExec);
                ScheduledFuture<?> newFuture = newExec.scheduleAtFixedRate(() -> {
                    if (!isRunning) return;
                    String gga = buildGga();
                    if (gga != null) {
                        try {
                            ggaOs.write((gga + "\r\n").getBytes());
                            ggaOs.flush();
                            Log.d(TAG, "Sent GGA to caster: " + gga);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to send GGA: " + e.getMessage());
                        }
                    }
                }, GGA_INTERVAL_MS, GGA_INTERVAL_MS, TimeUnit.MILLISECONDS);
                ggaFuture.set(newFuture);

                byte[] buffer = new byte[4096];
                long totalBytes = 0;

                while (isRunning) {
                    int read = is.read(buffer);
                    if (read < 0) {
                        Log.d(TAG, "NTRIP stream ended");
                        break;
                    }
                    if (read > 0) {
                        totalBytes += read;
                        if (listener != null) {
                            listener.onRtcmData(buffer, read);
                            listener.onStatusUpdate("NTRIP Connected \u00B7 " + fmtBytes(totalBytes));
                        }
                    }
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "NTRIP Error: " + e.getMessage());
                    if (listener != null) listener.onStatusUpdate("NTRIP Error: " + e.getMessage());
                }
            } finally {
                // Stop GGA scheduler for this connection cycle
                ScheduledFuture<?> f2 = ggaFuture.getAndSet(null);
                if (f2 != null) f2.cancel(true);
                ScheduledExecutorService exec2 = ggaExecutor.getAndSet(null);
                if (exec2 != null) exec2.shutdownNow();
                Socket s = socket;
                if (s != null) {
                    try { s.close(); } catch (Exception ignored) {}
                    socket = null;
                }
            }

            if (isRunning) {
                if (listener != null) {
                    listener.onStatusUpdate("NTRIP Reconnecting in " + (backoffMs / 1000) + " s...");
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // Exponential backoff capped at MAX_BACKOFF_MS
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        }
    }

    /**
     * Build a GPGGA sentence from the rover's current position.
     * Returns null if no position provider is set or position has never been acquired.
     */
    private String buildGga() {
        PositionProvider pp = positionProvider;
        if (pp == null) return null;
        // [Bug-4] Use explicit hasPosition flag instead of lat==0&&lon==0
        if (!pp.hasPosition()) return null;
        double lat = pp.getLatitude();
        double lon = pp.getLongitude();
        double alt = pp.getAltitude();

        // Convert decimal degrees to NMEA DDDMM.mmmmm format
        String latDir = lat >= 0 ? "N" : "S";
        lat = Math.abs(lat);
        int latDeg = (int) lat;
        double latMin = (lat - latDeg) * 60.0;
        String latStr = String.format(Locale.ROOT, "%02d%09.6f", latDeg, latMin);

        String lonDir = lon >= 0 ? "E" : "W";
        lon = Math.abs(lon);
        int lonDeg = (int) lon;
        double lonMin = (lon - lonDeg) * 60.0;
        String lonStr = String.format(Locale.ROOT, "%03d%09.6f", lonDeg, lonMin);

        // [Impr-2] Use java.time instead of Calendar for UTC time
        ZonedDateTime utcNow = Instant.now().atZone(ZoneOffset.UTC);
        String time = String.format(Locale.ROOT, "%02d%02d%02d.00",
                utcNow.getHour(), utcNow.getMinute(), utcNow.getSecond());

        // Minimal GGA: time, lat, N/S, lon, E/W, quality=1, sats=04, hdop=1.0, alt, M, geoid=0, M
        String body = String.format(Locale.ROOT,
                "GPGGA,%s,%s,%s,%s,%s,1,04,1.0,%.1f,M,0.0,M,,",
                time, latStr, latDir, lonStr, lonDir, alt);

        // Compute NMEA checksum (XOR of all chars between $ and *)
        int cksum = 0;
        for (int i = 0; i < body.length(); i++) {
            cksum ^= body.charAt(i);
        }

        return "$" + body + "*" + String.format(Locale.ROOT, "%02X", cksum);
    }

    /**
     * Read a single line (terminated by LF or CRLF) from the raw InputStream,
     * byte-by-byte. This avoids BufferedReader's read-ahead which would consume
     * binary RTCM data following the HTTP headers.
     * Returns null on EOF.
     */
    private static String readLine(InputStream is) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(128);
        int b;
        while ((b = is.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        if (b == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024f);
        return String.format(Locale.ROOT, "%.1f MB", b / (1024f * 1024f));
    }
}
