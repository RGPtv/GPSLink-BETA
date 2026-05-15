package com.gpslink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SignalBarsView extends View {

    public static class ConstellationSignal {
        public final String label;
        public final int avgSnr;
        public final int satCount;
        public final String snrText;
        public final String countText;

        public ConstellationSignal(String label, int avgSnr, int satCount) {
            this.label = label != null ? label : "???";
            this.avgSnr = Math.max(0, avgSnr);
            this.satCount = Math.max(0, satCount);
            this.snrText = this.avgSnr > 0 ? this.avgSnr + "dB" : "--";
            this.countText = this.satCount + (this.satCount == 1 ? " sat" : " sats");
        }
    }

    private static final int COLOR_TRACK = 0xFF1E293B; // bg_surface
    private static final int COLOR_LABEL = 0xFF94A3B8; // text_secondary
    private static final int COLOR_TEXT_DIM = 0xFF4A6080; // text_dim
    private static final int COLOR_NO_DATA = 0xFF374151;
    private static final int COLOR_GOOD = 0xFF10B981; // accent_emerald
    private static final int COLOR_FAIR = 0xFFF59E0B; // accent_amber
    private static final int COLOR_WEAK = 0xFFEF4444; // accent_red

    private static final int ROW_HEIGHT_DP = 30;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private List<ConstellationSignal> data = Collections.emptyList();
    private boolean hasData = false;

    public SignalBarsView(Context context) {
        super(context);
        init();
    }

    public SignalBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SignalBarsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        textPaint.setFakeBoldText(false);
    }

    public void setSignals(List<ConstellationSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            data = Collections.emptyList();
            hasData = false;
        } else {
            data = Collections.unmodifiableList(new ArrayList<>(signals));
            hasData = true;
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float density = getResources().getDisplayMetrics().density;
        int rows = Math.max(1, data.size());
        int totalH = (int) (rows * ROW_HEIGHT_DP * density + 4 * density);
        setMeasuredDimension(resolveSize(200, widthSpec), resolveSize(totalH, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float density = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float rowH = ROW_HEIGHT_DP * density;

        if (!hasData) {
            textPaint.setColor(COLOR_NO_DATA);
            textPaint.setTextSize(11 * density);
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("No signal data", 0, rowH * 0.65f, textPaint);
            return;
        }

        float labelW = 28 * density;
        float snrW = 42 * density;
        float gap = 8 * density;
        float trackW = width - labelW - snrW - gap * 2;
        if (trackW <= 0) return;

        float trackH = 8 * density;
        float radius = 4 * density;

        for (int i = 0; i < data.size(); i++) {
            ConstellationSignal signal = data.get(i);
            float midY = i * rowH + rowH * 0.5f;

            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(9 * density);
            textPaint.setColor(COLOR_LABEL);
            canvas.drawText(signal.label, 0, midY - 2 * density, textPaint);

            float left = labelW + gap;
            float top = midY - trackH / 2f;
            float right = left + trackW;
            float bottom = top + trackH;

            paint.setColor(COLOR_TRACK);
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, radius, radius, paint);

            float pct = Math.min(signal.avgSnr / 50f, 1f);
            int fillColor = signalColor(pct);
            if (pct > 0f) {
                paint.setColor(fillColor);
                rect.set(left, top, left + trackW * pct, bottom);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }

            textPaint.setColor(signal.avgSnr > 0 ? fillColor : COLOR_TEXT_DIM);
            textPaint.setTextSize(9 * density);
            canvas.drawText(signal.snrText, right + gap, midY - 2 * density, textPaint);
            textPaint.setColor(COLOR_TEXT_DIM);
            textPaint.setTextSize(7 * density);
            canvas.drawText(signal.countText, right + gap, midY + 9 * density, textPaint);
        }
    }

    private static int signalColor(float pct) {
        if (pct > 0.65f) return COLOR_GOOD;
        if (pct > 0.42f) return COLOR_FAIR;
        return COLOR_WEAK;
    }

}
