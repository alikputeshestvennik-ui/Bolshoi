package ru.southcoast.terrain;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.opengl.GLSurfaceView;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.Locale;
import javax.microedition.khronos.egl.*;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity {
    private Region region;
    private TerrainView terrain;
    private RegionMapView map, miniMap;
    private FrameLayout mapScreen, walkScreen;
    private TextView location, loading;
    private HudButton speedButton;
    private Region.Tile currentTile;
    private long lastLocationUpdate;
    private boolean hasWalked;
    private AlertDialog teleportDialog;
    private volatile boolean switchingTile;
    private int speedIndex = 2;
    private static final float[] SPEEDS = {5f, 40f / 3.6f, 70f / 3.6f};
    private static final String[] SPEED_LABELS = {"5 м/с", "40 км/ч", "70 км/ч"};

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }

    private TextView label(String value, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(236, 247, 237));
        t.setPadding(dp(13), dp(7), dp(13), dp(7));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private GradientDrawable glass(int alpha, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.argb(alpha, 14, 36, 40));
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), Color.argb(90, 155, 211, 198));
        return d;
    }

    private String speedText() { return SPEED_LABELS[speedIndex]; }

    private void applySpeed() {
        if (terrain != null && terrain.scene != null) terrain.scene.moveSpeed = SPEEDS[speedIndex];
        if (speedButton != null) speedButton.setLabel(speedText());
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        applySpeed();
        Toast.makeText(this, "Скорость: " + speedText(), Toast.LENGTH_SHORT).show();
    }

    private void confirmTeleport(float x, float z) {
        Region.Candidate candidate = region.candidate(x, z);
        if (candidate == null) {
            Toast.makeText(this, "Коснитесь линии Южнобережного шоссе", Toast.LENGTH_LONG).show();
            return;
        }
        if (candidate.tile == null) {
            Toast.makeText(this, "Этот участок ещё готовится", Toast.LENGTH_LONG).show();
            return;
        }
        Region.Place place = region.nearestPlaceByRoute(candidate.routeM, 7000);
        map.select(new float[]{candidate.x, candidate.z});
        String description = (place == null ? "Южнобережное шоссе" : place.name)
                + "\n" + String.format(Locale.ROOT, "%.0f км от Севастополя · %s", candidate.routeM / 1000f, candidate.tile.name)
                + "\n\nПерейти на этот участок?";
        teleportDialog = new AlertDialog.Builder(this)
                .setTitle("Переход на маршрут")
                .setMessage(description)
                .setPositiveButton("Перейти", (d, which) -> openCandidate(candidate, true))
                .setNegativeButton("Отмена", (d, which) -> map.select(null))
                .setOnCancelListener(d -> map.select(null))
                .create();
        final AlertDialog shown = teleportDialog;
        shown.setOnDismissListener(d -> { if (teleportDialog == shown) { teleportDialog = null; map.select(null); } });
        teleportDialog.show();
    }

    private void openCandidate(Region.Candidate candidate, boolean showProgress) {
        if (candidate == null || candidate.tile == null || terrain == null) return;
        if (switchingTile) return;
        switchingTile = true;
        if (showProgress && loading != null) {
            loading.setText("Загрузка участка\n" + candidate.tile.name);
            loading.setVisibility(View.VISIBLE);
        }
        terrain.queueEvent(() -> {
            try {
                WalkScene scene = new WalkScene(getAssets(), "route/" + candidate.tile.id);
                scene.x = candidate.localX;
                scene.z = candidate.localZ;
                scene.heading = candidate.heading;
                scene.pitch = 0;
                scene.forward = 0;
                scene.moveSpeed = SPEEDS[speedIndex];
                terrain.setScene(scene);
                currentTile = candidate.tile;
                runOnUiThread(() -> {
                    hasWalked = true;
                    switchingTile = false;
                    if (loading != null) loading.setVisibility(View.GONE);
                    map.select(null);
                    mapScreen.setVisibility(View.GONE);
                    walkScreen.setVisibility(View.VISIBLE);
                    updateLocation(true);
                    terrain.requestRender();
                });
            } catch (IOException | RuntimeException e) {
                switchingTile = false;
                runOnUiThread(() -> {
                    if (loading != null) loading.setVisibility(View.GONE);
                    Toast.makeText(this, "Не удалось загрузить участок: " + candidate.tile.id, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showMap() {
        if (terrain != null) terrain.queueEvent(() -> {
            if (terrain.scene != null) { terrain.scene.forward = 0; terrain.scene.lastFrame = 0; }
        });
        if (mapScreen != null) mapScreen.setVisibility(View.VISIBLE);
        if (walkScreen != null) walkScreen.setVisibility(View.GONE);
    }

    private void showPlaces() {
        final String[] names = new String[region.places.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = region.places[i].name + "  ·  " + String.format(Locale.ROOT, "%.0f км", region.places[i].routeM / 1000f);
        }
        new AlertDialog.Builder(this).setTitle("Места ЮБК").setItems(names, (d, i) -> {
            Region.Place p = region.places[i];
            Region.Candidate c = region.candidate(p.x, p.z);
            if (c == null) {
                Toast.makeText(this, "Рядом с этим местом нет загружаемого участка шоссе", Toast.LENGTH_LONG).show();
            } else {
                openCandidate(c, true);
            }
        }).setNegativeButton("Закрыть", null).show();
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        root.setOnApplyWindowInsetsListener((v, i) -> {
            v.setPadding(i.getSystemWindowInsetLeft(), i.getSystemWindowInsetTop(), i.getSystemWindowInsetRight(), i.getSystemWindowInsetBottom());
            return i;
        });
        try {
            region = new Region(getAssets());
            terrain = new TerrainView();
            mapScreen = new FrameLayout(this);
            walkScreen = new FrameLayout(this);

            map = new RegionMapView(this, region, this::confirmTeleport, false);
            mapScreen.addView(map, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout titleCard = column();
            titleCard.setBackground(glass(218, 18));
            titleCard.setPadding(dp(7), dp(3), dp(7), dp(7));
            TextView title = label("ЮБК · 3D", 23, true);
            title.setPadding(dp(9), dp(5), dp(9), 0);
            titleCard.addView(title);
            TextView subtitle = label("Севастополь — Алушта · 121 км", 12, false);
            subtitle.setTextColor(Color.rgb(255, 211, 126));
            titleCard.addView(subtitle);
            TextView hint = label("Коснитесь линии шоссе, чтобы выбрать участок", 11, false);
            hint.setTextColor(Color.rgb(201, 224, 215));
            titleCard.addView(hint);
            FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            titleParams.setMargins(dp(10), dp(10), dp(10), 0);
            mapScreen.addView(titleCard, titleParams);

            LinearLayout mapControls = row();
            mapControls.setGravity(Gravity.CENTER);
            mapControls.setPadding(dp(6), dp(4), dp(6), dp(4));
            mapControls.setBackground(glass(225, 20));
            HudButton route = new HudButton(this, HudButton.ROUTE, "Весь маршрут");
            route.setOnClickListener(v -> map.resetView());
            HudButton places = new HudButton(this, HudButton.PLACES, "Места");
            places.setOnClickListener(v -> showPlaces());
            HudButton start = new HudButton(this, HudButton.PLAY, "Продолжить");
            start.setAccent(true);
            start.setOnClickListener(v -> {
                if (!hasWalked) Toast.makeText(this, "Сначала выберите участок на карте", Toast.LENGTH_SHORT).show();
                else { mapScreen.setVisibility(View.GONE); walkScreen.setVisibility(View.VISIBLE); terrain.requestRender(); }
            });
            mapControls.addView(route, new LinearLayout.LayoutParams(dp(104), dp(58)));
            mapControls.addView(places, new LinearLayout.LayoutParams(dp(86), dp(58)));
            mapControls.addView(start, new LinearLayout.LayoutParams(dp(104), dp(58)));
            FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            controlParams.setMargins(0, 0, 0, dp(10));
            mapScreen.addView(mapControls, controlParams);

            location = label("", 12, false);
            location.setBackground(glass(218, 16));
            location.setMaxLines(3);
            FrameLayout.LayoutParams locationParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            locationParams.setMargins(dp(10), dp(10), dp(10), 0);

            FrameLayout viewport = new FrameLayout(this);
            viewport.addView(terrain, new FrameLayout.LayoutParams(-1, -1));
            walkScreen.addView(viewport, new FrameLayout.LayoutParams(-1, -1));
            viewport.addView(location, locationParams);

            FrameLayout miniCard = new FrameLayout(this);
            miniCard.setBackground(glass(225, 15));
            miniCard.setPadding(dp(2), dp(2), dp(2), dp(2));
            miniMap = new RegionMapView(this, region, null, true);
            miniCard.addView(miniMap, new FrameLayout.LayoutParams(dp(126), dp(78)));
            FrameLayout.LayoutParams miniParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            miniParams.setMargins(0, dp(10), dp(10), 0);
            viewport.addView(miniCard, miniParams);
            miniCard.setOnClickListener(v -> showMap());

            LinearLayout walkControls = row();
            walkControls.setGravity(Gravity.CENTER);
            walkControls.setPadding(dp(5), dp(4), dp(5), dp(4));
            walkControls.setBackground(glass(225, 22));
            HudButton back = new HudButton(this, HudButton.BACK, "Назад");
            back.setOnClickListener(v -> {});
            bindHold(back, -1);
            HudButton forward = new HudButton(this, HudButton.FORWARD, "Вперёд");
            forward.setAccent(true);
            forward.setOnClickListener(v -> {});
            bindHold(forward, 1);
            speedButton = new HudButton(this, HudButton.SPEED, speedText());
            speedButton.setOnClickListener(v -> cycleSpeed());
            HudButton mapButton = new HudButton(this, HudButton.MAP, "Карта");
            mapButton.setOnClickListener(v -> showMap());
            walkControls.addView(back, new LinearLayout.LayoutParams(dp(76), dp(58)));
            walkControls.addView(forward, new LinearLayout.LayoutParams(dp(88), dp(58)));
            walkControls.addView(speedButton, new LinearLayout.LayoutParams(dp(92), dp(58)));
            walkControls.addView(mapButton, new LinearLayout.LayoutParams(dp(78), dp(58)));
            FrameLayout.LayoutParams walkControlParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            walkControlParams.setMargins(0, 0, 0, dp(10));
            walkScreen.addView(walkControls, walkControlParams);

            loading = label("Загрузка участка…", 15, true);
            loading.setGravity(Gravity.CENTER);
            loading.setBackground(glass(238, 18));
            loading.setPadding(dp(24), dp(18), dp(24), dp(18));
            loading.setClickable(true);
            loading.setFocusable(true);
            loading.setElevation(dp(12));
            loading.setVisibility(View.GONE);

            TextView source = label("© OpenStreetMap · Skadi/SRTM", 9, false);
            source.setTextColor(Color.argb(180, 229, 241, 226));
            FrameLayout.LayoutParams sourceParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            sourceParams.setMargins(0, 0, dp(8), dp(72));
            walkScreen.addView(source, sourceParams);

            root.addView(walkScreen, new FrameLayout.LayoutParams(-1, -1));
            root.addView(mapScreen, new FrameLayout.LayoutParams(-1, -1));
            root.addView(loading, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            walkScreen.setVisibility(View.GONE);
            setContentView(root);
            root.requestApplyInsets();
        } catch (IOException e) {
            TextView error = label("Не удалось открыть данные ЮБК. Установите APK заново.", 16, true);
            root.addView(error, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            setContentView(root);
        }
    }

    private void bindHold(HudButton button, int direction) {
        button.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                button.pressed = true; button.invalidate();
                terrain.queueEvent(() -> { if (terrain.scene != null) { terrain.scene.forward = direction; terrain.requestRender(); } });
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                button.pressed = false; button.invalidate();
                terrain.queueEvent(() -> { if (terrain.scene != null) { terrain.scene.forward = 0; terrain.requestRender(); } });
                if (action == MotionEvent.ACTION_UP) button.performClick();
            }
            return true;
        });
    }

    @Override public void onBackPressed() {
        if (walkScreen != null && walkScreen.getVisibility() == View.VISIBLE) showMap();
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        super.onPause();
        if (terrain != null) {
            terrain.queueEvent(() -> { if (terrain.scene != null) { terrain.scene.forward = 0; terrain.scene.lastFrame = 0; } });
            terrain.onPause();
        }
    }

    @Override protected void onResume() { super.onResume(); if (terrain != null) terrain.onResume(); }

    private void updateLocation() { updateLocation(false); }

    private void updateLocation(boolean force) {
        if (terrain == null || terrain.scene == null) return;
        long now = System.nanoTime();
        if (!force && now - lastLocationUpdate < 250_000_000L) return;
        lastLocationUpdate = now;
        WalkScene s = terrain.scene;
        float gx = s.globalX(), gz = s.globalZ(), h = s.heading;
        String text = region.location(gx, gz, currentTile);
        runOnUiThread(() -> {
            if (location != null) location.setText(text);
            if (map != null) map.position(gx, gz, h);
            if (miniMap != null) miniMap.position(gx, gz, h);
        });
    }

    class TerrainView extends GLSurfaceView implements GLSurfaceView.Renderer {
        volatile WalkScene scene;
        float aspect = 1, lastX, lastY;
        volatile int glError = 0, frames = 0;

        TerrainView() {
            super(MainActivity.this);
            setEGLConfigChooser((egl, display) -> {
                for (int depth : new int[]{24, 16}) {
                    int[] spec = {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, 1, EGL10.EGL_NONE};
                    EGLConfig[] configs = new EGLConfig[1];
                    int[] n = new int[1];
                    if (egl.eglChooseConfig(display, spec, configs, 1, n) && n[0] > 0) return configs[0];
                }
                throw new IllegalArgumentException("No GLES depth buffer configuration");
            });
            setRenderer(this);
            setRenderMode(RENDERMODE_WHEN_DIRTY);
        }

        void setScene(WalkScene value) { scene = value; requestRender(); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && e.getPointerCount() == 1 && scene != null) {
                float dx = (x - lastX) / Math.max(1, getWidth()) * 120;
                float dy = (y - lastY) / Math.max(1, getHeight()) * 65;
                queueEvent(() -> { if (scene != null) { scene.heading += dx; scene.pitch = Math.max(-50, Math.min(50, scene.pitch - dy)); requestRender(); } });
            }
            lastX = x; lastY = y;
            return true;
        }

        public void onSurfaceCreated(GL10 gl, EGLConfig c) { gl.glEnable(GL10.GL_DEPTH_TEST); }
        public void onSurfaceChanged(GL10 gl, int w, int h) { gl.glViewport(0, 0, w, h); aspect = (float) w / Math.max(1, h); }
        public void onDrawFrame(GL10 gl) {
            WalkScene s = scene;
            if (s == null) {
                gl.glClearColor(.62f, .77f, .86f, 1);
                gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
                return;
            }
            s.draw(gl, aspect);
            glError = gl.glGetError();
            frames++;
            updateLocation();
            if (s.forward != 0) requestRender();
            if (s.nearEdge() && !switchingTile) {
                switchingTile = true;
                runOnUiThread(() -> {
                    Region.Candidate edge = region.candidateForGlobal(s.globalX(), s.globalZ());
                    if (edge != null && edge.tile != null && !edge.tile.id.equals(s.tileId)) {
                        switchingTile = false;
                        openCandidate(edge, false);
                    } else switchingTile = false;
                });
            }
        }
    }
}
