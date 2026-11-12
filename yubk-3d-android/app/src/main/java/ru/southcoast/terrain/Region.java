package ru.southcoast.terrain;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.*;
import java.io.*;
import java.util.*;

/** Offline regional route model for the whole South Coast. Coordinates are metric, centred on the region origin. */
final class Region {
    static final float REGION_ORIGIN_LAT = 44.55f;
    static final float REGION_ORIGIN_LON = 33.95f;
    static final float REGION_SX = 79461.5625f;
    static final float REGION_SZ = 111122.9844f;
    static final class Place {
        final String name, kind;
        final float x, z, routeM;
        Place(JSONObject j) throws JSONException {
            name = j.getString("name");
            kind = j.getString("kind");
            x = (float) j.getDouble("x");
            z = (float) j.getDouble("z");
            routeM = (float) j.getDouble("route_m");
        }
        boolean major() {
            return "city".equals(kind) || "town".equals(kind) || "sight".equals(kind);
        }
    }

    static final class Tile {
        final String id, name;
        final float startM, endM, x0, z0, x1, z1, originLat, originLon;
        Tile(JSONObject j) throws JSONException {
            id = j.getString("id");
            name = j.getString("name");
            startM = (float) j.getDouble("start_m");
            endM = (float) j.getDouble("end_m");
            x0 = (float) j.getDouble("x0");
            z0 = (float) j.getDouble("z0");
            x1 = (float) j.getDouble("x1");
            z1 = (float) j.getDouble("z1");
            JSONArray origin = j.getJSONArray("origin");
            originLat = (float) origin.getDouble(0);
            originLon = (float) origin.getDouble(1);
        }
    }

    static final class Candidate {
        final float x, z, heading, routeM, localX, localZ;
        final Tile tile;
        Candidate(float x, float z, float heading, float routeM, float localX, float localZ, Tile tile) {
            this.x = x; this.z = z; this.heading = heading; this.routeM = routeM;
            this.localX = localX; this.localZ = localZ; this.tile = tile;
        }
    }

    static final class Street {
        final String name, tileId;
        final float lx, lz, heading;
        String city = "ЮБК";
        float cityRouteM;
        Street(JSONObject j) throws JSONException {
            name = j.getString("name");
            tileId = j.optString("tile", "tile_00");
            lx = (float) j.getDouble("lx");
            lz = (float) j.getDouble("lz");
            heading = (float) j.getDouble("heading");
        }
    }

    static final class Water {
        final String name, kind;
        final float width;
        final float[] points;
        Water(JSONObject j) throws JSONException {
            name = j.getString("name");
            kind = j.optString("kind", "stream");
            width = (float) j.optDouble("width", 4.0);
            JSONArray pointsJson = j.getJSONArray("points");
            points = new float[pointsJson.length() * 2];
            for (int i = 0; i < pointsJson.length(); i++) {
                JSONArray p = pointsJson.getJSONArray(i);
                points[i * 2] = (float) p.getDouble(0);
                points[i * 2 + 1] = (float) p.getDouble(1);
            }
        }
    }

    final String name;
    final float originLat, originLon, sx, sz;
    final float worldLeft, worldTop, worldRight, worldBottom;
    final float[] route;
    final float[] roads;
    final Place[] places;
    final Street[] streets;
    final Water[] water;
    final Tile[] tiles;
    final Bitmap map;

