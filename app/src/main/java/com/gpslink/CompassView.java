package com.gpslink;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * CompassView draws a GPS-style compass dial and animates the needle
 * to a new heading using a smooth ValueAnimator.
 *
 * Usage in XML:
 *   <com.gpslink.CompassView
 *       android:id="@+id/compassView"
 *       android:layout_width="110dp"
 *       android:layout_height="110dp" />
 *
 * Usage in Java:
 *   compassView.setHeading(127.5f);  // degrees, 0 = North
 */
public class CompassView extends View {

    // -- Colours --------------------------------------------------------------
    private static final int COL_RING_BG      = 0xFF0F172A; // bg_card
    private static final int COL_RING_BORDER  = 0xFF1E293B; // bg_surface
    private static final int COL_TICK_MAJOR   = 0xFF3B82F6; // primary
    private static final int COL_TICK_MINOR   = 0xFF1E293B; // bg_surface
    private static final int COL_LABEL_NSEW   = 0xFF94A3B8; // text_secondary
    private static final int COL_LABEL_N      = 0xFFEF4444; // accent_red
    private static final int COL_NEEDLE_NORTH = 0xFFEF4444; // accent_red
    private static final int COL_NEEDLE_SOUTH = 0xFF4A6080; // text_dim
    private static final int COL_CENTER_FILL  = 0xFF0F172A; // bg_card
    private static final int COL_CENTER_RING  = 0xFF3B82F6; // primary

    // -- Paints ---------------------------------------------------------------
    private final Paint paintRing       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintRingBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTickMajor  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTickMinor  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintLabelN     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintLabelCard  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintNeedleN    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintNeedleS    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintCenterFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintCenterRing = new Paint(Paint.ANTI_ALIAS_FLAG);

    // -- State -----------------------------------------------------------------
    // FIX: currentHeading was never reset when a new animator starts from a
    //      cancelled mid-flight animation; it now always starts from the true
    //      last-drawn angle, avoiding a visual snap back to 0 on rapid updates.
    private float currentHeading = 0f;  // current visual angle (animated)

    private ValueAnimator animator;

    // -- Geometry (populated in onSizeChanged) ---------------------------------
    private float cx, cy, radius;
    private final RectF oval = new RectF();

    // -- Re-usable Path objects (avoid per-frame allocation) -------------------
    // FIX: Creating new Path objects inside onDraw() triggers GC on every frame.
    //      Reuse them instead.
    private final Path northPath = new Path();
    private final Path southPath = new Path();

    // -- Constructor ----------------------------------------------------------
    public CompassView(Context context) {
        super(context);
        init();
    }
    public CompassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    public CompassView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paintRing      .setColor(COL_RING_BG);
        paintRing      .setStyle(Paint.Style.FILL);

        paintRingBorder.setColor(COL_RING_BORDER);
        paintRingBorder.setStyle(Paint.Style.STROKE);
        paintRingBorder.setStrokeWidth(dpToPx(2));

        paintTickMajor .setColor(COL_TICK_MAJOR);
        paintTickMajor .setStyle(Paint.Style.STROKE);
        paintTickMajor .setStrokeWidth(dpToPx(1.5f));
        paintTickMajor .setStrokeCap(Paint.Cap.ROUND);

        paintTickMinor .setColor(COL_TICK_MINOR);
        paintTickMinor .setStyle(Paint.Style.STROKE);
        paintTickMinor .setStrokeWidth(dpToPx(0.8f));
        paintTickMinor .setStrokeCap(Paint.Cap.ROUND);

        paintLabelN    .setColor(COL_LABEL_N);
        paintLabelN    .setTextAlign(Paint.Align.CENTER);
        paintLabelN    .setTextSize(spToPx(10));
        paintLabelN    .setFakeBoldText(true);

        paintLabelCard .setColor(COL_LABEL_NSEW);
        paintLabelCard .setTextAlign(Paint.Align.CENTER);
        paintLabelCard .setTextSize(spToPx(8));

        paintNeedleN   .setStyle(Paint.Style.FILL);
        paintNeedleN   .setColor(COL_NEEDLE_NORTH);
        paintNeedleN   .setAntiAlias(true);

        paintNeedleS   .setStyle(Paint.Style.FILL);
        paintNeedleS   .setColor(COL_NEEDLE_SOUTH);
        paintNeedleS   .setAntiAlias(true);

        paintCenterFill.setColor(COL_CENTER_FILL);
        paintCenterFill.setStyle(Paint.Style.FILL);

