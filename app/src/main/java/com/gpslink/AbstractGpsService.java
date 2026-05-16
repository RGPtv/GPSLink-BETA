package com.gpslink;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AbstractGpsService — shared base for UsbSerialService and BluetoothGpsService.
 *
 * Contains all NMEA parse state (instance fields) and every helper method that
 * is byte-for-byte identical between the two concrete services.
 *
 * Concrete classes are responsible for:
 *  - Their own public static volatile fields (lastConn, lastFixTime, …) — kept
 *    there for backward-compat with MainActivity; bridged here via abstract accessors.
 *  - startIo() / stopIo() — transport-specific I/O lifecycle.
 *  - broadcastAll()        — body differs between USB (fixMode) and BT (displayFixMode).
 *  - startNtripIfEnabled() — onRtcmData write path differs (USB port vs BT socket).
 *  - createNotificationChannel() / buildNotification() / updateNotification() — differ.
 */
abstract class AbstractGpsService extends Service {

    // -- Constants shared by both services ------------------------------------
    protected static final int  SERIAL_LOG_MAX   = 12;
    protected static final long RETRY_DELAY_MS   = 3_000;
    protected static final long STALE_FIX_MS     = 10_000;
    protected static final int  MAX_AUTO_RETRIES = 10;

    // -- Abstract: identity / lock bridge -------------------------------------

    /** Returns the concrete class's TAG constant for Log calls. */
    protected abstract String getTag();

    /** Returns the concrete class's STATE_LOCK object. */
    protected abstract Object getStateLock();

    /** Returns the concrete class's ACTION_STATUS string. */
    protected abstract String getActionStatus();

    // -- Abstract: accessors for public static volatile fields ----------------
    // These fields must stay in the concrete classes (MainActivity reads them
    // directly as statics). The base class accesses them via these bridges.

    protected abstract long   getLastFixTime();
    protected abstract void   setLastFixTime(long t);

    /** lastBroadcastTime is private volatile in each concrete class. */
    protected abstract long   getLastBroadcastTime();
    protected abstract void   setLastBroadcastTime(long t);

    /** Write lastConn (synchronized by caller via STATE_LOCK). */
    protected abstract void   setLastConn(String v);

    protected abstract long   getTotalBytes();

    protected abstract int    getTotalSents();
    protected abstract void   setTotalSents(int v);

    protected abstract String getLastSerialLog();
    protected abstract void   setLastSerialLog(String v);

    protected abstract String getLastSatellites();
    protected abstract void   setLastSatellites(String v);

    protected abstract String getLastSatsInView();
    protected abstract void   setLastSatsInView(String v);

    protected abstract String getLastSatsUsed();
    protected abstract void   setLastSatsUsed(String v);

    protected abstract String getLastConstellationJson();
    protected abstract void   setLastConstellationJson(String v);

    /** Read-only from base — written by concrete startNtripIfEnabled(). */
    protected abstract String getLastNtripStatus();

    // -- Abstract: methods whose bodies differ between USB and BT -------------

    /**
     * kept concrete: broadcastAll() — USB uses raw fixMode for 2D/3D label;
     * BT correctly zeroes it out when stale via a local displayFixMode variable.
     * Merging would silently change USB behaviour.
     */
    protected abstract void broadcastAll();

    /**
     * kept concrete: updateNotification() — depends on service-specific
     * NOTIFICATION_ID and buildNotification() which differ per service.
     */
    protected abstract void updateNotification(String text);

    // -- Abstract: I/O lifecycle ----------------------------------------------

    /** Start transport-specific I/O (USB SerialInputOutputManager / BT reader thread). */
    protected abstract void startIo();

    /** Tear down transport-specific I/O and the NtripClient. */
    protected abstract void stopIo();

    // -- NMEA parse instance state --------------------------------------------
    // All fields below are accessed only inside nmeaLock (unless noted).

    protected final Object             nmeaLock        = new Object();
    protected final StringBuilder      nmeaBuffer      = new StringBuilder(512);
    protected final ArrayDeque<String> serialLines     = new ArrayDeque<>();
    protected final StringBuilder      serialLogBuilder= new StringBuilder();
    protected long   lastSerialBroadcast = 0; // guarded by nmeaLock for write; volatile not needed (single writer)

