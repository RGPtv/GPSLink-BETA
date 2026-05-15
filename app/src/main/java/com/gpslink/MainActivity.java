package com.gpslink;

import android.Manifest;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    // -- Colour tokens ---------------------------------------------------------
    private static final int COLOR_HZ_ACTIVE = 0xFF2563EB;
    private static final int COLOR_HZ_INACTIVE = 0xFF0A1020;
    private static final int TEXT_HZ_ACTIVE = Color.WHITE;
    private static final int TEXT_HZ_INACTIVE = 0xFF4B5563;
    private static final int DOT_COLOR_ACTIVE = 0xFF22C55E;
    private static final int DOT_COLOR_IDLE = 0xFF3F3F46;
    private static final String EM_DASH = "\u2014";
    private static final int REQUEST_LOCATION_PERMISSION = 1;
    private static final int REQUEST_BT_PERMISSION = 2;
    private static final int REQUEST_INITIAL_PERMISSIONS = 3;

    // -- Bluetooth device selection
    private String selectedBtAddress = "";
    private String selectedBtName = "";

    // -- Views -----------------------------------------------------------------
    private TextView tvConnection, tvSignal, tvSerial, tvSerialStats, tvLastFix;
    private TextView tvSatsInView, tvSatsUsed, tvHeading, tvHeadingDir;
    private TextView tvLatitude, tvLatDir, tvLongitude, tvLonDir, tvAltitude;
    private TextView tvSpeed, tvCourse, tvHdop, tvFixType;
    private TextView tvHdrSub, tvLocation;
    private TextView tvNtripStatus;
    private View statusDot;
    private CompassView compassView;
    private SignalBarsView signalBarsView;
    private MiniMapView miniMapView;
    private Button btnStart, btnStop;

    // -- Map popup state -------------------------------------------------------
    private Dialog mapPopupDialog;
    private MiniMapView popupMapView;

    // -- Settings dialog state -------------------------------------------------
    private Dialog settingsDialog;
    private String connType = "usb"; // "usb" | "bt"
    private int selectedHz = 1;
    private int selectedBaud = 9600;
    private boolean isReceiverRegistered = false;
    private boolean isSerialExpanded = false;
    private boolean isNtripEnabled = false;
    private String ntripHost = "";
    private String ntripPort = "2101";
    private String ntripMountpoint = "";
    private String ntripUser = "";
    private String ntripPass = "";

    // -- Persistence -----------------------------------------------------------
    private static final String PREFS_NAME = "GPSLinkPrefs";
    private static final String KEY_CONN_TYPE = "connType";
    private static final String KEY_SELECTED_HZ = "selectedHz";
    private static final String KEY_SELECTED_BAUD = "selectedBaud";
    private static final String KEY_BT_ADDR = "selectedBtAddr";
    private static final String KEY_BT_NAME = "selectedBtName";
    private static final String KEY_SERIAL_EXPANDED = "serialExpanded";
    private static final String KEY_LAST_LAT = "lastLat";
    private static final String KEY_LAST_LON = "lastLon";
    private static final String KEY_NTRIP_ENABLED = "ntripEnabled";
    private static final String KEY_NTRIP_HOST = "ntripHost";
    private static final String KEY_NTRIP_PORT = "ntripPort";
    private static final String KEY_NTRIP_MOUNTPOINT = "ntripMountpoint";
    private static final String KEY_NTRIP_USER = "ntripUser";
    private static final String KEY_NTRIP_PASS = "ntripPass";

    private void saveSettings() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_CONN_TYPE, connType)
                .putInt(KEY_SELECTED_HZ, selectedHz)
                .putInt(KEY_SELECTED_BAUD, selectedBaud)
                .putString(KEY_BT_ADDR, selectedBtAddress)
                .putString(KEY_BT_NAME, selectedBtName)
                .putBoolean(KEY_SERIAL_EXPANDED, isSerialExpanded)
                .putLong(KEY_LAST_LAT, Double.doubleToRawLongBits(lastMapLat))
                .putLong(KEY_LAST_LON, Double.doubleToRawLongBits(lastMapLon))
                .putBoolean(KEY_NTRIP_ENABLED, isNtripEnabled)
                .putString(KEY_NTRIP_HOST, ntripHost)
                .putString(KEY_NTRIP_PORT, ntripPort)
                .putString(KEY_NTRIP_MOUNTPOINT, ntripMountpoint)
                .putString(KEY_NTRIP_USER, ntripUser)
                .putString(KEY_NTRIP_PASS, ntripPass)
                .apply();
    }

    private void loadSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        connType = prefs.getString(KEY_CONN_TYPE, "usb");
        selectedHz = prefs.getInt(KEY_SELECTED_HZ, 1);
        selectedBaud = prefs.getInt(KEY_SELECTED_BAUD, 9600);
        selectedBtAddress = prefs.getString(KEY_BT_ADDR, "");
        selectedBtName = prefs.getString(KEY_BT_NAME, "");
        isSerialExpanded = prefs.getBoolean(KEY_SERIAL_EXPANDED, false);
        // Read as long bits to preserve full double precision
        lastMapLat = Double.longBitsToDouble(prefs.getLong(KEY_LAST_LAT, 0L));
        lastMapLon = Double.longBitsToDouble(prefs.getLong(KEY_LAST_LON, 0L));
        isNtripEnabled = prefs.getBoolean(KEY_NTRIP_ENABLED, false);
        ntripHost = prefs.getString(KEY_NTRIP_HOST, "");
        ntripPort = prefs.getString(KEY_NTRIP_PORT, "2101");
        ntripMountpoint = prefs.getString(KEY_NTRIP_MOUNTPOINT, "");
        ntripUser = prefs.getString(KEY_NTRIP_USER, "");
        ntripPass = prefs.getString(KEY_NTRIP_PASS, "");
    }

    // -- Broadcast receiver ----------------------------------------------------
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null)
                return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed())
                    return;
                String conn = intent.getStringExtra("conn");
                String signal = intent.getStringExtra("signal");
                String pos = intent.getStringExtra("position");
                String mov = intent.getStringExtra("movement");
                String serial = intent.getStringExtra("serial");
                String satsView = intent.getStringExtra("satsInView");
                String satsUsed = intent.getStringExtra("satsUsed");
                String heading = intent.getStringExtra("heading");
                String constJson = intent.getStringExtra("constellationJson");
                String ntripStatus = intent.getStringExtra("ntripStatus");
                long bytes = intent.getLongExtra("bytes", -1);
                int sents = intent.getIntExtra("sents", -1);
                long fixTime = intent.getLongExtra("fixtime", 0);
                // Read raw double coords for map (bypasses lossy string→float round-trip)
                double rawLat = intent.getDoubleExtra("lat", Double.NaN);
                double rawLon = intent.getDoubleExtra("lon", Double.NaN);

                if (conn != null)
                    updateConnection(conn);
                if (signal != null)
                    tvSignal.setText(signal);
                if (pos != null)
                    updatePosition(pos, rawLat, rawLon);
                if (mov != null)
                    updateMovement(mov);
                if (serial != null)
                    tvSerial.setText(serial);
                if (satsView != null)
                    tvSatsInView.setText(satsView);
                if (satsUsed != null)
                    tvSatsUsed.setText(satsUsed);
                if (heading != null)
                    updateHeading(heading);
                if (constJson != null && !constJson.isEmpty())
                    updateSignalBars(constJson);
                if (bytes >= 0 && sents >= 0)
                    tvSerialStats.setText(sents + " sentences \u00B7 " + fmtBytes(bytes));
                if (fixTime > 0)
                    tvLastFix.setText("Last fix: " + fmtTime(fixTime));
                tvNtripStatus.setVisibility(View.VISIBLE);
                if (!isNtripEnabled) {
                    tvNtripStatus.setText("NTRIP Disabled");
                    tvNtripStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text_dim));
                } else if (ntripStatus != null && !ntripStatus.isEmpty()) {
                    tvNtripStatus.setText(ntripStatus);
                    tvNtripStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.accent_emerald));
                } else {
                    tvNtripStatus.setText("NTRIP Enabled (Waiting)");
                    tvNtripStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.accent_amber));
                }
            });
        }
    };

    // -- Lifecycle -------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loadSettings();

        // Bind header views
        tvHdrSub = findViewById(R.id.tvHdrSub);
        tvLocation = findViewById(R.id.tvLocation);
        statusDot = findViewById(R.id.statusDot);
        tvConnection = findViewById(R.id.tvConnection);
        tvLastFix = findViewById(R.id.tvLastFix);
        tvNtripStatus = findViewById(R.id.tvNtripStatus);

        // Bind data views
        tvSignal = findViewById(R.id.tvSignal);
        tvSerial = findViewById(R.id.tvSerial);
        tvSerialStats = findViewById(R.id.tvSerialStats);
        tvSatsInView = findViewById(R.id.tvSatsInView);
        tvSatsUsed = findViewById(R.id.tvSatsUsed);
        tvHeading = findViewById(R.id.tvHeading);
        tvHeadingDir = findViewById(R.id.tvHeadingDir);
        tvLatitude = findViewById(R.id.tvLatitude);
        tvLatDir = findViewById(R.id.tvLatDir);
        tvLongitude = findViewById(R.id.tvLongitude);
        tvLonDir = findViewById(R.id.tvLonDir);
        tvAltitude = findViewById(R.id.tvAltitude);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvCourse = findViewById(R.id.tvCourse);
        tvHdop = findViewById(R.id.tvHdop);
        tvFixType = findViewById(R.id.tvFixType);
        compassView = findViewById(R.id.compassView);
        signalBarsView = findViewById(R.id.signalBarsView);
        miniMapView = findViewById(R.id.miniMapView);

        btnStart = findViewById(R.id.btnAction); // Using single action button

        // Serial log toggle
        View headerSerial = findViewById(R.id.headerSerial);
        ImageView ivSerialChevron = findViewById(R.id.ivSerialChevron);
        headerSerial.setOnClickListener(v -> {
            isSerialExpanded = !isSerialExpanded;
            tvSerial.setVisibility(isSerialExpanded ? View.VISIBLE : View.GONE);
            ivSerialChevron.setRotation(isSerialExpanded ? 180 : 0);
            saveSettings();
        });
        tvSerial.setVisibility(isSerialExpanded ? View.VISIBLE : View.GONE);
        ivSerialChevron.setRotation(isSerialExpanded ? 180 : 0);

        // Settings button -> open dialog
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsDialog());

        // Mini map click -> open popup
        miniMapView.setInteractivity(true);
        miniMapView.setOnClickListener(v -> showMapPopup());

        // Start / Stop logic
        btnStart.setOnClickListener(v -> {
            if (isAnyServiceRunning()) {
                stopGpsService();
            } else {
                startGpsService();
            }
            // Immediate UI feedback
            updateActionButtonUi();
        });

        // Mock Location warning
        checkMockLocationStatus();
        findViewById(R.id.btnFixMock).setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            }
        });

        // Restore if service is already running
        if (isAnyServiceRunning()) {
            selectedHz = UsbSerialService.currentHz; // P1-5: only override saved Hz when service is live
            restoreState();
        } else {
            resetCards();
        }

        updateActionButtonUi();
        updateHdrSub();

        if (lastMapLat != 0 && lastMapLon != 0) {
            miniMapView.setPosition(lastMapLat, lastMapLon);
        }

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean("firstRun", true)) {
            prefs.edit().putBoolean("firstRun", false).apply();
            requestInitialPermissions();
        }
    }

    private void checkMockLocationStatus() {
        View card = findViewById(R.id.cardMockWarning);
        if (card == null)
            return;

        // Step 1: developer options must be on — if not, mock location can never work.
        int devOptions = 0;
        try {
            devOptions = android.provider.Settings.Global.getInt(
                    getContentResolver(), android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
        } catch (Exception ignored) {
        }
        if (devOptions == 0) {
            card.setVisibility(View.VISIBLE);
            return;
        }

        // Step 2: actually probe whether we can add a test provider.
        // Use GPS_PROVIDER — fake provider names throw IllegalArgumentException on API
        // 31+
        // regardless of mock location permission, making them useless as a probe.
        boolean isMock = false;
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(
                Context.LOCATION_SERVICE);
        if (lm != null) {
            try {
                // removeTestProvider first in case a previous run left it registered.
                try {
                    lm.removeTestProvider(LocationManager.GPS_PROVIDER);
                } catch (Exception ignored) {
                }
                lm.addTestProvider(LocationManager.GPS_PROVIDER,
                        false, false, false, false, true, true, true,
                        android.location.Criteria.POWER_HIGH, android.location.Criteria.ACCURACY_FINE);
                lm.removeTestProvider(LocationManager.GPS_PROVIDER);
                isMock = true;
            } catch (SecurityException e) {
                isMock = false; // permission denied — this app is not the mock location app
            } catch (Exception e) {
                isMock = false; // unexpected failure — show warning to be safe
            }
        }
        card.setVisibility(isMock ? View.GONE : View.VISIBLE);
    }

    private void startGpsService() {
        if (!checkPermissions())
            return;
        if ("bt".equals(connType)) {
            if (selectedBtAddress.isEmpty()) {
                showSettingsDialog();
                return;
            }
            Intent svc = new Intent(this, BluetoothGpsService.class);
            svc.putExtra(BluetoothGpsService.EXTRA_BT_ADDRESS, selectedBtAddress);
            svc.putExtra(BluetoothGpsService.EXTRA_BT_NAME, selectedBtName);
            ContextCompat.startForegroundService(this, svc);
        } else {
            Intent svc = new Intent(this, UsbSerialService.class);
            svc.putExtra(UsbSerialService.EXTRA_HZ, selectedHz);
            svc.putExtra(UsbSerialService.EXTRA_BAUD, selectedBaud);
            ContextCompat.startForegroundService(this, svc);
        }
        updateActionButtonUi();
    }

    private void stopGpsService() {
        stopService(new Intent(this, UsbSerialService.class));
        stopService(new Intent(this, BluetoothGpsService.class));
        clearServiceState();
        resetCards();
        updateActionButtonUi();
    }

    private boolean isAnyServiceRunning() {
        synchronized (UsbSerialService.STATE_LOCK) {
            return UsbSerialService.isRunning || BluetoothGpsService.isRunning;
        }
    }

    private void updateActionButtonUi() {
        boolean running = isAnyServiceRunning();
        btnStart.setText(running ? R.string.btn_stop : R.string.btn_start);
        // Use setTint for the button background to keep the shape/ripple
        if (btnStart.getBackground() != null) {
            btnStart.getBackground().setTint(ContextCompat.getColor(this,
                    running ? R.color.accent_red : R.color.primary_variant));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (miniMapView != null)
            miniMapView.onResume();
        if (popupMapView != null)
            popupMapView.onResume();
        IntentFilter f = new IntentFilter(UsbSerialService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(statusReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(statusReceiver, f);
        isReceiverRegistered = true;
        checkMockLocationStatus(); // re-check every resume in case user just set mock location app
        if (isAnyServiceRunning()) {
            selectedHz = UsbSerialService.currentHz; // P1-5: only adopt when service is live
            restoreState();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (miniMapView != null)
            miniMapView.onPause();
        if (popupMapView != null)
            popupMapView.onPause();
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(statusReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            isReceiverRegistered = false;
        }
    }

    // FIX: dismiss open dialogs to prevent window token leaks / crashes
    // when the activity is destroyed while a dialog is showing.
    @Override
    protected void onDestroy() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.dismiss();
            settingsDialog = null;
        }
        if (mapPopupDialog != null && mapPopupDialog.isShowing()) {
            mapPopupDialog.dismiss();
            mapPopupDialog = null;
        }
        super.onDestroy();
    }

    // -- Settings Dialog -------------------------------------------------------

    private void showSettingsDialog() {
        if (isFinishing() || isDestroyed())
            return;
        settingsDialog = new Dialog(this);
        settingsDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        settingsDialog.setContentView(R.layout.dialog_settings);

        // Make dialog background transparent so card_bg corners show
        if (settingsDialog.getWindow() != null) {
            settingsDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            settingsDialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        // Bind dialog views
        LinearLayout optUsb = settingsDialog.findViewById(R.id.optUsb);
        LinearLayout optBt = settingsDialog.findViewById(R.id.optBt);
        LinearLayout hzSection = settingsDialog.findViewById(R.id.hzSection);
        LinearLayout btDevSection = settingsDialog.findViewById(R.id.btDeviceSection);
        LinearLayout btDevList = settingsDialog.findViewById(R.id.btDeviceList);
        TextView tvBtEmpty = settingsDialog.findViewById(R.id.tvBtEmpty);
        LinearLayout btSelectedRow = settingsDialog.findViewById(R.id.btSelectedRow);
        TextView tvBtSelected = settingsDialog.findViewById(R.id.tvBtSelected);
        TextView tvBtOpen = settingsDialog.findViewById(R.id.tvBtOpenSettings);
        TextView badgeUsb = settingsDialog.findViewById(R.id.badgeUsb);
        TextView badgeBt = settingsDialog.findViewById(R.id.badgeBt);
        Button dlgBtn1Hz = settingsDialog.findViewById(R.id.btn1Hz);
        Button dlgBtn5Hz = settingsDialog.findViewById(R.id.btn5Hz);
        TextView dlgHzNote = settingsDialog.findViewById(R.id.tvHzNote);
        Button btnClose = settingsDialog.findViewById(R.id.btnCloseSettings);
        androidx.appcompat.widget.SwitchCompat switchNtrip = settingsDialog.findViewById(R.id.switchNtrip);
        LinearLayout ntripFields = settingsDialog.findViewById(R.id.ntripFields);
        android.widget.EditText etNtripHost = settingsDialog.findViewById(R.id.etNtripHost);
        android.widget.EditText etNtripPort = settingsDialog.findViewById(R.id.etNtripPort);
        android.widget.EditText etNtripMountpoint = settingsDialog.findViewById(R.id.etNtripMountpoint);
        android.widget.EditText etNtripUser = settingsDialog.findViewById(R.id.etNtripUser);
        android.widget.EditText etNtripPass = settingsDialog.findViewById(R.id.etNtripPass);

        etNtripHost.setText(ntripHost);
        etNtripPort.setText(ntripPort);
        etNtripMountpoint.setText(ntripMountpoint);
        etNtripUser.setText(ntripUser);
        etNtripPass.setText(ntripPass);

        ntripFields.setVisibility(isNtripEnabled ? View.VISIBLE : View.GONE);

        // Populate paired BT devices
        populateBtDeviceList(btDevList, tvBtEmpty, btSelectedRow, tvBtSelected);

        // Restore selected device label
        if (!selectedBtName.isEmpty()) {
            btSelectedRow.setVisibility(View.VISIBLE);
            tvBtSelected.setText(selectedBtName);
        }

        tvBtOpen.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        });

        // Apply current state
        applyConnUi(optUsb, optBt, badgeUsb, badgeBt, hzSection, btDevSection, connType);
        applyHzUiDialog(dlgBtn1Hz, dlgBtn5Hz, dlgHzNote, selectedHz);

        // Baud rate buttons
        Button[] baudBtns = {
            settingsDialog.findViewById(R.id.btnBaud4800),
            settingsDialog.findViewById(R.id.btnBaud9600),
            settingsDialog.findViewById(R.id.btnBaud19200),
            settingsDialog.findViewById(R.id.btnBaud38400),
            settingsDialog.findViewById(R.id.btnBaud57600),
            settingsDialog.findViewById(R.id.btnBaud115200)
        };
        TextView tvBaudNote = settingsDialog.findViewById(R.id.tvBaudNote);
        applyBaudUiDialog(baudBtns, tvBaudNote, selectedBaud);

        for (Button bb : baudBtns) {
            bb.setOnClickListener(v -> {
                try {
                    selectedBaud = Integer.parseInt(bb.getText().toString().trim());
                } catch (NumberFormatException ignored) {
                    selectedBaud = 9600;
                }
                applyBaudUiDialog(baudBtns, tvBaudNote, selectedBaud);
                saveSettings();
            });
        }

        optUsb.setOnClickListener(v -> {
            if (!"usb".equals(connType))
                stopGpsService();
            connType = "usb";
            applyConnUi(optUsb, optBt, badgeUsb, badgeBt, hzSection, btDevSection, connType);
            updateHdrSub();
            saveSettings();
        });

        optBt.setOnClickListener(v -> {
            if (!"bt".equals(connType))
                stopGpsService();
            connType = "bt";
            applyConnUi(optUsb, optBt, badgeUsb, badgeBt, hzSection, btDevSection, connType);
            updateHdrSub();
            saveSettings();
        });

        dlgBtn1Hz.setOnClickListener(v -> {
            selectedHz = 1;
            applyHzUiDialog(dlgBtn1Hz, dlgBtn5Hz, dlgHzNote, selectedHz);
            dispatchHz(selectedHz);
            saveSettings();
        });

        dlgBtn5Hz.setOnClickListener(v -> {
            selectedHz = 5;
            applyHzUiDialog(dlgBtn1Hz, dlgBtn5Hz, dlgHzNote, selectedHz);
            dispatchHz(selectedHz);
            saveSettings();
        });

        switchNtrip.setChecked(isNtripEnabled);
        switchNtrip.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isNtripEnabled = isChecked;
            ntripFields.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            saveSettings();
        });

        btnClose.setOnClickListener(v -> {
            ntripHost = etNtripHost.getText().toString().trim();
            ntripPort = etNtripPort.getText().toString().trim();
            ntripMountpoint = etNtripMountpoint.getText().toString().trim();
            ntripUser = etNtripUser.getText().toString().trim();
            ntripPass = etNtripPass.getText().toString().trim();
            saveSettings();

            // [Issue-2] Broadcast NTRIP config change so running services can hot-reload
            Intent configChanged = new Intent("com.gpslink.ACTION_NTRIP_CONFIG_CHANGED");
            configChanged.setPackage(getPackageName());
            sendBroadcast(configChanged);

            settingsDialog.dismiss();
        });

        settingsDialog.show();
    }

    private void applyConnUi(LinearLayout optUsb, LinearLayout optBt,
            TextView badgeUsb, TextView badgeBt,
            LinearLayout hzSection, LinearLayout btDevSection, String type) {
        boolean isUsb = "usb".equals(type);
        optUsb.setBackgroundResource(isUsb ? R.drawable.conn_option_selected : R.drawable.conn_option_bg);
        optBt.setBackgroundResource(isUsb ? R.drawable.conn_option_bg : R.drawable.conn_option_selected);
        badgeUsb.setText(isUsb ? "ACTIVE" : "");
        badgeUsb.setBackgroundResource(isUsb ? R.drawable.badge_active_bg : 0);
        badgeBt.setText(isUsb ? "" : "ACTIVE");
        badgeBt.setBackgroundResource(isUsb ? 0 : R.drawable.badge_active_bg);
        hzSection.setVisibility(isUsb ? View.VISIBLE : View.GONE);
        btDevSection.setVisibility(isUsb ? View.GONE : View.VISIBLE);
    }

    private void populateBtDeviceList(LinearLayout list, TextView tvEmpty,
            LinearLayout selectedRow, TextView tvSelected) {
        list.removeAllViews();
        BluetoothAdapter adapter = null;
        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getSystemService(
                BLUETOOTH_SERVICE);
        if (bm != null)
            adapter = bm.getAdapter();
        // P2-4: BluetoothAdapter.getDefaultAdapter() deprecated API 31+
        if (adapter == null) {
            tvEmpty.setText("Bluetooth not available on this device.");
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        // On API 31+ we need BLUETOOTH_CONNECT permission to call getBondedDevices()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                tvEmpty.setText("Bluetooth permission required.\nGrant it in App Settings.");
                tvEmpty.setVisibility(View.VISIBLE);
                return;
            }
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        for (BluetoothDevice dev : bonded) {
            String devName = dev.getName();
            String devAddr = dev.getAddress();
            if (devName == null)
                devName = devAddr;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setBackground(ContextCompat.getDrawable(this, R.drawable.coord_cell_bg));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dp(4));
            row.setLayoutParams(rowLp);

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvName = new TextView(this);
            tvName.setText(devName);
            tvName.setTextSize(11f);
            tvName.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            tvName.setTextColor(0xFFE4EAF8);
            textCol.addView(tvName);

            TextView tvAddr = new TextView(this);
            tvAddr.setText(devAddr);
            tvAddr.setTextSize(9f);
            tvAddr.setTypeface(android.graphics.Typeface.MONOSPACE);
            tvAddr.setTextColor(0xFF4A6080);
            textCol.addView(tvAddr);
            row.addView(textCol);

            // Highlight if already selected
            boolean isSel = devAddr.equals(selectedBtAddress);
            TextView tvMark = new TextView(this);
            tvMark.setText(isSel ? "✓" : "");
            tvMark.setTextSize(14f);
            tvMark.setTextColor(0xFF10B981);
            row.addView(tvMark);

            final String fName = devName;
            final String fAddr = devAddr;
            row.setOnClickListener(v -> {
                selectedBtAddress = fAddr;
                selectedBtName = fName;
                selectedRow.setVisibility(View.VISIBLE);
                tvSelected.setText(fName);
                saveSettings();
                // Refresh marks
                populateBtDeviceList(list, tvEmpty, selectedRow, tvSelected);
            });
            list.addView(row);
        }
    }

    // [Impr-6] Inline density lookup — no need to cache DisplayMetrics.density
    private int dp(int px) {
        return Math.round(px * getResources().getDisplayMetrics().density);
    }

    private void applyHzUiDialog(Button btn1, Button btn5, TextView note, int hz) {
        boolean is5 = (hz == 5);
        btn1.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(is5 ? COLOR_HZ_INACTIVE : COLOR_HZ_ACTIVE));
        btn5.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(is5 ? COLOR_HZ_ACTIVE : COLOR_HZ_INACTIVE));
        btn1.setTextColor(is5 ? TEXT_HZ_INACTIVE : TEXT_HZ_ACTIVE);
        btn5.setTextColor(is5 ? TEXT_HZ_ACTIVE : TEXT_HZ_INACTIVE);
        note.setText(is5 ? "5 Hz \u00B7 5 updates / second (UBX-CFG-RATE)" : "1 Hz \u00B7 1 update per second");
    }

    private void applyBaudUiDialog(Button[] btns, TextView note, int baud) {
        int[] rates = {4800, 9600, 19200, 38400, 57600, 115200};
        for (int i = 0; i < btns.length && i < rates.length; i++) {
            boolean active = (rates[i] == baud);
            btns[i].setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(active ? COLOR_HZ_ACTIVE : COLOR_HZ_INACTIVE));
            btns[i].setTextColor(active ? TEXT_HZ_ACTIVE : TEXT_HZ_INACTIVE);
        }
        String baudNote;
        switch (baud) {
            case 4800:   baudNote = "4800 baud \u00B7 legacy NMEA standard"; break;
            case 19200:  baudNote = "19200 baud \u00B7 common for u-blox M8"; break;
            case 38400:  baudNote = "38400 baud \u00B7 common for u-blox M9/F9"; break;
            case 57600:  baudNote = "57600 baud \u00B7 high-rate receivers"; break;
            case 115200: baudNote = "115200 baud \u00B7 fastest standard rate"; break;
            default:     baudNote = "9600 baud \u00B7 default for u-blox receivers"; break;
        }
        if (UsbSerialService.isRunning && baud != UsbSerialService.currentBaud) {
            baudNote += " (restart to apply)";
        }
        note.setText(baudNote);
    }

    private void updateHdrSub() {
        tvHdrSub.setText("usb".equals(connType) ? R.string.conn_usb : R.string.conn_bt);
        // If not running, ensure the subtitle matches the idle state (e.g. show
        // remembered BT dev)
        if (!isAnyServiceRunning()) {
            resetCards();
        }
    }

    private void showMapPopup() {
        if (isFinishing() || isDestroyed())
            return;
        mapPopupDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        mapPopupDialog.setContentView(R.layout.dialog_map);

        if (mapPopupDialog.getWindow() != null) {
            mapPopupDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        popupMapView = mapPopupDialog.findViewById(R.id.popupMapView);
        ImageButton btnClose = mapPopupDialog.findViewById(R.id.btnCloseMap);
        Button btnRecenter = mapPopupDialog.findViewById(R.id.btnRecenter);

        if (popupMapView != null) {
            popupMapView.setRecenterButton(btnRecenter);
        }

        // Sync initial state
        if (miniMapView != null && popupMapView != null) {
            popupMapView.setTrack(miniMapView.getTrack());
            if (lastMapLat != 0 && lastMapLon != 0) {
                popupMapView.setPosition(lastMapLat, lastMapLon);
            }
        }

        btnClose.setOnClickListener(v -> mapPopupDialog.dismiss());
        mapPopupDialog.setOnDismissListener(dialog -> {
            popupMapView = null;
            mapPopupDialog = null;
        });

        mapPopupDialog.show();
        if (popupMapView != null)
            popupMapView.onResume();
    }

    private void dispatchHz(int hz) {
        if (UsbSerialService.isRunning) {
            Intent intent = new Intent(UsbSerialService.ACTION_SET_HZ);
            intent.setPackage(getPackageName());
            intent.putExtra(UsbSerialService.EXTRA_HZ, hz);
            sendBroadcast(intent);
        }
    }

    // -- UI update helpers -----------------------------------------------------

    private void updateConnection(String conn) {
        if (conn == null)
            return;
        String[] parts = conn.split(" \u00B7 ");
        if (parts.length >= 2) {
            tvConnection.setText(parts[0]);
            tvLocation.setText(parts[1]);
        } else {
            tvConnection.setText(conn);
            tvLocation.setText(EM_DASH);
        }

        String lc = conn.toLowerCase(Locale.ROOT);
        boolean error = lc.contains("no ublox") || lc.contains("error") || lc.contains("denied")
                || lc.contains("unavailable") || lc.contains("failed");
        boolean active = !lc.equals("idle") && !lc.contains("disconnected") && !lc.contains("not started") && !error;

        int dotColor = active ? 0xFF10B981 : (error ? 0xFFEF4444 : 0xFF3F3F46);
        int textColor = active ? 0xFF10B981 : (error ? 0xFFEF4444 : 0xFF4A6080);

        if (statusDot.getBackground() != null) {
            statusDot.getBackground().setTint(dotColor);
        }
        tvConnection.setTextColor(textColor);

        updateActionButtonUi();

        if (active || error) {
            if (statusDot.getAnimation() == null) {
                android.view.animation.AlphaAnimation blink = new android.view.animation.AlphaAnimation(1.0f, 0.4f);
                blink.setDuration(800);
                blink.setRepeatMode(android.view.animation.Animation.REVERSE);
                blink.setRepeatCount(android.view.animation.Animation.INFINITE);
                statusDot.startAnimation(blink);
            }
        } else {
            statusDot.clearAnimation();
        }
    }


    private void updatePosition(String pos, double rawLat, double rawLon) {
        if (pos == null || pos.isEmpty() || pos.equals(EM_DASH)) {
            setPositionEmpty();
            return;
        }
        String[] lines = pos.split("\n");
        Double signedLat = null;
        Double signedLon = null;

        if (lines.length >= 1) {
            String line = lines[0].trim();
            // Match pattern like "12.345678° N" or "Lat: 12.345678° N"
            // P1-4: strip +/- so the sign from dirPart isn't applied twice
            String numericPart = line.replaceAll("[^0-9.]", "");
            String dirPart = line.substring(line.length() - 1).toUpperCase(Locale.ROOT);
            try {
                if (!numericPart.isEmpty()) {
                    signedLat = Double.parseDouble(numericPart);
                    if ("S".equals(dirPart))
                        signedLat *= -1.0;
                    tvLatitude.setText(String.format(Locale.ROOT, "%.6f", Math.abs(signedLat)));
                    tvLatDir.setText(dirPart);
                }
            } catch (Exception ignored) {
            }
        }
        if (lines.length >= 2) {
            String line = lines[1].trim();
            // P1-4: strip +/- so the sign from dirPart isn't applied twice
            String numericPart = line.replaceAll("[^0-9.]", "");
            String dirPart = line.substring(line.length() - 1).toUpperCase(Locale.ROOT);
            try {
                if (!numericPart.isEmpty()) {
                    signedLon = Double.parseDouble(numericPart);
                    if ("W".equals(dirPart))
                        signedLon *= -1.0;
                    tvLongitude.setText(String.format(Locale.ROOT, "%.6f", Math.abs(signedLon)));
                    tvLonDir.setText(dirPart);
                }
            } catch (Exception ignored) {
            }
        }
        if (lines.length >= 3) {
            String line = lines[2].trim()
                    .replaceAll("(?i)alt:", "").replaceAll("(?i)\\bm\\b", "")
                    .replaceAll("(?i)\\bmsl\\b", "").trim();
            String[] parts = line.split("\\s+");
            tvAltitude.setText((parts.length >= 1 && !parts[0].isEmpty()) ? parts[0] : EM_DASH);
        }

        // Prefer raw double coords from broadcast extras (full NMEA precision ~1cm)
        // over the string-parsed values (which lose precision through formatting)
        double mapLat = !Double.isNaN(rawLat) ? rawLat : (signedLat != null ? signedLat : Double.NaN);
        double mapLon = !Double.isNaN(rawLon) ? rawLon : (signedLon != null ? signedLon : Double.NaN);

        if (miniMapView != null && !Double.isNaN(mapLat) && !Double.isNaN(mapLon)) {
            // Only update map if changed by > ~20cm (0.000002 deg)
            if (Math.abs(mapLat - lastMapLat) > 0.000002 || Math.abs(mapLon - lastMapLon) > 0.000002) {
                miniMapView.setPosition(mapLat, mapLon);
                lastMapLat = mapLat;
                lastMapLon = mapLon;
                // P1-1: only persist when moved >~11m (0.0001°) to avoid 5Hz disk writes
                if (Math.abs(mapLat - lastSavedLat) > 0.0001 || Math.abs(mapLon - lastSavedLon) > 0.0001) {
                    lastSavedLat = mapLat;
                    lastSavedLon = mapLon;
                    saveSettings();
                }
            }
        }
        if (popupMapView != null && !Double.isNaN(mapLat) && !Double.isNaN(mapLon)) {
            popupMapView.setPosition(mapLat, mapLon);
        }
    }

    private double lastMapLat = 0;
    private double lastMapLon = 0;
    private double lastSavedLat = 0; // P1-1: tracks last persisted position to debounce saves
    private double lastSavedLon = 0;

    private void setPositionEmpty() {
        tvLatitude.setText(EM_DASH);
        tvLatDir.setText("");
        tvLongitude.setText(EM_DASH);
        tvLonDir.setText("");
        tvAltitude.setText(EM_DASH);
        if (miniMapView != null)
            miniMapView.clearTrack();
        if (popupMapView != null)
            popupMapView.clearTrack();
    }

    private void updateMovement(String mov) {
        if (mov == null || mov.isEmpty() || mov.equals(EM_DASH)) {
            tvSpeed.setText(EM_DASH);
            tvCourse.setText(EM_DASH);
            tvHdop.setText(EM_DASH);
            tvFixType.setText(EM_DASH);
            tvFixType.setTextColor(0xFF4B5563);
            return;
        }
        for (String line : mov.split("\n")) {
            String lc = line.toLowerCase(Locale.ROOT);
            if (lc.contains("speed"))
                tvSpeed.setText(extractValue(line));
            if (lc.contains("course"))
                tvCourse.setText(extractValue(line));
            if (lc.contains("hdop"))
                tvHdop.setText(extractValue(line));
            if (lc.contains("fix")) {
                String fixVal = extractValue(line);
                tvFixType.setText(fixVal);
                String fv = fixVal.toLowerCase(Locale.ROOT);
                int fixColor = (fv.contains("no fix") || fv.contains("stale")) ? 0xFFEF4444
                        : (fv.contains("dgps") || fv.contains("rtk float") || fv.contains("dead reckoning"))
                                ? 0xFFF59E0B
                                : 0xFF22C55E;
                tvFixType.setTextColor(fixColor);
            }
        }
    }

    private String extractValue(String line) {
        int colon = line.indexOf(':');
        return colon >= 0 ? line.substring(colon + 1).trim() : line.trim();
    }

    private void updateHeading(String headingStr) {
        if (headingStr == null || headingStr.isEmpty()) {
            tvHeading.setText(EM_DASH);
            tvHeadingDir.setText(EM_DASH);
            return;
        }
        try {
            String numStr = headingStr.replaceAll("[^\\d.]", "");
            if (numStr.isEmpty()) {
                tvHeading.setText(EM_DASH);
                tvHeadingDir.setText(EM_DASH);
                return;
            }
            float heading = Float.parseFloat(numStr);
            heading = ((heading % 360) + 360) % 360;
            tvHeading.setText(String.format(Locale.ROOT, "%.0f\u00B0", heading));
            tvHeadingDir.setText(getCardinalDirection(heading));
            compassView.setHeading(heading);
        } catch (NumberFormatException e) {
            tvHeading.setText(EM_DASH);
            tvHeadingDir.setText(EM_DASH);
        }
    }

    private String getCardinalDirection(float heading) {
        String[] dirs = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW",
                "NNW" };
        int idx = (int) (Math.round(heading / 22.5) % 16);
        return dirs[idx];
    }

    private void updateSignalBars(String json) {
        if (signalBarsView == null || json == null || json.isEmpty())
            return;
        try {
            JSONArray arr = new JSONArray(json);
            List<SignalBarsView.ConstellationSignal> signals = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                signals.add(new SignalBarsView.ConstellationSignal(
                        obj.optString("label", "???"),
                        obj.optInt("avgSnr", 0),
                        obj.optInt("count", 0)));
            }
            signalBarsView.setSignals(signals);
        } catch (Exception ignored) {
        }
    }

    // -- State restore / reset -------------------------------------------------

    private void restoreState() {
        // [Bug-5] Read btRunning INSIDE STATE_LOCK to prevent stale snapshot
        synchronized (UsbSerialService.STATE_LOCK) {
            boolean btRunning = BluetoothGpsService.isRunning;
            String conn = btRunning ? BluetoothGpsService.lastConn : UsbSerialService.lastConn;
            String sig = btRunning ? BluetoothGpsService.lastSignal : UsbSerialService.lastSignal;
            String pos = btRunning ? BluetoothGpsService.lastPos : UsbSerialService.lastPos;
            String mov = btRunning ? BluetoothGpsService.lastMovement : UsbSerialService.lastMovement;
            String hdg = btRunning ? BluetoothGpsService.lastHeading : UsbSerialService.lastHeading;
            String cJson = btRunning ? BluetoothGpsService.lastConstellationJson
                    : UsbSerialService.lastConstellationJson;
            String slog = btRunning ? BluetoothGpsService.lastSerialLog : UsbSerialService.lastSerialLog;
            String sView = btRunning ? BluetoothGpsService.lastSatsInView : UsbSerialService.lastSatsInView;
            String sUsed = btRunning ? BluetoothGpsService.lastSatsUsed : UsbSerialService.lastSatsUsed;
            long bytes = btRunning ? BluetoothGpsService.totalBytes : UsbSerialService.totalBytes;
            int sents = btRunning ? BluetoothGpsService.totalSents : UsbSerialService.totalSents;
            long ft = btRunning ? BluetoothGpsService.lastFixTime : UsbSerialService.lastFixTime;
            updateConnection(conn);
            tvSignal.setText(sig);
            // [Dead-3] Call two-arg version directly instead of removed single-arg overload
            updatePosition(pos, Double.NaN, Double.NaN);
            updateMovement(mov);
            updateHeading(hdg);
            updateSignalBars(cJson);
            tvSerial.setText(slog);
            tvSatsInView.setText(sView);
            tvSatsUsed.setText(sUsed);
            if (bytes >= 0 && sents >= 0)
                tvSerialStats.setText(sents + " sentences \u00B7 " + fmtBytes(bytes));
            if (ft > 0)
                tvLastFix.setText("Last fix: " + fmtTime(ft));
        }
    }

    private void resetCards() {
        tvConnection.setText("Idle");
        if ("bt".equals(connType) && !selectedBtName.isEmpty()) {
            tvLocation.setText(selectedBtName);
        } else {
            tvLocation.setText(EM_DASH);
        }
        statusDot.clearAnimation();
        if (statusDot.getBackground() != null) {
            statusDot.getBackground().setTint(DOT_COLOR_IDLE);
        }
        tvConnection.setTextColor(0xFF4A6080);
        tvSignal.setText(EM_DASH);
        tvLatitude.setText(EM_DASH);
        tvLatDir.setText("");
        tvLongitude.setText(EM_DASH);
        tvLonDir.setText("");
        tvAltitude.setText(EM_DASH);
        tvSpeed.setText(EM_DASH);
        tvCourse.setText(EM_DASH);
        tvHdop.setText(EM_DASH);
        tvFixType.setText(EM_DASH);
        tvSerial.setText(EM_DASH);
        tvSatsInView.setText(EM_DASH);
        tvSatsUsed.setText(EM_DASH);
        tvHeading.setText(EM_DASH);
        tvHeadingDir.setText(EM_DASH);
        tvSerialStats.setText("");
        tvLastFix.setText(EM_DASH);
        compassView.setHeading(0f);
        if (miniMapView != null)
            miniMapView.clearTrack();
        if (popupMapView != null)
            popupMapView.clearTrack();
        if (signalBarsView != null)
            signalBarsView.setSignals(null);
    }

    private void clearServiceState() {
        synchronized (UsbSerialService.STATE_LOCK) {
            UsbSerialService.isRunning = false;
            UsbSerialService.lastConn = "Idle";
            UsbSerialService.lastSignal = EM_DASH;
            UsbSerialService.lastPos = EM_DASH;
            UsbSerialService.lastMovement = EM_DASH;
            UsbSerialService.lastSerialLog = EM_DASH;
            UsbSerialService.lastSatellites = EM_DASH;
            UsbSerialService.lastSatsInView = EM_DASH;
            UsbSerialService.lastSatsUsed = EM_DASH;
            UsbSerialService.totalBytes = 0;
            UsbSerialService.totalSents = 0;
            UsbSerialService.lastFixTime = 0;

            BluetoothGpsService.isRunning = false;
            BluetoothGpsService.lastConn = "Idle";
            BluetoothGpsService.lastSignal = EM_DASH;
            BluetoothGpsService.lastPos = EM_DASH;
            BluetoothGpsService.lastMovement = EM_DASH;
            BluetoothGpsService.lastSerialLog = EM_DASH;
            BluetoothGpsService.lastSatellites = EM_DASH;
            BluetoothGpsService.lastSatsInView = EM_DASH;
            BluetoothGpsService.lastSatsUsed = EM_DASH;
            BluetoothGpsService.totalBytes = 0;
            BluetoothGpsService.totalSents = 0;
            BluetoothGpsService.lastFixTime = 0;
        }
    }

    // -- Permissions -----------------------------------------------------------

    private void requestInitialPermissions() {
        List<String> missingPermissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missingPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.BLUETOOTH_CONNECT);
                missingPermissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        if (!missingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toArray(new String[0]),
                    REQUEST_INITIAL_PERMISSIONS);
        }
    }

    private boolean checkPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION },
                    REQUEST_LOCATION_PERMISSION);
            return false;
        }
        if ("bt".equals(connType) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[] { Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN },
                        REQUEST_BT_PERMISSION);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_LOCATION_PERMISSION && granted) {
            if (isAnyServiceRunning())
                return; // P2-2: don't double-start
            if ("bt".equals(connType)) {
                if (!selectedBtAddress.isEmpty() && checkPermissions()) {
                    Intent svc = new Intent(this, BluetoothGpsService.class);
                    svc.putExtra(BluetoothGpsService.EXTRA_BT_ADDRESS, selectedBtAddress);
                    svc.putExtra(BluetoothGpsService.EXTRA_BT_NAME, selectedBtName);
                    ContextCompat.startForegroundService(this, svc);
                }
            } else {
                Intent svc = new Intent(this, UsbSerialService.class);
                svc.putExtra(UsbSerialService.EXTRA_HZ, selectedHz);
                svc.putExtra(UsbSerialService.EXTRA_BAUD, selectedBaud);
                ContextCompat.startForegroundService(this, svc);
            }
        } else if (requestCode == REQUEST_BT_PERMISSION && granted) {
            if (isAnyServiceRunning())
                return; // P2-2: don't double-start
            if (!selectedBtAddress.isEmpty()) {
                Intent svc = new Intent(this, BluetoothGpsService.class);
                svc.putExtra(BluetoothGpsService.EXTRA_BT_ADDRESS, selectedBtAddress);
                svc.putExtra(BluetoothGpsService.EXTRA_BT_NAME, selectedBtName);
                ContextCompat.startForegroundService(this, svc);
            }
        }
    }

    // -- Formatters ------------------------------------------------------------

    private static String fmtBytes(long b) {
        if (b < 1024)
            return b + " B";
        if (b < 1024 * 1024)
            return String.format(Locale.ROOT, "%.1f KB", b / 1024f);
        return String.format(Locale.ROOT, "%.1f MB", b / (1024f * 1024f));
    }

    // FIX: java.text.SimpleDateFormat is NOT thread-safe. Replaced with
    // java.time.DateTimeFormatter (immutable, thread-safe) since minSdk=26.
    private static final java.time.format.DateTimeFormatter FMT_TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault());

    private static String fmtTime(long epochMs) {
        return FMT_TIME.format(java.time.Instant.ofEpochMilli(epochMs));
    }
}
