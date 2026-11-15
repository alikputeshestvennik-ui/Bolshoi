class_name RegionMap
extends Control

signal tapped(world: Vector2)

const ORIGIN_LAT := 44.55
const ORIGIN_LON := 33.95
const SX := 79461.56265170258
const SZ := 111122.98436768592

var region: Dictionary = {}
var world_left := -40000.0
var world_right := 40000.0
var world_top := -20000.0
var world_bottom := 20000.0
var zoom := 1.0
var pan := Vector2.ZERO
var selection := Vector2.ZERO
var has_selection := false
var player := Vector2.ZERO
var has_player := false
var tile_by_id := {}
# Keep a strong reference: a texture loaded only inside _draw can be freed
# before the renderer consumes its RID, leaving a white map on Android.
var backdrop: Texture2D
var road_layers: Array = []
var label_rects: Array[Rect2] = []
var _touches := {}
var _moved := 0.0
var _gesture := false
var _mouse_drag := false


func setup(data: Dictionary) -> void:
	region = data
	clip_contents = true
	backdrop = load("res://assets/route/region_map.png") as Texture2D
	tile_by_id.clear()
	var bounds = region.get("bounds", [])
	if bounds.size() >= 4:
		world_left = (float(bounds[0]) - ORIGIN_LON) * SX
		world_right = (float(bounds[2]) - ORIGIN_LON) * SX
		world_top = (ORIGIN_LAT - float(bounds[3])) * SZ
		world_bottom = (ORIGIN_LAT - float(bounds[1])) * SZ
	for t in region.get("tiles", []):
		tile_by_id[str(t["id"])] = t
	# Convert JSON once, then transform every vector layer with the same matrix.
	road_layers.clear()
	for group in region.get("road_groups", []):
		var points := PackedVector2Array()
		for seg in group.get("segments", []):
			points.append(Vector2(float(seg[0]), float(seg[1])))
			points.append(Vector2(float(seg[2]), float(seg[3])))
		road_layers.append({"kind": str(group.get("kind", "")), "points": points})
	resized.connect(_on_resized)
	queue_redraw()


func _on_resized() -> void:
	clamp_pan()
	queue_redraw()


func reset_view() -> void:
	zoom = 1.0
	pan = Vector2.ZERO
	has_selection = false
	queue_redraw()


func set_selection(pos: Vector2) -> void:
	selection = pos
	has_selection = true
	queue_redraw()


func base_scale() -> float:
	return maxf(0.0001, minf((size.x - 48.0) / (world_right - world_left), (size.y - 160.0) / (world_bottom - world_top)))


func screen_scale() -> float:
	return base_scale() * zoom


func world_center() -> Vector2:
	return Vector2((world_left + world_right) / 2.0, (world_top + world_bottom) / 2.0)


func world_to_screen(p: Vector2) -> Vector2:
	return size / 2.0 + pan + (p - world_center()) * screen_scale()


func screen_to_world(sp: Vector2) -> Vector2:
	return (sp - size / 2.0 - pan) / screen_scale() + world_center()


func zoom_at(factor: float, focus: Vector2) -> void:
	var world := screen_to_world(focus)
	zoom = clampf(zoom * factor, 1.0, 64.0)
	pan = focus - size / 2.0 - (world - world_center()) * screen_scale()
	clamp_pan()
	queue_redraw()


