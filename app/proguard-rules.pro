# GPSLink ProGuard Rules

# Keep USB serial library public API
-keep class com.hoho.android.usbserial.** { *; }

# Keep osmdroid public API (needed for tile source reflection)
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Keep our service classes (started via Intent)
-keep class com.gpslink.UsbSerialService { *; }
-keep class com.gpslink.BluetoothGpsService { *; }
-keep class com.gpslink.MainActivity { *; }

# Keep NtripClient inner interfaces (used by services via anonymous classes)
-keep class com.gpslink.NtripClient$NtripListener { *; }
-keep class com.gpslink.NtripClient$PositionProvider { *; }

# Keep NtripConfig (loaded reflectively via SharedPreferences)
-keep class com.gpslink.NtripConfig { *; }

# Keep custom views (inflated from XML)
-keep class com.gpslink.CompassView { *; }
-keep class com.gpslink.MiniMapView { *; }
-keep class com.gpslink.SignalBarsView { *; }

# Keep NmeaParser data classes (fields accessed by name)
-keep class com.gpslink.NmeaParser$GpsData { *; }
-keep class com.gpslink.NmeaParser$SatInfo { *; }

# AndroidX
-dontwarn androidx.**
-keep class androidx.** { *; }

# Suppress warnings for javax.net.ssl used in NtripClient TLS
-dontwarn javax.net.ssl.**
