package ru.southcoast.terrain;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

/** Compact canvas-drawn control used instead of rows of default Android buttons. */
final class HudButton extends View {
    static final int MAP = 1, PLACES = 2, ROUTE = 3, SPEED = 4, WALK = 5, DRIVE = 6, FORWARD = 7, BACK = 8, PLAY = 9, STREETS = 10;

    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final RectF rect = new RectF();
    final float density;
    final int action;
    String label;
    boolean accent, compact, pressed;

    HudButton(Context context, int action, String label) {
        super(context);
        this.action = action;
        this.label = label;
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(true);
        setContentDescription(label);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    void setAccent(boolean value) { accent = value; invalidate(); }
    void setCompact(boolean value) { compact = value; invalidate(); }
    void setLabel(String value) { label = value; setContentDescription(value); invalidate(); }

    float dp(float value) { return value * density; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float pad = dp(compact ? 3 : 4);
        rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
        float radius = compact ? dp(18) : dp(14);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent ? (pressed ? Color.rgb(255, 210, 112) : Color.rgb(255, 192, 77))
                : (pressed ? Color.argb(245, 36, 62, 66) : Color.argb(226, 20, 42, 47)));
        c.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(accent ? Color.argb(120, 255, 238, 179) : Color.argb(105, 173, 219, 211));
        c.drawRoundRect(rect, radius, radius, paint);

        int iconColor = accent ? Color.rgb(25, 47, 48) : Color.rgb(222, 246, 238);
        paint.setColor(iconColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(compact ? 1.7f : 1.9f));
        float cx = getWidth() / 2f;
        float cy = compact ? getHeight() / 2f : getHeight() * .40f;
        float icon = compact ? dp(8) : dp(9);
        drawIcon(c, cx, cy, icon);

        if (!compact && label != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(10));
            paint.setColor(accent ? Color.rgb(31, 53, 50) : Color.rgb(231, 244, 235));
            c.drawText(label, cx, getHeight() - dp(8), paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }

    void drawIcon(Canvas c, float cx, float cy, float s) {
        switch (action) {
            case MAP:
                path.reset();
                path.moveTo(cx - s, cy - s * .75f); path.lineTo(cx - s * .25f, cy - s);
                path.lineTo(cx + s * .3f, cy - s * .6f); path.lineTo(cx + s, cy - s * .9f);
                path.lineTo(cx + s, cy + s * .7f); path.lineTo(cx + s * .3f, cy + s);
                path.lineTo(cx - s * .25f, cy + s * .65f); path.lineTo(cx - s, cy + s * .9f); path.close();
                c.drawPath(path, paint);
                c.drawLine(cx - s * .25f, cy - s, cx - s * .25f, cy + s * .65f, paint);
                c.drawLine(cx + s * .3f, cy - s * .6f, cx + s * .3f, cy + s, paint);
                break;
            case PLACES:
                c.drawCircle(cx, cy - s * .25f, s * .62f, paint);
                path.reset(); path.moveTo(cx - s * .5f, cy + s * .1f); path.lineTo(cx, cy + s); path.lineTo(cx + s * .5f, cy + s * .1f); c.drawPath(path, paint);
                break;
            case ROUTE:
                path.reset(); path.moveTo(cx - s * .9f, cy + s * .55f); path.cubicTo(cx - s * .2f, cy - s * .6f, cx + s * .2f, cy + s * .8f, cx + s * .9f, cy - s * .45f); c.drawPath(path, paint);
                c.drawCircle(cx - s * .9f, cy + s * .55f, dp(2.2f), paint); c.drawCircle(cx + s * .9f, cy - s * .45f, dp(2.2f), paint);
                break;
            case SPEED:
                RectF arc = new RectF(cx - s, cy - s * .85f, cx + s, cy + s * .9f);
                c.drawArc(arc, 200, 140, false, paint);
                c.drawLine(cx, cy + s * .15f, cx + s * .45f, cy - s * .45f, paint);
                c.drawCircle(cx, cy + s * .15f, dp(1.8f), paint);
                break;
            case WALK:
                c.drawCircle(cx, cy - s * .7f, s * .28f, paint);
                c.drawLine(cx, cy - s * .35f, cx, cy + s * .35f, paint);
                c.drawLine(cx, cy - s * .1f, cx - s * .55f, cy + s * .1f, paint);
                c.drawLine(cx, cy - s * .1f, cx + s * .55f, cy - s * .4f, paint);
                c.drawLine(cx, cy + s * .35f, cx - s * .55f, cy + s, paint);
                c.drawLine(cx, cy + s * .35f, cx + s * .55f, cy + s, paint);
                break;
            case DRIVE:
                RectF car = new RectF(cx - s, cy - s * .35f, cx + s, cy + s * .4f);
                c.drawRoundRect(car, s * .25f, s * .25f, paint);
                c.drawLine(cx - s * .55f, cy - s * .35f, cx - s * .25f, cy - s * .8f, paint);
                c.drawLine(cx + s * .55f, cy - s * .35f, cx + s * .25f, cy - s * .8f, paint);
                c.drawCircle(cx - s * .55f, cy + s * .55f, s * .2f, paint);
                c.drawCircle(cx + s * .55f, cy + s * .55f, s * .2f, paint);
                break;
            case FORWARD:
                path.reset(); path.moveTo(cx - s * .55f, cy - s); path.lineTo(cx + s * .55f, cy); path.lineTo(cx - s * .55f, cy + s); c.drawPath(path, paint);
                break;
            case BACK:
                path.reset(); path.moveTo(cx + s * .55f, cy - s); path.lineTo(cx - s * .55f, cy); path.lineTo(cx + s * .55f, cy + s); c.drawPath(path, paint);
                break;
            case PLAY:
                paint.setStyle(Paint.Style.FILL);
                path.reset(); path.moveTo(cx - s * .45f, cy - s); path.lineTo(cx + s * .75f, cy); path.lineTo(cx - s * .45f, cy + s); path.close(); c.drawPath(path, paint);
                break;
            case STREETS:
                path.reset();
                path.moveTo(cx, cy - s); path.lineTo(cx, cy + s);
                path.moveTo(cx - s, cy); path.lineTo(cx + s, cy);
                c.drawPath(path, paint);
                break;
            default:
                c.drawCircle(cx, cy, s * .7f, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) { pressed = true; invalidate(); return true; }
        if (action == MotionEvent.ACTION_CANCEL) { pressed = false; invalidate(); return true; }
        if (action == MotionEvent.ACTION_UP) {
            pressed = false; invalidate(); performClick(); return true;
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