func _gui_input(event: InputEvent) -> void:
	if event is InputEventScreenTouch:
		if event.pressed:
			if _touches.is_empty():
				_moved = 0.0
				_gesture = false
			_touches[event.index] = event.position
			if _touches.size() > 1:
				_gesture = true
		else:
			_touches.erase(event.index)
			if _touches.is_empty() and not _gesture and _moved < 10.0:
				tapped.emit(screen_to_world(event.position))
		accept_event()
	elif event is InputEventScreenDrag and _touches.has(event.index):
		var prev: Vector2 = _touches[event.index]
		_moved += event.position.distance_to(prev)
		if _touches.size() >= 2:
			for key in _touches:
				if key == event.index:
					continue
				var other: Vector2 = _touches[key]
				var old_mid := (prev + other) / 2.0
				var new_mid := (event.position + other) / 2.0
				var focus := screen_to_world(old_mid)
				zoom = clampf(zoom * event.position.distance_to(other) / maxf(1.0, prev.distance_to(other)), 1.0, 64.0)
				pan = new_mid - size / 2.0 - (focus - world_center()) * screen_scale()
				break
		else:
			pan += event.position - prev
		# Update after every drag, including pinch: stale positions cause jumps.
		_touches[event.index] = event.position
		clamp_pan()
		queue_redraw()
		accept_event()
	elif event is InputEventMouseButton and event.device != InputEvent.DEVICE_ID_EMULATION:
		if event.button_index == MOUSE_BUTTON_WHEEL_UP and event.pressed:
			zoom_at(1.25, event.position)
		elif event.button_index == MOUSE_BUTTON_WHEEL_DOWN and event.pressed:
			zoom_at(0.8, event.position)
		elif event.button_index == MOUSE_BUTTON_LEFT:
			_mouse_drag = event.pressed
			if event.pressed:
				_moved = 0.0
			elif _moved < 10.0:
				tapped.emit(screen_to_world(event.position))
		accept_event()
	elif event is InputEventMouseMotion and _mouse_drag:
		pan += event.relative
		_moved += event.relative.length()
		clamp_pan()
		queue_redraw()
		accept_event()


func clamp_pan() -> void:
	var extent := Vector2(world_right - world_left, world_bottom - world_top) * screen_scale()
	var limit := (extent - size) / 2.0 + Vector2(80.0, 100.0)
	pan.x = clampf(pan.x, -maxf(0.0, limit.x), maxf(0.0, limit.x))
	pan.y = clampf(pan.y, -maxf(0.0, limit.y), maxf(0.0, limit.y))


func _draw() -> void:
	draw_rect(Rect2(Vector2.ZERO, size), Color("0b1e30"))
	if region.is_empty():
		return
	if backdrop != null:
		var a := world_to_screen(Vector2(world_left, world_top))
		var b := world_to_screen(Vector2(world_right, world_bottom))
		draw_texture_rect(backdrop, Rect2(a, b - a), false)
	_draw_landuse()
	_draw_lines()
	label_rects.clear()
	_draw_places()
	if zoom >= 5.0:
		_draw_streets()
	if has_player:
		_draw_marker(player, Color("65d5ee"), 7.0)
	if has_selection:
		_draw_marker(selection, Color("ffc36b"), 9.0)
	_draw_scale()


func _draw_marker(world: Vector2, color: Color, radius: float) -> void:
	var sp := world_to_screen(world)
	draw_circle(sp, radius + 5.0, Color(0.02, 0.06, 0.1, 0.8))
	draw_circle(sp, radius, color)
	draw_arc(sp, radius + 5.0, 0.0, TAU, 32, color, 2.0, true)


func _poly_points(points) -> PackedVector2Array:
	var out := PackedVector2Array()
	for p in points:
		out.append(world_to_screen(Vector2(float(p[0]), float(p[1]))))
	return out


func _draw_landuse() -> void:
	for lu in region.get("landuse", []):
		var pts := _poly_points(lu.get("points", []))
		if pts.size() < 3:
			continue
		# OSM polygons can self-intersect; never submit an invalid polygon.
		var indices := Geometry2D.triangulate_polygon(pts)
		if indices.is_empty():
			continue
		for i in range(0, indices.size(), 3):
			draw_colored_polygon(PackedVector2Array([pts[indices[i]], pts[indices[i + 1]], pts[indices[i + 2]]]), Color(0.76, 0.72, 0.58, 0.18))


