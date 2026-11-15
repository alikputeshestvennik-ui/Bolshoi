# YUBK 3D · Godot Android

## Mobile data pipeline

`python3 tools/sync_assets.py` prepares attributed offline OSM/SRTM assets from
the adjacent Android project. It repairs pavement, rebuilds waterways against
the DEM triangles and adds missing real footprints from `data/yalta.osm.gz`.
The Yalta extract covers only 44.487–44.497 N, 34.145–34.165 E; it is not full
city coverage. Existing decorative facades are not measured building imagery.

The phone decodes one spatial mesh job at a time on a worker thread. Each job
contains at most 18,000 vertices; resident and in-flight geometry together are
limited to 480,000 vertices and 96 resident meshes. Obsolete jobs are discarded
after rapid movement. Coarse terrain is masked where surrounding detailed cells
are fully resident so it cannot obscure pavement and rivers.

The 2D map uses packed spatial road batches at three zoom levels. It suspends
3D rendering and simulation; idle map mode enables Godot's low CPU usage mode.
Rendering is capped at 30 FPS. These limits reduce workload but do not establish
thermal behaviour or crash resistance on a specific phone.

## Verification and APK

Run `python3 -m unittest discover -s tests -p 'test_*.py' -v` from this directory.
The **Build YUBK 3D Godot APK** workflow prepares assets, imports with Godot 4.4.1,
runs rendered map/streaming regressions and exports the arm64 APK. Screenshots
and the test log are saved as `yubk-godot-visual-checks`.

## BeamNG.Earth reference

Reviewed: https://github.com/TecnaGamer/BeamNG.Earth (MIT).
Its server obtains elevation, imagery and Overture/OSM data; its client uses
BeamNG.drive terrain, road, mesh and physics APIs. It cannot run as an Android
mod without that engine. Distance-based detail, spatial chunks and bounded
background preparation are applicable to this Godot implementation. No code,
game assets or services from BeamNG.Earth are embedded here. Satellite imagery,
global streaming and BeamNG vehicle physics are not implemented.

Data: © OpenStreetMap contributors (ODbL 1.0), Mapzen/Tilezen Skadi / NASA SRTM.
See `../yubk-3d-android/ATTRIBUTION.md` for source attribution.
