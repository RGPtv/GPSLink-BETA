package com.gpslink;

import android.app.UiModeManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

// osmdroid Configuration accessed via fully-qualified name to avoid clash with android.content.res.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class MiniMapView extends FrameLayout {
    private static final int MAX_TRACK_POINTS = 120;
    private static final double DEFAULT_LAT = 14.5995;
    private static final double DEFAULT_LON = 120.9842;
    private static final int COLOR_DOT = 0xFF22D3EE; // accent_cyan
    private static final int COLOR_DOT_RING = 0xFFFFFFFF;
    private static final int COLOR_DOT_HALO = 0x5522D3EE; // accent_cyan halo
    private static final int RETINA_TILE_SIZE = 512;

    private static final OnlineTileSourceBase DAY_TILES = new OnlineTileSourceBase(
        "Carto Voyager Retina",
        1,
        20,
        RETINA_TILE_SIZE,
        ".png",
        new String[] {
            "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
            "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
        }
    ) {
        @Override
        public String getTileURLString(long tileIndex) {
            return getBaseUrl()
                + MapTileIndex.getZoom(tileIndex) + "/"
                + MapTileIndex.getX(tileIndex) + "/"
                + MapTileIndex.getY(tileIndex) + "@2x"
                + mImageFilenameEnding;
        }
    };

    private static final OnlineTileSourceBase NIGHT_TILES = new OnlineTileSourceBase(
        "Carto Dark Matter Retina",
        1,
        20,
        RETINA_TILE_SIZE,
        ".png",
        new String[] {
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/",
            "https://d.basemaps.cartocdn.com/dark_all/"
        }
    ) {
        @Override
        public String getTileURLString(long tileIndex) {
            return getBaseUrl()
                + MapTileIndex.getZoom(tileIndex) + "/"
                + MapTileIndex.getX(tileIndex) + "/"
                + MapTileIndex.getY(tileIndex) + "@2x"
                + mImageFilenameEnding;
        }
    };

    private final MapView mapView;
    private final Marker marker;
    private final Polyline trackLine;
    // [PossIss-6] Replace ArrayList with ArrayDeque — removeFirst() is O(1)
    private final ArrayDeque<GeoPoint> track = new ArrayDeque<>();
    private View clickOverlay;

    public MiniMapView(Context context) {
        this(context, null);
    }
    private boolean isFollowing = true;
    private boolean isProgrammaticScroll = false;
    private View btnRecenter;

    public MiniMapView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MiniMapView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        org.osmdroid.config.Configuration.getInstance().setUserAgentValue(context.getPackageName());

        setBackgroundColor(0xFF06090F); // bg_main
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dpToPx(10));
            }
        });

        mapView = new MapView(context);
        mapView.setTileSource(isNightNow() ? NIGHT_TILES : DAY_TILES);
        mapView.setTilesScaledToDpi(true);
        mapView.setFlingEnabled(true);
        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(false);
        mapView.setUseDataConnection(true);
        mapView.setMinZoomLevel(3.0);
        mapView.setMaxZoomLevel(20.0);
        mapView.getController().setZoom(18.0);
        mapView.getController().setCenter(new GeoPoint(DEFAULT_LAT, DEFAULT_LON));
        addView(mapView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        trackLine = new Polyline(mapView);
        trackLine.getOutlinePaint().setColor(0xFF3B82F6); // primary
        trackLine.getOutlinePaint().setStrokeWidth(dpToPx(3));
        trackLine.getOutlinePaint().setAlpha(230);
        mapView.getOverlayManager().add(trackLine);

        marker = new Marker(mapView);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        marker.setInfoWindow(null);
        marker.setIcon(new android.graphics.drawable.BitmapDrawable(getResources(), createBlueDotBitmap()));
        marker.setPosition(new GeoPoint(DEFAULT_LAT, DEFAULT_LON));
        mapView.getOverlayManager().add(marker);

        // Add a transparent overlay to catch clicks
        clickOverlay = new View(context);
        clickOverlay.setFocusable(true);
        clickOverlay.setClickable(true);
        clickOverlay.setVisibility(GONE); // Hidden by default
        addView(clickOverlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        clickOverlay.setOnClickListener(v -> performClick());

        mapView.addMapListener(new org.osmdroid.events.MapListener() {
            @Override
            public boolean onScroll(org.osmdroid.events.ScrollEvent event) {
                if (isProgrammaticScroll) return false;
                
                // Only allow manual "Free Roam" if a recenter button is provided.
                // This ensures the dashboard minimap always follows the GPS.
                if (btnRecenter != null) {
                    isFollowing = false;
                    btnRecenter.setVisibility(VISIBLE);
                }
                return false;
            }
            @Override
            public boolean onZoom(org.osmdroid.events.ZoomEvent event) { return false; }
        });
    }

    public void setRecenterButton(View btn) {
        this.btnRecenter = btn;
        if (btn != null) {
            btn.setOnClickListener(v -> {
                isFollowing = true;
                btn.setVisibility(GONE);
                if (marker.getPosition() != null) {
                    isProgrammaticScroll = true;
                    mapView.getController().setCenter(marker.getPosition());
                    mapView.postDelayed(() -> isProgrammaticScroll = false, 200);
                }
            });
        }
    }

    public void setInteractivity(boolean interactive) {
        if (clickOverlay != null) {
            clickOverlay.setVisibility(interactive ? VISIBLE : GONE);
        }
    }

    public void setPosition(double latitude, double longitude) {
        OnlineTileSourceBase correctTiles = isNightNow() ? NIGHT_TILES : DAY_TILES;
        if (mapView.getTileProvider().getTileSource() != correctTiles) {
            mapView.setTileSource(correctTiles);
        }
        GeoPoint point = new GeoPoint(latitude, longitude);
        track.addLast(point);
        // [PossIss-6] O(1) removeFirst() with ArrayDeque
        if (track.size() > MAX_TRACK_POINTS) {
            track.removeFirst();
        }

        marker.setPosition(point);
        trackLine.setPoints(new ArrayList<>(track));
        if (isFollowing) {
            isProgrammaticScroll = true;
            mapView.getController().setCenter(point);
            // Allow events to settle before re-enabling manual detection
            mapView.postDelayed(() -> isProgrammaticScroll = false, 100);
        }
        mapView.invalidate();
    }

    public List<GeoPoint> getTrack() {
        return new ArrayList<>(track);
    }

    public void setTrack(List<GeoPoint> newTrack) {
        track.clear();
        track.addAll(newTrack);
        trackLine.setPoints(new ArrayList<>(track));
        if (!track.isEmpty()) {
            GeoPoint last = track.getLast();
            marker.setPosition(last);
            isProgrammaticScroll = true;
            mapView.getController().setCenter(last);
            mapView.postDelayed(() -> isProgrammaticScroll = false, 200);
        }
        mapView.invalidate();
    }

    public void clearTrack() {
        track.clear();
        trackLine.setPoints(new ArrayList<>(track));
        mapView.invalidate();
    }

    public void onResume() {
        mapView.setTileSource(isNightNow() ? NIGHT_TILES : DAY_TILES);
        mapView.onResume();
    }

    public void onPause() {
        mapView.onPause();
    }

    @Override
    protected void onDetachedFromWindow() {
        mapView.onDetach();
        super.onDetachedFromWindow();
    }

    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private Bitmap createBlueDotBitmap() {
        int size = dpToPx(26);
        int center = size / 2;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_DOT_HALO);
        canvas.drawCircle(center, center, dpToPx(12), paint);

        paint.setColor(COLOR_DOT_RING);
        canvas.drawCircle(center, center, dpToPx(7), paint);

        paint.setColor(COLOR_DOT);
        canvas.drawCircle(center, center, dpToPx(5), paint);

        return bitmap;
    }

    /**
     * [Impr-3] Use UiModeManager for night detection instead of hour-of-day.
     * Falls back to hour-based heuristic only when system mode is AUTO.
     */
    private boolean isNightNow() {
        try {
            UiModeManager uiModeManager = (UiModeManager) getContext().getSystemService(Context.UI_MODE_SERVICE);
            if (uiModeManager != null) {
                int nightMode = uiModeManager.getNightMode();
                if (nightMode == UiModeManager.MODE_NIGHT_YES) return true;
                if (nightMode == UiModeManager.MODE_NIGHT_NO) return false;
            }
            // Also check current Configuration for UI_MODE_NIGHT_YES
            int uiMode = getContext().getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            if (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) return true;
            if (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_NO) return false;
        } catch (Exception ignored) {}
        // Fallback: hour-based heuristic
        int hour = java.time.LocalTime.now().getHour();
        return hour < 6 || hour >= 18;
    }
}