        paintCenterRing.setColor(COL_CENTER_RING);
        paintCenterRing.setStyle(Paint.Style.STROKE);
        paintCenterRing.setStrokeWidth(dpToPx(1.5f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(cx, cy) * 0.92f;
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
    }

    // -- Public API ------------------------------------------------------------

    /**
     * Animate the compass needle to the given heading (0-360 degrees, 0 = North).
     * Picks the shortest arc to avoid spinning the wrong way.
     */
    public void setHeading(float newHeading) {
        // FIX: normalise to [0, 360) first
        newHeading = ((newHeading % 360) + 360) % 360;

        if (animator != null && animator.isRunning()) {
            // FIX: capture the mid-animation position BEFORE cancelling, so the
            //      new animation starts from wherever the needle currently is
            //      (not from the stale `from` that was set when the old anim began).
            animator.cancel();
        }

        float from = currentHeading;

        // Shortest-arc correction
        float delta = newHeading - from;
        if (delta > 180)  delta -= 360;
        if (delta < -180) delta += 360;
        float finalTo = from + delta;

        animator = ValueAnimator.ofFloat(from, finalTo);
        animator.setDuration(400);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(anim -> {
            currentHeading = (float) anim.getAnimatedValue();
            invalidate();
        });
        // FIX: keep currentHeading in [0,360) after animation ends so the
        //      next shortest-arc calculation doesn't accumulate large offsets.
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                currentHeading = ((currentHeading % 360) + 360) % 360;
            }
        });
        animator.start();
    }

    // -- Drawing ---------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. Background circle
        canvas.drawCircle(cx, cy, radius, paintRing);
        canvas.drawCircle(cx, cy, radius, paintRingBorder);

        // 2. Ticks (drawn on the fixed dial, not rotated with heading)
        for (int i = 0; i < 72; i++) {
            float angle = i * 5f;
            boolean major = (i % 9 == 0);   // every 45 degrees
            boolean semi  = (i % 3 == 0);   // every 15 degrees
            float innerFraction = major ? 0.76f : (semi ? 0.82f : 0.87f);
            float rad  = (float) Math.toRadians(angle - 90);
            float sinA = (float) Math.sin(rad);
            float cosA = (float) Math.cos(rad);
            canvas.drawLine(
                cx + cosA * innerFraction * radius,
                cy + sinA * innerFraction * radius,
                cx + cosA * radius,
                cy + sinA * radius,
                major ? paintTickMajor : paintTickMinor
            );
        }

        // 3. Cardinal labels (fixed on dial)
        float labelR = radius * 0.62f;
        canvas.drawText("N", cx,                        cy - labelR + spToPx(4), paintLabelN);
        canvas.drawText("S", cx,                        cy + labelR + spToPx(4), paintLabelCard);
        canvas.drawText("E", cx + labelR + dpToPx(2),  cy + spToPx(4),          paintLabelCard);
        canvas.drawText("W", cx - labelR - dpToPx(2),  cy + spToPx(4),          paintLabelCard);

        // 4. Rotating needle: rotate canvas around center by heading
        canvas.save();
        canvas.rotate(currentHeading, cx, cy);

        float needleW    = dpToPx(4f);
        float needleTip  = cy - radius * 0.58f;   // north tip
        float needleTail = cy + radius * 0.38f;   // south tail

        // FIX: reuse pre-allocated Path objects; call rewind() instead of
        //      creating new Path() instances on every draw frame.
        northPath.rewind();
        northPath.moveTo(cx, needleTip);
        northPath.lineTo(cx + needleW, cy);
        northPath.lineTo(cx - needleW, cy);
        northPath.close();
        canvas.drawPath(northPath, paintNeedleN);

        southPath.rewind();
        southPath.moveTo(cx, needleTail);
        southPath.lineTo(cx + needleW, cy);
        southPath.lineTo(cx - needleW, cy);
        southPath.close();
        canvas.drawPath(southPath, paintNeedleS);

        canvas.restore();

        // 5. Center pivot dot
        float pivotR = dpToPx(5f);
        canvas.drawCircle(cx, cy, pivotR, paintCenterFill);
        canvas.drawCircle(cx, cy, pivotR, paintCenterRing);
    }

    // -- View lifecycle --------------------------------------------------------

    /**
     * FIX: Cancel any running animator when the view is detached to prevent
     *      memory leaks from the animator holding a reference to this View.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    // -- Helpers ---------------------------------------------------------------

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private float spToPx(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }
}
