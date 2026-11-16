# YUBK 3D · Godot Android 1.5

## Regional offline coverage

The 3D world covers the complete map rectangle, 33.35–34.50 E and
44.35–44.75 N: Sevastopol, Balaklava, the coast through Alupka, Koreiz,
Yalta and Gurzuf to Alushta, and the Baidar Valley. Teleports select local
streets within 160 m of the tap, or unobstructed land. They do not select
points on the highlighted highway. The sea and building interiors are excluded.

`python3 tools/fetch_region.py` caches ten complete OSM building extracts
(including multipolygon outlines and courtyards) and the two SRTM tiles.
A missing or incomplete extract stops preparation. `python3 tools/sync_assets.py`
bakes the full area using those buildings and the existing complete regional
street extract. No highway-distance filter or building count cap is applied.
Building height uses OSM `height` / `building:levels`, with a 6 m default;
these are simplified footprint extrusions, not photogrammetry or measured facades.
OSM omissions remain omissions in the scene.

4096 m pages contain elevation, collisions and street navigation. All geometry
shares one metric origin; 256 m geometry cells are split into jobs of at most
18,000 vertices. Neighboring page manifests are opened on demand. Crossing a
page boundary preserves the streamer and its resident geometry, including in
both travel directions. Regional teleports reuse the streamer as well.
A coarse terrain surface covers the entire region behind nearby detail.

One background decoder and one mesh upload per frame serve the whole region.
Resident and in-flight detailed geometry together stay below 480,000 vertices,
with at most 96 resident jobs. Obsolete loads are discarded after rapid movement.
The 2D map pauses 3D rendering and movement, uses packed road batches and
Godot's low CPU usage mode. Rendering is capped at 30 FPS. The expanded build
still needs thermal and stability verification on the target phone.

Road ribbons use the existing grading and junction repair: continuous ground,
separate asphalt and shoulders, sealed retaining faces and pavement height data.
Rivers follow the DEM triangles. Bridges retain an approximate raised surface;
this is not a reconstruction of complete interchanges or underground tunnels.

## Verification and APK

Run `python3 -m unittest discover -s tests -p 'test_*.py' -v`.
After preparation, run `python3 tools/validate_region.py`. It verifies that
all spatial pages and mesh jobs exist and checks nearby roads and buildings in
nine settlements, including Alupka, Koreiz and Orlinoe in the Baidar Valley.

The **Build YUBK 3D Godot APK** workflow checks scripts with Godot 4.4.1,
prepares the full region, validates coverage, renders street and overhead views
in eight locations, checks local teleports and actual movement across a page
boundary in both directions, then exports the arm64 APK. The coverage report,
screenshots and log are uploaded as `yubk-godot-visual-checks`.
`--legacy` on `sync_assets.py` retains the old route-format preparation for
comparison; regional APK exports exclude the old `tile_*` resources.

## BeamNG.Earth reference

Reviewed: https://github.com/TecnaGamer/BeamNG.Earth (MIT).
Its client depends on BeamNG.drive APIs. This app uses offline preparation,
spatial streaming and bounded background decoding in Godot. No BeamNG code,
game assets or services are embedded. Satellite imagery and BeamNG vehicle
physics are not implemented.

Data: © OpenStreetMap contributors (ODbL 1.0), Mapzen/Tilezen Skadi / NASA SRTM.
See `../yubk-3d-android/ATTRIBUTION.md` for source attribution.
