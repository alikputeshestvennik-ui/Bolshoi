package ru.southcoast.terrain;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Graphic regional map: hillshade backdrop, the whole South Coast highway and settlements. */
final class RegionMapView extends View {
    interface Pick { void at(float x, float z, Region.Street street); }

    final Region region;
    final Pick pick;
    final boolean compact;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final RectF dstRect = new RectF();
    final ArrayList<RectF> occupied = new ArrayList<>();
    final ArrayList<Region.Street> visibleStreets = new ArrayList<>();
    final ArrayList<RectF> streetRects = new ArrayList<>();
    final Typeface boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    int placeLabelCount;
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
        if (compact) {
            float w = Math.max(1, getWidth() - 4 * density);
            float h = Math.max(1, getHeight() - 4 * density);
            return Math.max(.0001f, Math.min(w, h) / 1000f);
        }
        float w = Math.max(1, getWidth() - (compact ? 4 : 20) * density);
        float h = Math.max(1, getHeight() - (compact ? 4 : 20) * density);
        return Math.max(.0001f, Math.min(w / (region.worldRight - region.worldLeft), h / (region.worldBottom - region.worldTop)));
    }

    void clampPan() {
        if (compact) { panX = panY = 0; return; }
        float s = baseScale() * zoom;
        float mx = Math.max(0, (region.worldRight - region.worldLeft) * s / 2 - getWidth() / 2f + (compact ? 2 : 10) * density);
        float my = Math.max(0, (region.worldBottom - region.worldTop) * s / 2 - getHeight() / 2f + (compact ? 2 : 10) * density);
        panX = Math.max(-mx, Math.min(mx, panX));
        panY = Math.max(-my, Math.min(my, panY));
    }

    void transform() {
        scale = baseScale() * zoom;
        float cx = compact ? x : (region.worldLeft + region.worldRight) / 2;
        float cz = compact ? z : (region.worldTop + region.worldBottom) / 2;
        left = getWidth() / 2f + panX - cx * scale;
        top = getHeight() / 2f + panY - cz * scale;
    }

    float px(float x) { return left + x * scale; }
    float py(float z) { return top + z * scale; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        transform();
        c.drawColor(Color.rgb(10, 24, 29));
        if (region.map != null && (compact || zoom <= 40f)) {
            dstRect.set(px(region.worldLeft), py(region.worldTop), px(region.worldRight), py(region.worldBottom));
            paint.setFilterBitmap(true);
            c.drawBitmap(region.map, null, dstRect, paint);
        } else {
            // Deep street-level zoom: a clean flat base keeps the vector overlay crisp.
            c.drawColor(Color.rgb(31, 48, 51));
        }
        drawLanduse(c);
        drawCoast(c);

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

        drawWater(c);
        drawRoads(c);
        drawBranches(c);
        drawPlaces(c);
        drawStreetLabels(c);
        drawWaterLabels(c);
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

    void drawLanduse(Canvas c) {
        if (region.landuse == null || region.landuse.length == 0 || compact) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        for (Region.Landuse area : region.landuse) {
            if (area.points.length < 6) continue;
            path.reset();
            path.moveTo(px(area.points[0]), py(area.points[1]));
            for (int i = 2; i + 1 < area.points.length; i += 2) path.lineTo(px(area.points[i]), py(area.points[i + 1]));
            path.close();
            paint.setColor(landuseFillColor(area.kind));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.6f * density);
            paint.setColor(landuseStrokeColor(area.kind));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private int landuseFillColor(String kind) {
        switch (kind) {
            case "commercial": case "retail": return Color.argb(72, 220, 206, 174);
            case "industrial": return Color.argb(82, 188, 180, 158);
            default: return Color.argb(58, 214, 204, 180);
        }
    }

    private int landuseStrokeColor(String kind) {
        switch (kind) {
            case "commercial": case "retail": return Color.argb(95, 232, 216, 184);
            case "industrial": return Color.argb(95, 198, 190, 168);
            default: return Color.argb(82, 224, 216, 190);
        }
    }

    void drawCoast(Canvas c) {
        if (region.coast == null || region.coast.length == 0) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.argb(compact ? 150 : 200, 228, 232, 210));
        paint.setStrokeWidth((compact ? 0.7f : 1.3f) * density);
        for (float[] line : region.coast) {
            if (line.length < 4) continue;
            path.reset();
            path.moveTo(px(line[0]), py(line[1]));
            for (int i = 2; i + 1 < line.length; i += 2) path.lineTo(px(line[i]), py(line[i + 1]));
            c.drawPath(path, paint);
        }
    }

    void drawWater(Canvas c) {
        if (region.water == null || region.water.length == 0) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (Region.Water water : region.water) {
            if (water.points.length < 4) continue;
            path.reset();
            path.moveTo(px(water.points[0]), py(water.points[1]));
            for (int i = 2; i + 1 < water.points.length; i += 2) path.lineTo(px(water.points[i]), py(water.points[i + 1]));
            float base = "river".equals(water.kind) ? 1.45f : "canal".equals(water.kind) ? 1.1f : .8f;
            if (compact) base *= .7f;
            paint.setColor(Color.argb(185, 7, 38, 51));
            paint.setStrokeWidth((base + 1.8f) * density);
            c.drawPath(path, paint);
            paint.setColor("river".equals(water.kind) ? Color.rgb(82, 183, 222) : Color.rgb(71, 157, 197));
            paint.setStrokeWidth(base * density);
            c.drawPath(path, paint);
        }
    }

    void drawWaterLabels(Canvas c) {
        if (compact || zoom < 3.2f || region.water == null || region.water.length == 0) return;
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        paint.setTextSize(9.5f * density);
        int shown = 0;
        for (Region.Water water : region.water) {
            if (water.points.length < 4) continue;
            int mid = (water.points.length / 4) * 2;
            float sx = px(water.points[mid]), sy = py(water.points[mid + 1]);
            if (sx < 0 || sx > getWidth() || sy < 0 || sy > getHeight()) continue;
            float width = paint.measureText(water.name);
            RectF box = new RectF(sx + 5 * density, sy - 12 * density, sx + width + 8 * density, sy + 3 * density);
            if (box.right > getWidth() - 4 || box.left < 4) continue;
            boolean overlap = false;
            for (int i = 0; i < placeLabelCount; i++) if (RectF.intersects(occupied.get(i), box)) { overlap = true; break; }
            if (!overlap) for (RectF used : streetRects) if (RectF.intersects(used, box)) { overlap = true; break; }
            if (overlap) continue;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.4f * density);
            paint.setColor(Color.argb(210, 5, 28, 38));
            c.drawText(water.name, sx + 5 * density, sy - 3 * density, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(174, 225, 242));
            c.drawText(water.name, sx + 5 * density, sy - 3 * density, paint);
            if (++shown >= 22) break;
        }
        paint.setTypeface(Typeface.DEFAULT);
    }

    void drawRoads(Canvas c) {
        if (region.roadGroups != null && region.roadGroups.length > 0) {
            drawRoadGroups(c);
            return;
        }
        float[] r = region.roads;
        if (r == null || r.length < 4) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.argb(compact ? 48 : 105, 30, 38, 36));
        paint.setStrokeWidth((compact ? 1.7f : 3.2f) * density);
        path.reset();
        for (int i = 0; i + 3 < r.length; i += 4) {
            path.moveTo(px(r[i]), py(r[i + 1]));
            path.lineTo(px(r[i + 2]), py(r[i + 3]));
        }
        c.drawPath(path, paint);
        paint.setColor(Color.argb(compact ? 120 : 175, 226, 218, 194));
        paint.setStrokeWidth((compact ? 0.55f : 1.05f) * density);
        c.drawPath(path, paint);
    }

    private static final String[] ROAD_ORDER = {
        "track", "path", "steps", "cycleway", "footway", "pedestrian", "service",
        "living_street", "residential", "unclassified", "road",
        "tertiary", "tertiary_link", "secondary", "secondary_link",
        "primary", "primary_link", "trunk", "trunk_link"
    };

    private Region.RoadGroup groupOf(String kind) {
        if (region.roadGroups == null) return null;
        for (Region.RoadGroup g : region.roadGroups) if (g.kind.equals(kind)) return g;
        return null;
    }

    private boolean roadVisible(String kind, float zoom) {
        switch (kind) {
            case "trunk": case "trunk_link": case "primary": case "primary_link":
            case "secondary": case "secondary_link": case "tertiary": case "tertiary_link":
                return zoom >= 1.0f;
            case "unclassified": case "residential": case "living_street": case "road":
                return zoom >= 2.2f;
            case "service": case "pedestrian":
                return zoom >= 3.2f;
            default:
                return zoom >= 5.0f;
        }
    }

    private float roadWidthMeters(String kind) {
        switch (kind) {
            case "trunk": case "trunk_link": return 10f;
            case "primary": case "primary_link": return 8f;
            case "secondary": case "secondary_link": return 6.5f;
            case "tertiary": case "tertiary_link": return 5.5f;
            case "residential": case "unclassified": case "road": case "living_street":
                return 4.6f;
            case "service": case "pedestrian": return 3.4f;
            case "footway": case "cycleway": return 2.1f;
            case "path": return 1.6f;
            case "steps": return 1.2f;
            default: return 3f;
        }
    }

    private float roadMinPx(String kind) {
        switch (kind) {
            case "trunk": case "primary": case "secondary": case "tertiary":
            case "trunk_link": case "primary_link": case "secondary_link": case "tertiary_link":
                return 1.1f * density;
            case "footway": case "path": case "steps": case "cycleway":
                return 0.55f * density;
            default:
                return 0.7f * density;
        }
    }

    private int roadFillColor(String kind, boolean compact) {
        if (compact) return Color.argb(150, 224, 216, 194);
        switch (kind) {
            case "trunk": case "trunk_link": return Color.rgb(235, 224, 190);
            case "primary": case "primary_link": return Color.rgb(223, 211, 182);
            case "secondary": case "secondary_link": return Color.rgb(210, 200, 175);
            case "tertiary": case "tertiary_link": return Color.rgb(198, 192, 170);
            case "residential": case "unclassified": case "road": case "living_street":
                return Color.rgb(184, 182, 164);
            case "service": case "pedestrian": return Color.rgb(166, 168, 156);
            default: return Color.rgb(138, 146, 140);
        }
    }

    void drawRoadGroups(Canvas c) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        float viewW = getWidth(), viewH = getHeight();
        float margin = 40 * density;
        for (String kind : ROAD_ORDER) {
            Region.RoadGroup group = groupOf(kind);
            if (group == null || !roadVisible(kind, zoom)) continue;
            float width = Math.max(roadWidthMeters(kind) * scale, roadMinPx(kind));
            path.reset();
            float[] s = group.segs;
            boolean any = false;
            for (int i = 0; i + 3 < s.length; i += 4) {
                float x0 = px(s[i]), y0 = py(s[i + 1]), x1 = px(s[i + 2]), y1 = py(s[i + 3]);
                if ((x0 < -margin && x1 < -margin) || (x0 > viewW + margin && x1 > viewW + margin)) continue;
                if ((y0 < -margin && y1 < -margin) || (y0 > viewH + margin && y1 > viewH + margin)) continue;
                path.moveTo(x0, y0);
                path.lineTo(x1, y1);
                any = true;
            }
            if (!any) continue;
            paint.setColor(Color.argb(compact ? 70 : 135, 20, 30, 32));
            paint.setStrokeWidth(width + (compact ? 0.7f : 1.8f) * density);
            c.drawPath(path, paint);
            paint.setColor(roadFillColor(kind, compact));
            paint.setStrokeWidth(width);
            c.drawPath(path, paint);
        }
    }

    void drawBranches(Canvas c) {
        if (region.branches == null || region.branches.isEmpty()) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (float[] branch : region.branches) {
            if (branch.length < 4) continue;
            path.reset();
            path.moveTo(px(branch[0]), py(branch[1]));
            for (int i = 2; i + 1 < branch.length; i += 2) path.lineTo(px(branch[i]), py(branch[i + 1]));
            paint.setColor(Color.argb(compact ? 70 : 170, 34, 48, 46));
            paint.setStrokeWidth((compact ? 2.6f : 4.6f) * density);
            c.drawPath(path, paint);
            paint.setColor(Color.rgb(255, 214, 120));
            paint.setStrokeWidth((compact ? 1.05f : 1.9f) * density);
            c.drawPath(path, paint);
        }
    }

    void drawPlaces(Canvas c) {
        int occupiedCount = 0;
        paint.setTypeface(boldTypeface);
        for (Region.Place p : region.places) {
            if (compact && !p.major()) continue;
            if (!compact && !placeVisible(p)) continue;
            float sx = px(p.x), sy = py(p.z);
            if (sx < -20 || sx > getWidth() + 20 || sy < -20 || sy > getHeight() + 20) continue;
            int size = compact ? 7 : placeSize(p);
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
            paint.setColor(placeFillColor(p));
            if (compact) paint.setColor(Color.argb(210, 245, 241, 218));
            c.drawCircle(sx, sy, placeDot(p) * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f * density);
            paint.setColor(Color.argb(190, 12, 27, 30));
            c.drawText(p.name, tx, ty, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(compact ? Color.rgb(240, 238, 216) : placeTextColor(p));
            c.drawText(p.name, tx, ty, paint);
        }
        placeLabelCount = occupiedCount;
        paint.setTypeface(Typeface.DEFAULT);
    }

    private boolean placeVisible(Region.Place p) {
        if (p.major()) return true;
        if ("village".equals(p.kind)) return zoom >= 2.0f;
        return zoom >= 3.0f;
    }

    private int placeSize(Region.Place p) {
        if ("city".equals(p.kind)) return 13;
        if ("town".equals(p.kind) || "sight".equals(p.kind)) return 12;
        if ("village".equals(p.kind)) return 10;
        return 9;
    }

    private float placeDot(Region.Place p) {
        if ("city".equals(p.kind)) return 3.0f;
        if ("town".equals(p.kind) || "sight".equals(p.kind)) return 2.7f;
        if ("village".equals(p.kind)) return 2.3f;
        return 1.9f;
    }

    private int placeFillColor(Region.Place p) {
        if ("city".equals(p.kind) || "town".equals(p.kind) || "sight".equals(p.kind)) return Color.rgb(255, 238, 185);
        return Color.rgb(214, 222, 205);
    }

    private int placeTextColor(Region.Place p) {
        if (p.major()) return Color.rgb(255, 247, 217);
        return Color.rgb(224, 230, 214);
    }

    void drawStreetLabels(Canvas c) {
        visibleStreets.clear();
        streetRects.clear();
        if (compact || zoom < 2.0f || region.streets == null || region.streets.length == 0) return;
        paint.setTypeface(boldTypeface);
        paint.setTextSize((zoom >= 5.5f ? 10.5f : zoom >= 3.5f ? 9.8f : 9.0f) * density);
        for (Region.Street street : region.streets) {
            float[] g = region.globalForStreet(street);
            float sx = px(g[0]), sy = py(g[1]);
            if (sx < -20 || sx > getWidth() + 20 || sy < -20 || sy > getHeight() + 20) continue;
            float width = paint.measureText(street.name);
            float tx = sx + 6 * density, ty = sy - 5 * density;
            RectF box = new RectF(tx - 4 * density, ty - 11 * density, tx + width + 4 * density, ty + 4 * density);
            if (box.left < 3 || box.right > getWidth() - 3 || box.top < 2 || box.bottom > getHeight() - 2) continue;
            boolean overlap = false;
            for (int i = 0; i < placeLabelCount; i++) if (RectF.intersects(occupied.get(i), box)) { overlap = true; break; }
            if (!overlap) for (RectF used : streetRects) if (RectF.intersects(used, box)) { overlap = true; break; }
            if (overlap) continue;
            visibleStreets.add(street);
            streetRects.add(box);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(255, 198, 81));
            c.drawCircle(sx, sy, 2.4f * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f * density);
            paint.setColor(Color.argb(205, 9, 28, 31));
            c.drawText(street.name, tx, ty, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(247, 244, 221));
            c.drawText(street.name, tx, ty, paint);
            if (visibleStreets.size() >= 55) break;
        }
        paint.setTypeface(Typeface.DEFAULT);
    }

    Region.Street streetAt(float screenX, float screenY) {
        for (int i = 0; i < visibleStreets.size(); i++) {
            if (streetRects.get(i).contains(screenX, screenY)) return visibleStreets.get(i);
            Region.Street street = visibleStreets.get(i);
            float[] g = region.globalForStreet(street);
            float sx = px(g[0]), sy = py(g[1]);
            if (Math.hypot(screenX - sx, screenY - sy) <= 13 * density) return street;
        }
        return null;
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
                float next = Math.max(1, Math.min(200, zoom * span / previousSpan)), ratio = next / zoom;
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
                if (pick != null) pick.at(wx, wz, streetAt(e.getX(), e.getY()));
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            dragged = true; getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
