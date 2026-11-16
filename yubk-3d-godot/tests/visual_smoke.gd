extends SceneTree

var app
var failed := false


func _initialize() -> void:
	_run.call_deferred()


func check(value: bool, message: String) -> void:
	if not value:
		failed = true
		push_error(message)


func capture(name: String) -> Image:
	await process_frame
	await process_frame
	# Idle map mode intentionally stops requesting frames. Capture explicitly.
	RenderingServer.force_draw(false)
	var image := root.get_texture().get_image()
	image.save_png("res://build/" + name + ".png")
	print("CAPTURE ", name)
	return image


func wait_for_stream() -> void:
	var start := Time.get_ticks_msec()
	while app.world_stream != null and not app.world_stream.is_settled():
		await process_frame
		check(app.world_stream.resident_vertices <= WorldStream.MAX_VERTICES, "Streaming vertex budget exceeded")
		check(app.world_stream.resident.size() <= WorldStream.MAX_CHUNKS, "Streaming chunk budget exceeded")
		if Time.get_ticks_msec() - start > 20000:
			check(false, "Streaming did not settle within 20 seconds")
			break


func _run() -> void:
	root.size = Vector2i(1600, 720)
	app = load("res://scenes/Main.tscn").instantiate()
	root.add_child(app)
	await process_frame
	var map: RegionMap = app.map
	check(map.backdrop != null, "Map backdrop missing")
	check(not app.region.is_empty(), "Region missing")
	var point := Vector2(12000, 8000)
	check(map.screen_to_world(map.world_to_screen(point)).distance_to(point) < 0.1, "Map coordinate roundtrip")
	check(map._poly_points([[point.x, point.y]])[0].distance_to(map.world_to_screen(point)) < 0.1, "Vector overlays must use screen coordinates")
	var map_started := Time.get_ticks_msec()
	while map._map_thread != null and Time.get_ticks_msec() - map_started < 20000:
		await process_frame
	check(not map.road_layers.is_empty(), "Packed map roads missing")
	var image := await capture("map-overview")
	check(map.drawn_road_points < 150000, "Overview geometry must stay bounded")
	check(root.disable_3d and not app.is_processing(), "Map must suspend the 3D simulation")
	# Repeated pinch/pan used to rebuild all road arrays and exhaust mobile memory.
	for i in range(90):
		map.zoom_at(1.2 if i % 2 == 0 else 1.0 / 1.2, Vector2(800, 360))
		map.pan += Vector2(10 if i % 2 == 0 else -10, 0)
		map.clamp_pan()
		map.queue_redraw()
		await process_frame
	map.reset_view()
	var pixel := image.get_pixel(800, 360)
	check(minf(pixel.r, minf(pixel.g, pixel.b)) < 0.85, "Map background is white")
	check(app._candidate_at(40000.0, 22000.0).is_empty(), "Off-route taps must not select kilometre zero")
	var touch := InputEventScreenTouch.new()
	touch.index = 0
	touch.position = Vector2(700, 350)
	touch.pressed = true
	map._gui_input(touch)
	touch = InputEventScreenTouch.new()
	touch.index = 1
	touch.position = Vector2(900, 350)
	touch.pressed = true
	map._gui_input(touch)
	var drag := InputEventScreenDrag.new()
	drag.index = 1
	drag.position = Vector2(1000, 350)
	map._gui_input(drag)
	check(map._touches[1] == drag.position, "Pinch touch must update")
	drag.position = Vector2(1100, 350)
	map._gui_input(drag)
	check(absf(map.zoom - 2.0) < 0.001, "Consecutive pinch deltas must not accumulate stale positions")
	for finger in [0, 1]:
		touch = InputEventScreenTouch.new()
		touch.index = finger
		touch.pressed = false
		map._gui_input(touch)
	check(not app.teleport_panel.visible, "Pinch release must not teleport")
	map.reset_view()
	if app.region.has("spatial_grid"):
		await _run_regional()
		return
	# Revisit the same area as the user's Gaspra screenshot.
	var route: Array = app.region["route"]
	var target: Array = []
	for p in route:
		if p[3] >= 72600.0:
			target = p
			break
	var candidate: Dictionary = app._candidate_at(target[0], target[1])
	check(candidate.get("tile_id") == "tile_12", "Gaspra selection")
	app._on_map_tapped(Vector2(target[0], target[1]))
	map.zoom_at(5.0, map.world_to_screen(Vector2(target[0], target[1])))
	await capture("map-gaspra")
	app._show_walk()
	app._load_tile(candidate["tile_id"], candidate["local_x"], candidate["local_z"], candidate["heading"])
	check(app.current_mesh != null, "Tile did not load")
	check(app.world_stream.material.cull_mode == BaseMaterial3D.CULL_DISABLED, "Mixed-winding legacy geometry must be two-sided")
	check(not app.current_tile["surfaces"].is_empty(), "Actual pavement height data missing")
	var pavement := TileLoader.pavement_height(app.current_tile, app.pos.x, app.pos.y)
	check(absf(app.camera.position.y - pavement - 1.9) < 0.01, "Camera must stand on the pavement")
	check(app.instruments.visible, "Driving instruments missing")
	await wait_for_stream()
	check(not app.world_stream.resident.is_empty(), "Near detail never loaded")
	await capture("walk-gaspra")
	var initial: Vector2 = app.pos
	app.forward_input = 1.0
	app._process(0.1)
	app.forward_input = 0.0
	check(app.pos.distance_to(initial) > 0.05, "Road movement is blocked at spawn")
	check(app.travel_speed > 0.0 and app.travel_speed <= 70.01, "Speedometer must show actual travel speed")
	app._process(0.1)
	check(app.instruments.speed_kmh == 0.0, "Idle speedometer must show zero")
	app._show_map()
	check(app.forward_input == 0.0 and app.turn_input == 0.0, "Movement must stop when opening the map")
	check(map.has_player, "Map must show current position")
	app._show_walk()
	check(app.current_mesh != null, "Returning from map should preserve the scene")
	# Check a different terrain/building tile and compact landscape layout.
	var spawn: Array = TileLoader.load_tile_meta("tile_00")["spawn"]
	app._load_tile("tile_00", spawn[0], spawn[1], spawn[2])
	await wait_for_stream()
	await capture("walk-sevastopol")
	# Cover restored real buildings in central Yalta.
	var yalta: Dictionary = TileLoader.load_tile_meta("tile_13")
	check(int(yalta.get("recovered_osm_buildings", 0)) > 1000, "Yalta building recovery missing")
	var lx: float = (34.155 - float(yalta["origin"][1])) * app.SX
	var lz: float = (float(yalta["origin"][0]) - 44.492) * app.SZ
	app._load_tile("tile_13", lx, lz, 180)
	await wait_for_stream()
	var ground := TileLoader.ground_height(app.current_tile, lx, lz)
	app.set_process(false)
	app.camera.position = Vector3(lx, ground + 280, lz + 230)
	app.camera.look_at(Vector3(lx, ground, lz), Vector3.UP)
	await capture("walk-yalta-buildings")
	app._update_camera()
	app.set_process(true)
	# Teleports and rapid changes may leave only one decode in flight.
	for i in range(30):
		app.world_stream.update_focus(Vector2(lx + (i % 3) * 450, lz), true)
		await process_frame
		check(app.world_stream.resident_vertices <= WorldStream.MAX_VERTICES, "Rapid movement exceeded memory budget")
	app.world_stream.update_focus(app.pos, true)
	await wait_for_stream()
	# Raised roads must not teleport the camera between overpass levels.
	var test_tile := {"w": 2, "h": 2, "dx": 20.0, "dz": 20.0,
		"half_x": 10.0, "half_z": 10.0, "heights": [[0, 0], [0, 0]],
		"surface_index": {Vector2i.ZERO: [0, 12]},
		"surfaces": PackedFloat32Array([-4, 1, -4, 4, 1, -4, 4, 1, 4, -4, 1, 4,
			-4, 6, -4, 4, 6, -4, 4, 6, 4, -4, 6, 4])}
	check(absf(TileLoader.pavement_height(test_tile, 0, 0, 1.0) - 1.0) < 0.001, "Stay below an overpass")
	check(absf(TileLoader.pavement_height(test_tile, 0, 0, 6.0) - 6.0) < 0.001, "Stay on a bridge")
	check(absf(TileLoader.pavement_height(test_tile, 9, 9) - 0.0) < 0.001, "Use terrain outside pavement")
	app._show_map()
	map.reset_view()
	root.size = Vector2i(1280, 720)
	await capture("map-16-9")
	app._show_walk()
	await capture("walk-16-9")
	print("VISUAL_SMOKE_", "FAIL" if failed else "PASS")
	quit(1 if failed else 0)


