package ru.southcoast.terrain;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Graphic regional map: hillshade backdrop, the whole South Coast highway and settlements. */
final class RegionMapView extends View {
    interface Pick { void at(float x, float z); }

    final Region region;
    final Pick pick;
    final boolean compact;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final RectF dstRect = new RectF();
    final ArrayList<RectF> occupied = new ArrayList<>();
    final Typeface boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    final float density;
    final int slop;
    float x, z, heading, zoom = 1, panX, panY;
    float scale, left, top, lastX, lastY, downX, downY, previousSpan, previousFocusX, previousFocusY;
    boolean dragged, multi;
    float[] selection;

    RegionMapView(Context context, Region region, Pick pick, boolean compact) {
        super(context);
        this.region = region;
        this.pick = pick;
        this.compact = compact;
        density = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setContentDescription(compact
                ? "Мини-карта ЮБК. Нажмите, чтобы открыть весь маршрут."
                : "Карта ЮБК от Севастополя до Алушты. Два пальца — масштаб, один — перемещение, тап — выбор места.");
    }

    void position(float x, float z, float heading) {
        this.x = x; this.z = z; this.heading = heading;
        invalidate();
    }

    void select(float[] p) { selection = p; invalidate(); }
    void resetView() { zoom = 1; panX = panY = 0; invalidate(); }

    float baseScale() {
        float w = Math.max(1, getWidth() - (compact ? 4 : 20) * density);
        float h = Math.max(1, getHeight() - (compact ? 4 : 20) * density);
        return Math.max(.0001f, Math.min(w / (region.worldRight - region.worldLeft), h / (region.worldBottom - region.worldTop)));
    }

    void clampPan() {
        float s = baseScale() * zoom;
        float mx = Math.max(0, (region.worldRight - region.worldLeft) * s / 2 - getWidth() / 2f + (compact ? 2 : 10) * density);
        float my = Math.max(0, (region.worldBottom - region.worldTop) * s / 2 - getHeight() / 2f + (compact ? 2 : 10) * density);
        panX = Math.max(-mx, Math.min(mx, panX));
        panY = Math.max(-my, Math.min(my, panY));
    }

    void transform() {
        scale = baseScale() * zoom;
        float cx = (region.worldLeft + region.worldRight) / 2, cz = (region.worldTop + region.worldBottom) / 2;
        left = getWidth() / 2f + panX - cx * scale;
        top = getHeight() / 2f + panY - cz * scale;
    }

    float px(float x) { return left + x * scale; }
    float py(float z) { return top + z * scale; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        transform();
        c.drawColor(Color.rgb(10, 24, 29));
        if (region.map != null) {
            dstRect.set(px(region.worldLeft), py(region.worldTop), px(region.worldRight), py(region.worldBottom));
            paint.setFilterBitmap(true);
            c.drawBitmap(region.map, null, dstRect, paint);
        }

        // The whole Sevastopol-Alushta highway stays visible as the visual spine of the screen.
        float[] r = region.route;
        if (r.length >= 8) {
            path.reset();
            path.moveTo(px(r[0]), py(r[1]));
            for (int i = 4; i + 1 < r.length; i += 4) path.lineTo(px(r[i]), py(r[i + 1]));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.argb(190, 6, 25, 28));
            paint.setStrokeWidth((compact ? 5 : 7) * density);
            c.drawPath(path, paint);
            paint.setColor(Color.rgb(255, 193, 80));
            paint.setStrokeWidth((compact ? 2.4f : 3.5f) * density);
            c.drawPath(path, paint);
        }