func _draw_lines() -> void:
	for line in region.get("coast", []):
		var pts := _poly_points(line)
		if pts.size() >= 2:
			draw_polyline(pts, Color(0.69, 0.83, 0.79, 0.65), 1.1, true)
	for water in region.get("water", []):
		var pts := _poly_points(water.get("points", []))
		if pts.size() >= 2:
			draw_polyline(pts, Color("61aeca"), 1.4 if water.get("kind", "") == "river" else 0.8, true)
	# Draw low priority roads first and keep widths in screen pixels.
	for major in [false, true]:
		for layer in road_layers:
			var is_major: bool = layer["kind"] in ["trunk", "primary", "trunk_link", "primary_link"]
			if is_major != major:
				continue
			var pts := PackedVector2Array()
			for p in layer["points"]:
				pts.append(world_to_screen(p))
			if pts.size() < 2:
				continue
			var width := 3.0 if is_major else (2.0 if zoom >= 3.0 else 1.0)
			draw_multiline(pts, Color("293d3c"), width + 2.0, true)
			draw_multiline(pts, Color("e7d3a5") if is_major else Color("a6ad96"), width, true)
	for branch in region.get("branches", []):
		var pts := _poly_points(branch.get("points", []))
		if pts.size() >= 2:
			draw_polyline(pts, Color("cfb37d"), 2.0, true)
	var route := _poly_points(region.get("route", []))
	if route.size() >= 2:
		draw_polyline(route, Color("162c37"), 7.0, true)
		draw_polyline(route, Color("ffc36b"), 3.5, true)


func _draw_label(pos: Vector2, text: String, font_size: int, color: Color) -> void:
	var font := ThemeDB.fallback_font
	var width := font.get_string_size(text, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size).x
	var box := Rect2(pos + Vector2(7.0, -font_size - 3.0), Vector2(width + 10.0, font_size + 10.0))
	if box.position.x < 8.0 or box.end.x > size.x - 8.0 or box.position.y < 86.0 or box.end.y > size.y - 90.0:
		return
	for occupied in label_rects:
		if occupied.intersects(box.grow(5.0)):
			return
	label_rects.append(box)
	draw_style_box(_label_background(), box)
	draw_string(font, pos + Vector2(12.0, 1.0), text, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size, color)


func _label_background() -> StyleBoxFlat:
	var style := StyleBoxFlat.new()
	style.bg_color = Color(0.035, 0.09, 0.13, 0.9)
	style.set_corner_radius_all(4)
	return style


func _draw_places() -> void:
	for major in [true, false]:
		for place in region.get("places", []):
			var kind := str(place.get("kind", ""))
			var is_major := kind in ["city", "town", "sight"]
			if is_major != major or (not major and zoom < 2.5):
				continue
			var sp := world_to_screen(Vector2(float(place["x"]), float(place["z"])))
			if not Rect2(Vector2.ZERO, size).has_point(sp):
				continue
			draw_circle(sp, 3.5 if major else 2.0, Color("ffc36b") if major else Color("c8dcc7"))
			_draw_label(sp, str(place.get("name", "")), 15 if major else 13, Color("f3f5e9"))


func _street_global(st: Dictionary) -> Vector2:
	var tile: Dictionary = tile_by_id.get(str(st.get("tile", "tile_00")), {})
	var origin = tile.get("origin", [ORIGIN_LAT, ORIGIN_LON])
	return Vector2(float(st.get("lx", 0.0)) + (float(origin[1]) - ORIGIN_LON) * SX, float(st.get("lz", 0.0)) + (ORIGIN_LAT - float(origin[0])) * SZ)


func _draw_streets() -> void:
	for street in region.get("streets", []):
		var sp := world_to_screen(_street_global(street))
		if Rect2(Vector2.ZERO, size).has_point(sp):
			_draw_label(sp, str(street.get("name", "")), 12, Color("d4e1d6"))


func _draw_scale() -> void:
	var meters := 1000.0
	for step in [100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0]:
		if step * screen_scale() <= 150.0:
			meters = step
	var a := Vector2(24.0, size.y - 25.0)
	var b := a + Vector2(meters * screen_scale(), 0.0)
	draw_line(a, b, Color("f3f5e9"), 3.0, true)
	draw_line(a - Vector2(0, 5), a + Vector2(0, 3), Color("f3f5e9"), 2.0)
	draw_line(b - Vector2(0, 5), b + Vector2(0, 3), Color("f3f5e9"), 2.0)
	var text := "%.0f м" % meters if meters < 1000.0 else "%.0f км" % (meters / 1000.0)
	draw_string(ThemeDB.fallback_font, a - Vector2(0, 10), text, HORIZONTAL_ALIGNMENT_LEFT, -1, 13, Color("f3f5e9"))