    protected double  latitude = 0, longitude = 0, altitude = 0;
    protected boolean hasPosition = false;           // [Bug-4] explicit position flag
    protected float   speed = 0, bearing = 0, accuracy = 5.0f, hdop = 99.0f;
    protected int     satellites = 0, fixQuality = 0, fixMode = 1;
    protected int     noFixCount = 0;                // consecutive GSA no-fix reports
    protected long    gpsTimeMs  = 0;
    protected boolean hasGGA = false, hasRMC = false;
    protected float   gstAccuracy = -1.0f;
    protected long    lastGstTime = 0;
    protected float   diffAge = -1.0f;
    protected String  refStationId = "";

    protected final Map<String, NmeaParser.SatInfo>         seenSats            = new LinkedHashMap<>();
    protected final Map<String, Integer>                     gsvReportedTotal    = new LinkedHashMap<>();
    protected final Map<String, java.util.Set<String>>       gsvCurrentCyclePrns = new LinkedHashMap<>();
    protected int     satsTrackedCount = 0;
    protected boolean hasGGASatCount   = false;

    // -- Shared infrastructure fields -----------------------------------------

    protected LocationManager          locationManager;

    // -- NMEA sentence processing (called inside nmeaLock) --------------------

    /**
     * Dispatches a single NMEA sentence.  Called by both services' onNewData paths.
     * Minor absorbed difference: USB had an extra Log.d on bad-CRC; kept here (debug-only).
     */
    protected void processSentence(String sentence) {
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
            Log.d(getTag(), "BAD CRC: " + sentence); // absorbed minor diff: USB had this, BT didn't
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

        setTotalSents(getTotalSents() + 1);

        if ("GGA".equals(d.type)) {
            satellites       = d.satellites;
            fixQuality       = d.fixQuality;
            satsTrackedCount = d.satellites;
            hasGGASatCount   = true;

            if (d.satellites < 3 || d.fixQuality == 0) {
                hasGGA = false;
            } else {
                latitude     = d.latitude;
                longitude    = d.longitude;
                altitude     = d.altitude;
                hasPosition  = true; // [Bug-4]
                accuracy     = d.accuracy;
                hdop         = d.hdop;
                diffAge      = !Float.isNaN(d.diffAge) ? d.diffAge : diffAge;
                refStationId = (d.refStationId != null) ? d.refStationId : refStationId;
                hasGGA       = true;
                noFixCount   = 0; // valid GGA resets debounce counter

                if (!hasRMC && d.gpsTimeMs > 0) {
                    gpsTimeMs = d.gpsTimeMs;
                }
                setLastFixTime(System.currentTimeMillis());

                if (System.currentTimeMillis() - getLastBroadcastTime() > 200) {
                    pushLocation();
                    broadcastAll();
                    setLastBroadcastTime(System.currentTimeMillis());
                }
            }
        } else if ("RMC".equals(d.type)) {
            if (d.fixMode == 1) {
                hasRMC = false;
            } else {
                speed   = d.speed;
                bearing = d.bearing;
                if (d.gpsTimeMs > 0) {
                    gpsTimeMs = d.gpsTimeMs;
                }
                setLastFixTime(System.currentTimeMillis());

                if (d.latitude != 0 || d.longitude != 0) {
                    latitude  = d.latitude;
                    longitude = d.longitude;
                }
                hasRMC = true;
                if (System.currentTimeMillis() - getLastBroadcastTime() > 200) {
                    pushLocation();
                    broadcastAll();
                    setLastBroadcastTime(System.currentTimeMillis());
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
                    Log.d(getTag(), "GSA: " + noFixCount + " consecutive no-fix reports — clearing fix state");
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

    protected float getDisplayAccuracy() {
        if (gstAccuracy >= 0 && (System.currentTimeMillis() - lastGstTime < 5000)) {
            return gstAccuracy;
        }
        return accuracy;
    }

    // -- Satellite summary ----------------------------------------------------

    protected void updateSatelliteSummary() {
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
            String label   = e.getKey();
            int    totalSat = e.getValue();
            int[]  snrAcc  = snrByConstellation.get(label);
            int    avgSnr  = (snrAcc != null && snrAcc[1] > 0) ? (snrAcc[0] / snrAcc[1]) : 0;
            if (!firstEntry) json.append(",");
            json.append("{\"label\":\"").append(label)
                .append("\",\"avgSnr\":").append(avgSnr)
                .append(",\"count\":").append(totalSat)
                .append("}");
            firstEntry = false;
        }
        json.append("]");
        setLastConstellationJson(json.toString());

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
        setLastSatellites(sb.toString());
        setLastSatsInView(String.valueOf(displaySeen));
        setLastSatsUsed(hasGGASatCount ? String.valueOf(displayUsed) : "\u2014");
    }

    protected static String constellationAbbr(String name) {
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

    // -- Mock location providers ----------------------------------------------

    /**
     * Sets up GPS + NETWORK mock providers.
     * Absorbed minor diff: USB logged a warning when locationManager/permission was
     * missing; BT returned silently. The Log.w lines are kept (debug-only, harmless).
     */
    protected void setupMockProviders() {
        if (locationManager == null) {
            Log.w(getTag(), "Location service unavailable, skipping mock provider setup");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(getTag(), "Location permission missing, skipping mock provider setup");
            return;
        }
        tryAddProvider(LocationManager.GPS_PROVIDER,     Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
        tryAddProvider(LocationManager.NETWORK_PROVIDER, Criteria.POWER_LOW,  Criteria.ACCURACY_COARSE);
    }

    protected void tryAddProvider(String p, int power, int acc) {
        if (locationManager == null) return;
        try { locationManager.removeTestProvider(p); } catch (Exception ignored) {}
        try {
            locationManager.addTestProvider(p, false, false, false, false,
                    true, true, true, power, acc);
            locationManager.setTestProviderEnabled(p, true);
        } catch (SecurityException se) {
            Log.e(getTag(), "MOCK_LOCATION permission denied for " + p + " — GPS injection disabled");
        } catch (Exception e) {
            Log.w(getTag(), "addTestProvider " + p + ": " + e.getMessage());
        }
    }

    protected void removeProviders() {
        if (locationManager == null) return;
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);     } catch (Exception ignored) {}
        try { locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
    }

    // -- Location injection ---------------------------------------------------

    protected void pushLocation() {
        if (!hasGGA && !hasRMC) return;
        if (locationManager == null) return;
        // FIX: do not inject stale positions into the mock provider. Android's
        // location engine will reject them anyway once their elapsed-nanos age
        // exceeds its internal threshold, and doing so ourselves avoids feeding
        // apps a last-known position that is clearly outdated.
        long nowMs = System.currentTimeMillis();
        if (getLastFixTime() > 0 && nowMs - getLastFixTime() > STALE_FIX_MS) {
            Log.d(getTag(), "Skipping pushLocation: fix is stale ("
                    + (nowMs - getLastFixTime()) + " ms old)");
            return;
        }
        long time  = gpsTimeMs > 0 ? gpsTimeMs : System.currentTimeMillis();
        long nanos = SystemClock.elapsedRealtimeNanos();
        pushToProvider(LocationManager.GPS_PROVIDER,     time, nanos);
        pushToProvider(LocationManager.NETWORK_PROVIDER, time, nanos);
    }

    protected void pushToProvider(String provider, long time, long nanos) {
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
            Log.w(getTag(), "push " + provider + ": " + e.getMessage());
        }
    }

    // -- Broadcast helpers ----------------------------------------------------

    protected void broadcastConn(String msg) {
        synchronized (getStateLock()) { setLastConn(msg); }
        updateNotification(msg);
        sendStatusBroadcast(new Intent(getActionStatus()).putExtra("conn", msg));
        Log.d(getTag(), msg);
    }

    protected void broadcastSerial() {
        long now = System.currentTimeMillis();
        if (now - lastSerialBroadcast < 200) return;
        lastSerialBroadcast = now;
        sendStatusBroadcast(new Intent(getActionStatus())
                .putExtra("serial",            getLastSerialLog())
                .putExtra("bytes",             getTotalBytes())
                .putExtra("sents",             getTotalSents())
                .putExtra("satsInView",        getLastSatsInView())
                .putExtra("satsUsed",          getLastSatsUsed())
                .putExtra("satellites",        getLastSatellites())
                .putExtra("constellationJson", getLastConstellationJson())
                .putExtra("ntripStatus",       getLastNtripStatus()));
    }

    // -- Serial log -----------------------------------------------------------

    // [Impr-9] Incremental StringBuilder — rebuild only when a line is evicted
    protected void appendSerialLine(String line) {
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
        setLastSerialLog(serialLogBuilder.toString());
    }

    protected void sendStatusBroadcast(Intent intent) {
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    // -- Fix label ------------------------------------------------------------

    protected static String fixLabel(int q) {
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
}