        drawRoads(c);
        drawPlaces(c);
        if (selection != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(255, 186, 67));
            c.drawCircle(px(selection[0]), py(selection[1]), (compact ? 3 : 7) * density, paint);
            if (!compact) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * density);
                paint.setColor(Color.argb(190, 255, 225, 155));
                c.drawCircle(px(selection[0]), py(selection[1]), 13 * density, paint);
            }
        }
        drawMarker(c);
        if (!compact) drawScale(c);
    }

    void drawRoads(Canvas c) {
        float[] r = region.roads;
        if (r == null || r.length < 4) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.argb(78, 226, 218, 194));
        paint.setStrokeWidth((compact ? 0.6f : 1.1f) * density);
        path.reset();
        for (int i = 0; i + 3 < r.length; i += 4) {
            path.moveTo(px(r[i]), py(r[i + 1]));
            path.lineTo(px(r[i + 2]), py(r[i + 3]));
        }
        c.drawPath(path, paint);
    }

    void drawPlaces(Canvas c) {
        int occupiedCount = 0;
        paint.setTypeface(boldTypeface);
        for (Region.Place p : region.places) {
            if (compact && !p.major()) continue;
            if (!compact && zoom < 1.8f && !p.major()) continue;
            float sx = px(p.x), sy = py(p.z);
            if (sx < -20 || sx > getWidth() + 20 || sy < -20 || sy > getHeight() + 20) continue;
            int size = compact ? 7 : p.major() ? 13 : 11;
            paint.setTextSize(size * density);
            float width = paint.measureText(p.name);
            float tx = sx + 6 * density, ty = sy - 5 * density;
            RectF box;
            if (occupiedCount < occupied.size()) box = occupied.get(occupiedCount);
            else { box = new RectF(); occupied.add(box); }
            box.set(tx - 3 * density, ty - size * density, tx + width + 3 * density, ty + 4 * density);
            if (box.left < 3 || box.right > getWidth() - 3 || box.top < 2 || box.bottom > getHeight() - 2) continue;
            boolean overlap = false;
            for (int i = 0; i < occupiedCount; i++) if (RectF.intersects(occupied.get(i), box)) { overlap = true; break; }
            if (overlap) continue;
            occupiedCount++;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(p.major() ? Color.rgb(255, 238, 185) : Color.rgb(235, 239, 221));
            if (compact) paint.setColor(Color.argb(210, 245, 241, 218));
            c.drawCircle(sx, sy, (p.major() ? 2.8f : 2f) * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f * density);
            paint.setColor(Color.argb(190, 12, 27, 30));
            c.drawText(p.name, tx, ty, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(compact ? Color.rgb(240, 238, 216) : p.major() ? Color.rgb(255, 247, 217) : Color.rgb(235, 239, 221));
            c.drawText(p.name, tx, ty, paint);
        }
        paint.setTypeface(Typeface.DEFAULT);
    }

    void drawMarker(Canvas c) {
        float sx = px(x), sy = py(z);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(65, 86, 225, 247));
        c.drawCircle(sx, sy, (compact ? 9 : 16) * density, paint);
        paint.setColor(Color.rgb(87, 232, 250));
        c.drawCircle(sx, sy, (compact ? 3 : 5) * density, paint);
        c.save();
        c.translate(sx, sy);
        c.rotate(heading);
        path.reset();
        path.moveTo(0, -(compact ? 9 : 14) * density);
        path.lineTo((compact ? 5 : 8) * density, (compact ? 6 : 10) * density);
        path.lineTo(0, (compact ? 3 : 5) * density);
        path.lineTo(-(compact ? 5 : 8) * density, (compact ? 6 : 10) * density);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(95, 238, 255));
        c.drawPath(path, paint);
        c.restore();
    }

    void drawScale(Canvas c) {
        int meters = zoom < 1.6f ? 10000 : zoom < 3 ? 5000 : zoom < 6 ? 2000 : 1000;
        float width = meters * scale;
        if (width < 36 * density) { meters *= 2; width *= 2; }
        float y = getHeight() - 18 * density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * density);
        paint.setColor(Color.rgb(245, 242, 218));
        c.drawLine(14 * density, y, 14 * density + width, y, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(11 * density);
        c.drawText(meters >= 1000 ? (meters / 1000) + " км" : meters + " м", 14 * density, y - 7 * density, paint);
        c.drawText("С ↑", 14 * density, 17 * density, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (compact) return super.onTouchEvent(e);
        int action = e.getActionMasked();
        if (e.getPointerCount() >= 2) {
            multi = true; dragged = true;
            float span = (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
            float fx = (e.getX(0) + e.getX(1)) / 2, fy = (e.getY(0) + e.getY(1)) / 2;
            if (action == MotionEvent.ACTION_MOVE && previousSpan > 0 && span > 0) {
                float next = Math.max(1, Math.min(18, zoom * span / previousSpan)), ratio = next / zoom;
                panX = fx - getWidth() / 2f - (previousFocusX - getWidth() / 2f - panX) * ratio;
                panY = fy - getHeight() / 2f - (previousFocusY - getHeight() / 2f - panY) * ratio;
                zoom = next; clampPan(); invalidate();
            }
            previousSpan = span; previousFocusX = fx; previousFocusY = fy;
            if (action == MotionEvent.ACTION_POINTER_UP) previousSpan = 0;
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            lastX = downX = e.getX(); lastY = downY = e.getY();
            dragged = multi = false; previousSpan = 0;
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (Math.hypot(e.getX() - downX, e.getY() - downY) > slop) dragged = true;
            if (!multi) {
                panX += e.getX() - lastX; panY += e.getY() - lastY;
                clampPan(); invalidate();
            }
            lastX = e.getX(); lastY = e.getY();
        } else if (action == MotionEvent.ACTION_UP) {
            getParent().requestDisallowInterceptTouchEvent(false);
            if (!dragged) {
                performClick();
                transform();
                float wx = (e.getX() - left) / scale, wz = (e.getY() - top) / scale;
                if (pick != null) pick.at(wx, wz);
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            dragged = true; getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
