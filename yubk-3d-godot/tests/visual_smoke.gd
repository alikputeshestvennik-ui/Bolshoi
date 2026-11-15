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
	await RenderingServer.frame_post_draw
	var image := root.get_texture().get_image()
	image.save_png("res://build/" + name + ".png")
	return image


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
	var image := await capture("map-overview")
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
	check(app.current_mesh.material_override.cull_mode == BaseMaterial3D.CULL_DISABLED, "Mixed-winding legacy geometry must be two-sided")
	await capture("walk-gaspra")
	var initial: Vector2 = app.pos
	app.forward_input = 1.0
	app._process(0.1)
	app.forward_input = 0.0
	check(app.pos.distance_to(initial) > 0.05, "Road movement is blocked at spawn")
	app._show_map()
	check(app.forward_input == 0.0 and app.turn_input == 0.0, "Movement must stop when opening the map")
	check(map.has_player, "Map must show current position")
	app._show_walk()
	check(app.current_mesh != null, "Returning from map should preserve the scene")
	# Check a different terrain/building tile and compact landscape layout.
	var spawn: Array = TileLoader.load_tile_meta("tile_00")["spawn"]
	app._load_tile("tile_00", spawn[0], spawn[1], spawn[2])
	await capture("walk-sevastopol")
	app._show_map()
	map.reset_view()
	root.size = Vector2i(1280, 720)
	await capture("map-16-9")
	app._show_walk()
	await capture("walk-16-9")
	print("VISUAL_SMOKE_", "FAIL" if failed else "PASS")
	quit(1 if failed else 0)
