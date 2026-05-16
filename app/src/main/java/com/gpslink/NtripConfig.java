package com.gpslink;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * [Impr-4] Value object for NTRIP configuration.
 * Centralises SharedPreferences key names so both services
 * and the settings dialog stay in sync.
 */
public final class NtripConfig {

    public static final String ACTION_NTRIP_CONFIG_CHANGED = "com.gpslink.ACTION_NTRIP_CONFIG_CHANGED";

    public final boolean enabled;
    public final String host;
    public final int port;
    public final String mountpoint;
    public final String username;
    public final String password;
    public final boolean useTls;

    private NtripConfig(boolean enabled, String host, int port, String mountpoint,
                        String username, String password, boolean useTls) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.mountpoint = mountpoint;
        this.username = username;
        this.password = password;
        this.useTls = useTls;
    }

    /** Load NTRIP configuration from the app's SharedPreferences. */
    private static NtripConfig load(SharedPreferences prefs) {
        boolean enabled = prefs.getBoolean("ntripEnabled", false);
        String host = prefs.getString("ntripHost", "");
        int port = 2101;
        try { port = Integer.parseInt(prefs.getString("ntripPort", "2101")); }
        catch (Exception ignored) {}
        String mountpoint = prefs.getString("ntripMountpoint", "");
        String user = prefs.getString("ntripUser", "");
        String pass = prefs.getString("ntripPass", "");
        boolean useTls = prefs.getBoolean("ntripUseTls", false);
        return new NtripConfig(enabled, host, port, mountpoint, user, pass, useTls);
    }

    /** Load from the standard app preferences name. */
    public static NtripConfig load(Context context) {
        return load(context.getSharedPreferences("GPSLinkPrefs", Context.MODE_PRIVATE));
    }

    /** Returns true if the configuration has enough data to attempt a connection. */
    public boolean isValid() {
        return enabled && !host.isEmpty() && !mountpoint.isEmpty();
    }
}
