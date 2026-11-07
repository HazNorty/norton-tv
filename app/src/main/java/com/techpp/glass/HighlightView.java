package com.techpp.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Full-screen overlay that draws the one focus ring: a white outline around the focused card or tile, moved
 * (with a small spring) by the activity. In the day theme a faint dark edge keeps it visible on light backgrounds;
 * while an app is picked up for moving the ring is thicker.
 */
public class HighlightView extends View {
    final RectF rect = new RectF();
    private float radius;
    private boolean day, moving;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final RectF tmp = new RectF();

    public HighlightView(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(0x33000000);
        edge.setStrokeWidth(dp);
        setFocusable(false);
        setWillNotDraw(false);
    }

    void setRing(RectF r, float radiusPx) {
        rect.set(r);
        radius = radiusPx;
        invalidate();
    }

    void setDay(boolean d) { day = d; invalidate(); }

    /** Norton TV: the ring in Control Room's focus colour (ink by day, white at night). */
    void setColour(int c) { ring.setColor(c); invalidate(); }

    void setMoving(boolean m) { moving = m; invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        if (rect.isEmpty()) return;
        float w = (moving ? 3f : 1.5f) * dp;  // Norton TV: Control Room's 3 px ring on the 1920 grid
        ring.setStrokeWidth(w);
        tmp.set(rect);
        tmp.inset(-w / 2, -w / 2);
        canvas.drawRoundRect(tmp, radius + w / 2, radius + w / 2, ring);
        if (day && ring.getColor() == 0xFFFFFFFF) {
            tmp.inset(-w / 2 - dp / 2, -w / 2 - dp / 2);
            canvas.drawRoundRect(tmp, radius + w + dp / 2, radius + w + dp / 2, edge);
        }
    }
}
