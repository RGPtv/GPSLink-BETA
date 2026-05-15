package com.gpslink;

import android.util.Log;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class NmeaParser {

    private static final String TAG = "NmeaParser";

    public static class GpsData {
        public double  latitude, longitude, altitude;
        public float   accuracy, speed, bearing;
        public long    gpsTimeMs;
        public int     satellites;
        public int     fixQuality;
        public float   hdop;
        // [Dead-2] pdop and vdop removed — never read outside parser
        public int     fixMode;   // GSA: 1=no fix, 2=2D, 3=3D
        public float   diffAge = -1.0f; // Age of diff data
        public String  refStationId = "";
        public boolean valid;
        public String  type;      // "GGA", "RMC", "GSA", "VTG"
    }

    public static class SatInfo {
        public String constellation;
        public int prn, elevation, azimuth, snr;

        @Override
        public String toString() {
            return constellation + " PRN:" + prn
                    + " El:" + elevation + "\u00B0 Az:" + azimuth + "\u00B0 SNR:" + snr;
        }
    }

    // -- Checksum --------------------------------------------------------------

    public static boolean verifyChecksum(String sentence) {
        if (sentence == null) return false;
        int star = sentence.indexOf('*');
        if (star < 1 || star + 3 > sentence.length()) return false;
        int declared;
        try {
            declared = Integer.parseInt(sentence.substring(star + 1, star + 3), 16);
        } catch (NumberFormatException e) {
            return false;
        }
        int computed = 0;
        for (int i = 1; i < star; i++) computed ^= sentence.charAt(i);
        return computed == declared;
    }

    // -- Sentence dispatcher ---------------------------------------------------

    public static GpsData parse(String sentence) {
        if (sentence == null || !sentence.startsWith("$")) return null;
        int star = sentence.indexOf('*');
        String body = (star > 0) ? sentence.substring(0, star) : sentence;
        try {
            if (isType(body, "GGA")) return parseGGA(body);
            if (isType(body, "RMC")) return parseRMC(body);
            if (isType(body, "GSA")) return parseGSA(body);
            if (isType(body, "VTG")) return parseVTG(body);
            if (isType(body, "GST")) return parseGST(body);
        } catch (Exception e) {
            Log.w(TAG, "Parse error in '" + sentence + "': " + e.getMessage());
        }
        return null;
    }

    private static boolean isType(String body, String type) {
        if (body == null) return false;
        int typeStart = body.startsWith("$P") ? 2 : 3;
        return body.length() >= typeStart + type.length()
                && body.regionMatches(true, typeStart, type, 0, type.length());
    }

    // -- GGA -------------------------------------------------------------------

    private static GpsData parseGGA(String body) {
        String[] p = body.split(",", -1);
        if (p.length < 10) return null;
        if (p[6].isEmpty() || "0".equals(p[6].trim())) return null;

        GpsData d = new GpsData();
        d.type       = "GGA";
        d.latitude   = parseLatLon(p[2], p[3]);
        d.longitude  = parseLatLon(p[4], p[5]);

        // FIX: log a warning when altitude unit is not metres so non-standard
        // receivers don't silently produce wrong altitudes.
        boolean metresOrAbsent = p.length < 11 || p[10].isEmpty()
                || "M".equalsIgnoreCase(p[10].trim());
        if (!metresOrAbsent) {
            Log.w(TAG, "GGA: unexpected altitude unit '" + p[10].trim()
                    + "' — altitude may be wrong");
        }
        double ellipsoidalAlt = (!p[9].isEmpty() && metresOrAbsent)
                ? Double.parseDouble(p[9].trim()) : 0;

        // NMEA 4.11 §4.2.1: field [11] is geoid separation (N), field [12] is
        // its unit (must be 'M').  orthometric = ellipsoidal + geoidSep.
        // Without this correction altitude can be off by 0–100 m.
        double geoidSep = 0;
        if (p.length > 11 && !p[11].isEmpty()) {
            boolean geoidMetres = p.length < 13 || p[12].isEmpty()
                    || "M".equalsIgnoreCase(p[12].trim());
            if (geoidMetres) {
                geoidSep = parseDoubleSafe(p[11]);
            } else {
                Log.w(TAG, "GGA: unexpected geoid unit '" + p[12].trim() + "'");
            }
        }
        d.altitude = ellipsoidalAlt + geoidSep;

        d.fixQuality = parseIntSafe(p[6]);
        d.satellites = parseIntSafe(p[7]);
        d.hdop       = parseFloatSafe(p[8]);

        // FIX: use a more realistic accuracy multiplier. DGPS (quality=2) is
        // more accurate than standard GPS, so use a smaller base error.
        // The 2.5× factor was too optimistic and could cause Android to prefer
        // a stale fix over a fresher one with slightly worse HDOP.
        float baseError = (d.fixQuality == 2) ? 2.5f : 4.0f;
        d.accuracy   = Math.max(1.0f, d.hdop * baseError);

        // Parse time-of-day from field 1 (useful when RMC is absent on BT GPS)
        d.gpsTimeMs  = parseTimeOfDay(p[1]);

        if (p.length > 13) d.diffAge = parseFloatSafe(p[13]);
        if (p.length > 14) d.refStationId = p[14].trim();

        d.valid      = true;
        return d;
    }

    // -- RMC -------------------------------------------------------------------

    private static GpsData parseRMC(String body) {
        String[] p = body.split(",", -1);
        if (p.length < 9) return null;

        GpsData d = new GpsData();
        d.type      = "RMC";

        if (!"A".equals(p[2].trim()) || (p.length > 12 && "N".equalsIgnoreCase(p[12].trim()))) {
            d.fixMode = 1; // void fix
            return d;
        }

        d.latitude  = parseLatLon(p[3], p[4]);
        d.longitude = parseLatLon(p[5], p[6]);
        d.speed     = p[7].isEmpty() ? 0f : parseFloatSafe(p[7]) * 0.514444f;
        d.bearing   = p[8].isEmpty() ? 0f : parseFloatSafe(p[8]);
        d.gpsTimeMs = parseGpsTime(p[1], p.length > 9 ? p[9] : "");
        d.valid     = true;
        return d;
    }

    // -- GSA -------------------------------------------------------------------
    // $GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1*39
    // Field 0: header ($GPGSA etc.)
    // Field 1: mode (A=auto, M=manual)
    // Field 2: fix type (1=no fix, 2=2D, 3=3D)
    // Fields 3-14: PRNs of satellites used
    // Field 15: PDOP, Field 16: HDOP, Field 17: VDOP

    private static GpsData parseGSA(String body) {
        String[] p = body.split(",", -1);

        // FIX: standard GSA has 18 fields (indices 0–17). Requiring < 17 was
        // wrong — it allowed sentences without the VDOP field, which then caused
        // p[17-1] = p[16] (HDOP) to be read as VDOP. We now require all 3 DOP
        // fields to be present.
        if (p.length < 18) return null;

        int fixMode = parseIntSafe(p[2]);
        if (fixMode < 1) return null;

        GpsData d = new GpsData();
        d.type    = "GSA";
        d.fixMode = fixMode;
        // [Dead-2] pdop/vdop assignments removed — fields no longer exist
        d.hdop    = parseFloatSafe(p[16]);

        // FIX: use the same realistic multiplier as GGA so both sources agree.
        d.accuracy = Math.max(1.0f, d.hdop * 4.0f);

        // Count satellites used (fields 3-14)
        int usedCount = 0;
        for (int i = 3; i <= 14 && i < p.length; i++) {
            if (!p[i].trim().isEmpty()) usedCount++;
        }
        d.satellites = usedCount;
        d.valid = true;
        return d;
    }

    // -- VTG -------------------------------------------------------------------
    // $GPVTG,054.7,T,034.4,M,005.5,N,010.2,K*48
    // Field 1: course true, Field 3: course magnetic
    // Field 5: speed knots, Field 7: speed km/h

    private static GpsData parseVTG(String body) {
        String[] p = body.split(",", -1);
        if (p.length < 8) return null;

        // NMEA 4.11 adds field [9] mode indicator: A/D/E/M/S/N.
        // 'N' = data not valid — reject the sentence.
        if (p.length > 9 && "N".equalsIgnoreCase(p[9].trim())) return null;

        GpsData d = new GpsData();
        d.type    = "VTG";
        d.bearing = p[1].isEmpty() ? 0f : parseFloatSafe(p[1]);

        // Prefer km/h field (7) → convert to m/s; fallback to knots field (5)
        if (!p[7].isEmpty()) {
            d.speed = parseFloatSafe(p[7]) / 3.6f; // km/h → m/s
        } else if (!p[5].isEmpty()) {
            d.speed = parseFloatSafe(p[5]) * 0.514444f; // knots → m/s
        }

        d.valid = true;
        return d;
    }

    // -- GST -------------------------------------------------------------------
    // $GPGST,024603.00,3.2,6.6,4.7,47.3,5.8,5.6,22.0*58
    // Field 6: Std dev of latitude error
    // Field 7: Std dev of longitude error

    private static GpsData parseGST(String body) {
        String[] p = body.split(",", -1);
        if (p.length < 9) return null;

        GpsData d = new GpsData();
        d.type = "GST";
        
        // [PossIss-5] Use Float.NaN sentinel instead of 99.0f for parse failures
        float latErr = parseFloatSafe(p[6]);
        float lonErr = parseFloatSafe(p[7]);
        
        if (!Float.isNaN(latErr) && !Float.isNaN(lonErr)) {
            d.accuracy = (float) Math.sqrt(latErr * latErr + lonErr * lonErr);
            d.valid = true;
            return d;
        } else {
            float semiMajor = parseFloatSafe(p[3]);
            if (!Float.isNaN(semiMajor)) {
                d.accuracy = semiMajor;
                d.valid = true;
                return d;
            }
        }
        return null;
    }

    // -- GSV -------------------------------------------------------------------

    public static List<SatInfo> parseGSV(String body) {
        List<SatInfo> result = new ArrayList<>();
        int star = body.indexOf('*');
        if (star > 0) body = body.substring(0, star);

        String[] p = body.split(",", -1);
        if (p.length < 4) return result;

        String constellation = getConstellation(p[0]);
        int idx = 4;
        while (idx < p.length) {
            String prnStr = p[idx].trim();
            // FIX: skip blank PRN slots instead of breaking — a receiver may
            // leave a slot empty in the middle of a GSV sentence.
            if (prnStr.isEmpty()) { idx += 4; continue; }
            try {
                SatInfo s       = new SatInfo();
                s.prn           = Integer.parseInt(prnStr);
                // SBAS PRN reclassification only for GPS talker ($GP)
                s.constellation = (isGpsTalker(p[0]) && isSbasPrn(s.prn))
                        ? "SBAS" : constellation;
                s.elevation = (idx + 1 < p.length) ? clamp(parseIntSafe(p[idx + 1]), 0, 90)  : 0;
                s.azimuth   = (idx + 2 < p.length) ? clamp(parseIntSafe(p[idx + 2]), 0, 360) : 0;
                s.snr       = (idx + 3 < p.length) ? parseSnr(p[idx + 3]) : 0;
                result.add(s);
            } catch (NumberFormatException e) {
                Log.w(TAG, "GSV field parse error at idx=" + idx);
            }
            idx += 4;
        }
        return result;
    }

    // -- Helpers ---------------------------------------------------------------

    public static String constellationFromTalker(String talker) {
        return getConstellation(talker);
    }

    private static String getConstellation(String talker) {
        if (talker == null) return "Unknown";
        if (talker.contains("GN")) return "GNSS";
        if (talker.contains("GP")) return "GPS";
        if (talker.contains("GL")) return "GLONASS";
        if (talker.contains("GA")) return "Galileo";
        if (talker.contains("BD") || talker.contains("GB")) return "BeiDou";
        if (talker.contains("QZ")) return "QZSS";
        if (talker.contains("GI")) return "NavIC";
        if (talker.contains("GS")) return "SBAS";
        return "Unknown";
    }

    /** True only for $GP talker — SBAS PRN reclassification is GPS-context only. */
    private static boolean isGpsTalker(String header) {
        if (header == null || header.length() < 3) return false;
        return header.charAt(1) == 'G' && header.charAt(2) == 'P';
    }

    /**
     * Returns true if the given PRN falls in the SBAS satellite range.
     * Only valid when called in GPS ($GP) talker context.
     */
    public static boolean isSbasPrn(int prn) {
        return (prn >= 33 && prn <= 64) || (prn >= 120 && prn <= 158);
    }

    /**
     * Parse SNR field which may be integer ("38") or float ("38.5").
     * Returns 0 for empty/invalid.
     */
    private static int parseSnr(String raw) {
        if (raw == null) return 0;
        raw = raw.trim();
        if (raw.isEmpty()) return 0;
        try {
            if (raw.contains(".")) {
                return Math.round(Float.parseFloat(raw));
            }
            raw = raw.replaceAll("[^0-9]", "");
            return raw.isEmpty() ? 0 : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * [PossIss-5] Returns Float.NaN for null, empty, or unparseable input
     * instead of a magic sentinel value.
     */
    private static float parseFloatSafe(String s) {
        if (s == null || s.trim().isEmpty()) return Float.NaN;
        try { return Float.parseFloat(s.trim()); }
        catch (NumberFormatException e) { return Float.NaN; }
    }

    /**
     * [Impr-2] Parse time-of-day from GGA field 1 (HHMMSS.sss) using java.time.
     * Returns 0 if unparseable.
     */
    private static long parseTimeOfDay(String t) {
        if (t == null || t.length() < 6) return 0;
        try {
            int hh = Integer.parseInt(t.substring(0, 2));
            int mm = Integer.parseInt(t.substring(2, 4));
            int ss = Integer.parseInt(t.substring(4, 6));
            int nanos = 0;
            if (t.length() > 7 && t.charAt(6) == '.') {
                String frac = t.substring(7);
                int ms = (int) Math.round(Integer.parseInt(frac)
                        * (1000.0 / Math.pow(10, frac.length())));
                nanos = ms * 1_000_000;
            }
            LocalTime lt = LocalTime.of(hh, mm, ss, nanos);
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            return ZonedDateTime.of(today, lt, ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * [Impr-2] Parse full GPS timestamp from RMC using java.time.
     */
    private static long parseGpsTime(String t, String date) {
        if (t == null || t.length() < 6 || date == null || date.length() < 6) return 0;
        try {
            int hh = Integer.parseInt(t.substring(0, 2));
            int mm = Integer.parseInt(t.substring(2, 4));
            int ss = Integer.parseInt(t.substring(4, 6));
            int nanos = 0;
            if (t.length() > 7 && t.charAt(6) == '.') {
                String frac = t.substring(7);
                int ms = (int) Math.round(Integer.parseInt(frac)
                        * (1000.0 / Math.pow(10, frac.length())));
                nanos = ms * 1_000_000;
            }
            int dd = Integer.parseInt(date.substring(0, 2));
            int mo = Integer.parseInt(date.substring(2, 4));
            int yr = Integer.parseInt(date.substring(4, 6));
            yr += (yr >= 80) ? 1900 : 2000;

            LocalDate ld = LocalDate.of(yr, mo, dd);
            LocalTime lt = LocalTime.of(hh, mm, ss, nanos);
            return ZonedDateTime.of(ld, lt, ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception e) {
            Log.w(TAG, "parseGpsTime failed for t='" + t + "' date='" + date + "'");
            return 0;
        }
    }

    private static double parseLatLon(String value, String dir) {
        if (value == null || value.isEmpty()) return 0.0;
        try {
            double raw = Double.parseDouble(value.trim());
            int    deg = (int) (raw / 100);
            double min = raw - (deg * 100.0);
            double res = deg + min / 60.0;
            if (dir != null && ("S".equals(dir.trim()) || "W".equals(dir.trim()))) res = -res;
            return res;
        } catch (NumberFormatException e) {
            Log.w(TAG, "parseLatLon failed for value='" + value + "'");
            return 0.0;
        }
    }

    private static int parseIntSafe(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static double parseDoubleSafe(String s) {
        if (s == null || s.trim().isEmpty()) return 0.0;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