func _run_regional() -> void:
	var places := [
		["sevastopol", 44.6054, 33.5221], ["alupka", 44.4194, 34.0557],
		["koreiz", 44.433, 34.084], ["yalta", 44.499, 34.169],
		["alushta", 44.6771, 34.4095], ["baidar", 44.446, 33.775],
		["gurzuf", 44.5479, 34.2737], ["balaklava", 44.5121, 33.5990]]
	var stream_id := 0
	for place in places:
		var point := Vector2((float(place[2]) - app.ORIGIN_LON) * app.SX, (app.ORIGIN_LAT - float(place[1])) * app.SZ)
		var candidate: Dictionary = app._candidate_at(point.x, point.y)
		check(not candidate.is_empty(), "Regional teleport unavailable: " + str(place[0]))
		if candidate.is_empty():
			continue
		check(point.distance_to(Vector2(candidate["x"], candidate["z"])) <= 161.0, "Teleport moved to the highway: " + str(place[0]))
		app._show_walk()
		app._load_tile(candidate["tile_id"], candidate["local_x"], candidate["local_z"], candidate["heading"])
		check(not TileLoader.blocked(app.current_tile, app.pos.x, app.pos.y), "Teleport landed in an obstacle")
		check(not app.current_tile["surfaces"].is_empty(), "Local roads have no pavement height data")
		if stream_id == 0:
			stream_id = app.world_stream.get_instance_id()
		check(app.world_stream.get_instance_id() == stream_id, "Regional teleport recreated the streamer")
		await wait_for_stream()
		check(app.world_stream.failed.is_empty(), "A regional geometry job failed")
		check(not app.world_stream.resident.is_empty(), "Missing local 3D detail")
		var cell := Vector2i(int(floor(app.pos.x / 256.0)), int(floor(app.pos.y / 256.0)))
		var pixel: Vector2i = cell - app.world_stream._grid_origin
		check(app.world_stream._coverage.get_pixel(pixel.x, pixel.y).r > 0.5, "Coarse terrain still covers the selected street")
		var ground := TileLoader.pavement_height(app.current_tile, app.pos.x, app.pos.y)
		check(absf(app.camera.position.y - ground - 1.9) < 0.01, "Regional camera is off the pavement")
		await capture("street-" + str(place[0]))
		app.set_process(false)
		app.camera.position = Vector3(app.pos.x, ground + 240, app.pos.y + 200)
		app.camera.look_at(Vector3(app.pos.x, ground, app.pos.y), Vector3.UP)
		await capture("area-" + str(place[0]))
		app._update_camera()
		app.set_process(true)
	# Cross a real route/page boundary in both directions with ordinary movement.
	var crossed := false
	var route: Array = app.region["route"]
	for i in range(1, route.size()):
		var a := Vector2(float(route[i - 1][0]), float(route[i - 1][1]))
		var b := Vector2(float(route[i][0]), float(route[i][1]))
		var page_a: Dictionary = app._tile_at(a)
		var page_b: Dictionary = app._tile_at(b)
		if page_a.is_empty() or page_b.is_empty() or page_a["id"] == page_b["id"]:
			continue
		var lo := a
		var hi := b
		for j in range(20):
			var mid := (lo + hi) / 2.0
			if app._tile_at(mid).get("id") == page_a["id"]:
				lo = mid
			else:
				hi = mid
		var direction := (b - a).normalized()
		var start := lo - direction * 1.0
		app._load_tile(page_a["id"], start.x, start.y, rad_to_deg(atan2(direction.x, -direction.y)))
		if TileLoader.blocked(app.current_tile, start.x, start.y):
			continue
		app.snap_enabled = false
		app.forward_input = 1.0
		app._process(0.1)
		app.forward_input = 0.0
		if app.current_tile.get("id") != page_b["id"]:
			continue
		check(app.pos.distance_to(start) > 1.5, "Movement stopped at a page border")
		app.heading_deg += 180.0
		app.forward_input = 1.0
		app._process(0.1)
		app.forward_input = 0.0
		check(app.current_tile.get("id") == page_a["id"], "Cannot cross back over a page border")
		check(app.world_stream.get_instance_id() == stream_id, "Page transition recreated the streamer")
		app.snap_enabled = true
		crossed = true
		break
	check(crossed, "No real page boundary could be crossed")
	await wait_for_stream()
	for i in range(24):
		app.world_stream.update_focus(app.pos + Vector2((i % 3 - 1) * 4200, 0), true)
		await process_frame
		check(app.world_stream.resident_vertices <= WorldStream.MAX_VERTICES, "Page changes exceeded geometry budget")
	app.world_stream.update_focus(app.pos, true)
	await wait_for_stream()
	app._show_map()
	check(root.disable_3d and not app.is_processing(), "Map did not suspend regional rendering")
	root.size = Vector2i(1280, 720)
	app.map.reset_view()
	await capture("map-regional-16-9")
	print("VISUAL_SMOKE_", "FAIL" if failed else "PASS")
	quit(1 if failed else 0)