    Region(AssetManager assets) throws IOException {
        try (InputStream in = assets.open("route/region.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            JSONObject j = new JSONObject(out.toString("UTF-8"));
            name = j.getString("name");
            JSONArray origin = j.getJSONArray("origin");
            originLat = (float) origin.getDouble(0);
            originLon = (float) origin.getDouble(1);
            sx = (float) j.getDouble("sx");
            sz = (float) j.getDouble("sz");
            JSONArray bounds = j.getJSONArray("bounds");
            float west = (float) bounds.getDouble(0), south = (float) bounds.getDouble(1);
            float east = (float) bounds.getDouble(2), north = (float) bounds.getDouble(3);
            worldLeft = (west - originLon) * sx;
            worldRight = (east - originLon) * sx;
            worldTop = (originLat - north) * sz;
            worldBottom = (originLat - south) * sz;
            JSONArray routeJson = j.getJSONArray("route");
            route = new float[routeJson.length() * 4];
            for (int i = 0; i < routeJson.length(); i++) {
                JSONArray p = routeJson.getJSONArray(i);
                route[i * 4] = (float) p.getDouble(0);
                route[i * 4 + 1] = (float) p.getDouble(1);
                route[i * 4 + 2] = (float) p.getDouble(2);
                route[i * 4 + 3] = (float) p.getDouble(3);
            }
            JSONArray placeJson = j.getJSONArray("places");
            places = new Place[placeJson.length()];
            for (int i = 0; i < places.length; i++) places[i] = new Place(placeJson.getJSONObject(i));
            JSONArray tileJson = j.getJSONArray("tiles");
            tiles = new Tile[tileJson.length()];
            for (int i = 0; i < tiles.length; i++) tiles[i] = new Tile(tileJson.getJSONObject(i));
            JSONArray roadsJson = j.optJSONArray("roads");
            int roadCount = 0;
            if (roadsJson != null) for (int i = 0; i < roadsJson.length(); i++) roadCount += roadsJson.getJSONArray(i).length();
            roads = new float[roadCount * 4];
            int roadAt = 0;
            if (roadsJson != null) {
                for (int i = 0; i < roadsJson.length(); i++) {
                    JSONArray way = roadsJson.getJSONArray(i);
                    for (int s = 0; s < way.length(); s++) {
                        JSONArray seg = way.getJSONArray(s);
                        roads[roadAt * 4] = (float) seg.getDouble(0);
                        roads[roadAt * 4 + 1] = (float) seg.getDouble(1);
                        roads[roadAt * 4 + 2] = (float) seg.getDouble(2);
                        roads[roadAt * 4 + 3] = (float) seg.getDouble(3);
                        roadAt++;
                    }
                }
            }
            JSONArray streetJson = j.optJSONArray("streets");
            int streetCount = streetJson == null ? 0 : streetJson.length();
            streets = new Street[streetCount];
            for (int i = 0; i < streetCount; i++) streets[i] = new Street(streetJson.getJSONObject(i));
            JSONArray waterJson = j.optJSONArray("water");
            int waterCount = waterJson == null ? 0 : waterJson.length();
            water = new Water[waterCount];
            for (int i = 0; i < waterCount; i++) water[i] = new Water(waterJson.getJSONObject(i));
            assignStreetCities();
            try (InputStream image = assets.open(j.getString("map"))) {
                map = BitmapFactory.decodeStream(image);
            }
        } catch (JSONException e) {
            throw new IOException("Invalid regional route data", e);
        }
    }

    private void assignStreetCities() {
        for (Street street : streets) {
            Tile tile = tileById(street.tileId);
            if (tile == null) continue;
            float gx = street.lx + (tile.originLon - originLon) * sx;
            float gz = street.lz + (originLat - tile.originLat) * sz;
            Place nearestTown = null, nearest = null;
            float townD = Float.MAX_VALUE, anyD = Float.MAX_VALUE;
            for (Place place : places) {
                float d = (place.x - gx) * (place.x - gx) + (place.z - gz) * (place.z - gz);
                if (d < anyD) { anyD = d; nearest = place; }
                boolean town = "city".equals(place.kind) || "town".equals(place.kind);
                if (town && d < townD) { townD = d; nearestTown = place; }
            }
            Place chosen = nearestTown != null && townD < 14_000f * 14_000f ? nearestTown : nearest;
            if (chosen != null) {
                street.city = chosen.name;
                street.cityRouteM = chosen.routeM;
            }
        }
    }

    Tile tileForRouteM(float routeM) {
        for (Tile tile : tiles) if (routeM >= tile.startM && routeM <= tile.endM) return tile;
        return null;
    }

    Tile tileById(String id) {
        for (Tile tile : tiles) if (tile.id.equals(id)) return tile;
        return null;
    }

    Place placeAt(float x, float z, float maxMeters) {
        Place best = null;
        float bestD = maxMeters * maxMeters;
        for (Place p : places) {
            float d = (p.x - x) * (p.x - x) + (p.z - z) * (p.z - z);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    Place nearestPlaceByRoute(float routeM, float maxMeters) {
        Place best = null;
        float bestD = maxMeters;
        for (Place p : places) {
            float d = Math.abs(p.routeM - routeM);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    /** Projects a tap onto the highlighted highway and returns a loadable tile point. */
    Candidate candidate(float x, float z) {
        float bestD = 900f * 900f;
        float bx = 0, bz = 0, bh = 0, bm = 0;
        float[] r = route;
        for (int i = 0; i + 7 < r.length; i += 4) {
            float ax = r[i], az = r[i + 1], cx = r[i + 4], cz = r[i + 5];
            float vx = cx - ax, vz = cz - az;
            float len = vx * vx + vz * vz;
            float t = len == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * vx + (z - az) * vz) / len));
            float px = ax + t * vx, pz = az + t * vz;
            float d = (x - px) * (x - px) + (z - pz) * (z - pz);
            if (d < bestD) {
                bestD = d;
                bx = px; bz = pz;
                bh = r[i + 2] + (r[i + 6] - r[i + 2]) * t;
                bm = r[i + 3] + (r[i + 7] - r[i + 3]) * t;
            }
        }
        Tile tile = tileForRouteM(bm);
        if (tile == null) return null;
        float localX = bx + (originLon - tile.originLon) * sx;
        float localZ = bz + (tile.originLat - originLat) * sz;
        return new Candidate(bx, bz, bh, bm, localX, localZ, tile);
    }

    /** Nearest candidate when the player walks across a tile edge. */
    Candidate candidateForGlobal(float x, float z) {
        return candidate(x, z);
    }

    String describe(Candidate c) {
        Place p = nearestPlaceByRoute(c.routeM, 7000);
        String place = p == null ? "Южнобережное шоссе" : p.name;
        return String.format(Locale.ROOT, "%s · %.0f км от Севастополя\n%s", c.tile.name, c.routeM / 1000f, place);
    }

    String location(float x, float z, Tile tile) {
        Candidate c = candidate(x, z);
        float routeM = c == null ? 0 : c.routeM;
        Place p = nearestPlaceByRoute(routeM, 8000);
        String place = p == null ? "Южнобережное шоссе" : p.name;
        String tileName = tile == null ? "ЮБК" : tile.name;
        return String.format(Locale.ROOT, "%s\n%s · %.0f км", tileName, place, routeM / 1000f);
    }
}
