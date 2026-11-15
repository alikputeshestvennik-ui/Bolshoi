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
var tile_by_id := {}

var _touches := {}
var _dragging := false
var _moved := 0.0


func setup(data: Dictionary) -> void:
	region = data
	tile_by_id.clear()
	var bounds = region.get("bounds", [])
	if bounds.size() >= 4:
		var west := float(bounds[0])
		var south := float(bounds[1])
		var east := float(bounds[2])
		var north := float(bounds[3])
		world_left = (west - ORIGIN_LON) * SX
		world_right = (east - ORIGIN_LON) * SX
		world_top = (ORIGIN_LAT - north) * SZ
		world_bottom = (ORIGIN_LAT - south) * SZ
	for t in region.get("tiles", []):
		tile_by_id[str(t["id"])] = t
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
	var w := maxf(1.0, size.x - 24.0)
	var h := maxf(1.0, size.y - 24.0)
	return maxf(0.0001, minf(w / maxf(1.0, world_right - world_left), h / maxf(1.0, world_bottom - world_top)))


func screen_scale() -> float:
	return base_scale() * zoom


func world_to_screen(p: Vector2) -> Vector2:
	var s := screen_scale()
	var cx := (world_left + world_right) / 2.0
	var cz := (world_top + world_bottom) / 2.0
	return Vector2(size.x / 2.0 + pan.x - cx * s, size.y / 2.0 + pan.y - cz * s) + Vector2(p.x * s, p.y * s)


func screen_to_world(sp: Vector2) -> Vector2:
	var s := screen_scale()
	var cx := (world_left + world_right) / 2.0
	var cz := (world_top + world_bottom) / 2.0
	return Vector2((sp.x - size.x / 2.0 - pan.x) / s + cx, (sp.y - size.y / 2.0 - pan.y) / s + cz)


func _gui_input(event: InputEvent) -> void:
	if event is InputEventScreenTouch:
		if event.pressed:
			_touches[event.index] = event.position
			_dragging = false
			_moved = 0.0
		else:
			if _touches.has(event.index):
				_touches.erase(event.index)
			if _touches.is_empty() and not _dragging and _moved < 12.0:
				tapped.emit(screen_to_world(event.position))
	elif event is InputEventScreenDrag:
		if _touches.has(event.index):
			var prev: Vector2 = _touches[event.index]
			var delta: Vector2 = event.position - prev
			_moved += delta.length()
			if _touches.size() >= 2:
				var idx2: int = -1
				for k in _touches.keys():
					if int(k) != event.index:
						idx2 = int(k)
						break
				if idx2 >= 0 and _touches.has(idx2):
					var a: Vector2 = _touches[event.index]
					var b: Vector2 = _touches[idx2]
					var old_dist := maxf(1.0, a.distance_to(b))
					var new_pos: Vector2 = event.position
					var new_dist := maxf(1.0, new_pos.distance_to(b))
					var factor := new_dist / old_dist
					var focus := screen_to_world((a + b) / 2.0)
					zoom = clampf(zoom * factor, 0.8, 64.0)
					# Keep the pinch focus point stationary.
					var after := screen_scale()
					pan = Vector2(
						(a.x + b.x) / 2.0 - size.x / 2.0 + ((world_left + world_right) / 2.0 - focus.x) * after,
						(a.y + b.y) / 2.0 - size.y / 2.0 + ((world_top + world_bottom) / 2.0 - focus.y) * after
					)
					queue_redraw()
					return
			_touches[event.index] = event.position
			pan += delta
			if _moved > 6.0:
				_dragging = true
			clamp_pan()
			queue_redraw()


func clamp_pan() -> void:
	var s := screen_scale()
	var mx := maxf(0.0, (world_right - world_left) * s / 2.0 - size.x / 2.0 + 10.0)
	var my := maxf(0.0, (world_bottom - world_top) * s / 2.0 - size.y / 2.0 + 10.0)
	pan.x = clampf(pan.x, -mx, mx)
	pan.y = clampf(pan.y, -my, my)


func _draw() -> void:
	if region.is_empty():
		return
	draw_rect(Rect2(Vector2.ZERO, size), Color(0.04, 0.09, 0.11))
	if zoom <= 40.0:
		var tex := load("res://assets/route/region_map.png") as Texture2D
		if tex != null:
			var a := world_to_screen(Vector2(world_left, world_top))
			var b := world_to_screen(Vector2(world_right, world_bottom))
			draw_texture_rect(tex, Rect2(a, b - a), false)
	else:
		draw_rect(Rect2(Vector2.ZERO, size), Color(0.12, 0.19, 0.2))

	_draw_landuse()
	_draw_coast()
	_draw_route()
	_draw_water()
	_draw_roads()
	_draw_branches()
	_draw_places()
	if zoom >= 5.0:
		_draw_streets()
	if has_selection:
		var sp := world_to_screen(selection)
		draw_circle(sp, 8.0, Color(1.0, 0.76, 0.31))
		draw_arc(sp, 10.0, 0.0, TAU, 32, Color(1.0, 0.76, 0.31), 2.0)


func _poly_points(points) -> PackedVector2Array:
	var out := PackedVector2Array()
	for p in points:
		out.append(Vector2(float(p[0]), float(p[1])))
	return out


func _draw_landuse() -> void:
	for lu in region.get("landuse", []):
		var pts := _poly_points(lu.get("points", []))
		if pts.size() < 3:
			continue
		var kind := str(lu.get("kind", ""))
		var fill := Color(0.84, 0.80, 0.68, 0.22)
		if kind == "commercial" or kind == "retail":
			fill = Color(0.86, 0.81, 0.68, 0.28)
		elif kind == "industrial":
			fill = Color(0.74, 0.71, 0.62, 0.32)
		draw_colored_polygon(pts, fill)


func _draw_coast() -> void:
	for line in region.get("coast", []):
		var pts := _poly_points(line)
		if pts.size() >= 2:
			draw_polyline(pts, Color(0.89, 0.91, 0.82, 0.8), 1.3, true)


func _draw_route() -> void:
	var route = region.get("route", [])
	if route.is_empty():
		return
	var pts := PackedVector2Array()
	for p in route:
		pts.append(Vector2(float(p[0]), float(p[1])))
	draw_polyline(pts, Color(0.02, 0.10, 0.11, 0.75), 7.0, true)
	draw_polyline(pts, Color(1.0, 0.76, 0.31), 3.5, true)


func _draw_water() -> void:
	for w in region.get("water", []):
		var pts := _poly_points(w.get("points", []))
		if pts.size() < 2:
			continue
		var kind := str(w.get("kind", ""))
		var base := 1.45 if kind == "river" else (1.1 if kind == "canal" else 0.8)
		draw_polyline(pts, Color(0.03, 0.15, 0.2, 0.73), base + 1.8, true)
		var col := Color(0.32, 0.72, 0.87) if kind == "river" else Color(0.28, 0.62, 0.77)
		draw_polyline(pts, col, base, true)


func _draw_roads() -> void:
	var groups = region.get("road_groups", [])
	if groups.is_empty():
		return
	for g in groups:
		var segs = g.get("segments", [])
		var pts := PackedVector2Array()
		for s in segs:
			pts.append(Vector2(float(s[0]), float(s[1])))
			pts.append(Vector2(float(s[2]), float(s[3])))
		if pts.size() >= 2:
			draw_multiline(pts, Color(0.12, 0.15, 0.14, 0.42), 3.2)


func _draw_branches() -> void:
	for br in region.get("branches", []):
		var pts := _poly_points(br.get("points", []))
		if pts.size() >= 2:
			draw_polyline(pts, Color(1.0, 0.83, 0.55, 0.7), 2.0, true)


func _draw_places() -> void:
	var font := ThemeDB.fallback_font
	for p in region.get("places", []):
		var pos := Vector2(float(p["x"]), float(p["z"]))
		var sp := world_to_screen(pos)
		if sp.x < -40.0 or sp.x > size.x + 40.0 or sp.y < -40.0 or sp.y > size.y + 40.0:
			continue
		var kind := str(p.get("kind", ""))
		var major := kind == "city" or kind == "town" or kind == "sight"
		var col := Color(0.99, 0.77, 0.35) if major else Color(0.72, 0.84, 0.78)
		draw_circle(sp, 3.5 if major else 2.4, col)
		if major or zoom >= 3.2:
			draw_string(font, sp + Vector2(6.0, -4.0), str(p.get("name", "")), HORIZONTAL_ALIGNMENT_LEFT, -1, 13, Color(0.93, 0.97, 0.93, 0.92))


func _street_global(st: Dictionary) -> Vector2:
	var tid := str(st.get("tile", "tile_00"))
	if not tile_by_id.has(tid):
		return Vector2(float(st.get("lx", 0.0)), float(st.get("lz", 0.0)))
	var tile = tile_by_id[tid]
	var origin = tile.get("origin", [ORIGIN_LAT, ORIGIN_LON])
	var gx := float(st.get("lx", 0.0)) + (float(origin[1]) - ORIGIN_LON) * SX
	var gz := float(st.get("lz", 0.0)) + (ORIGIN_LAT - float(origin[0])) * SZ
	return Vector2(gx, gz)


func _draw_streets() -> void:
	var font := ThemeDB.fallback_font
	var shown := 0
	for st in region.get("streets", []):
		if shown >= 140:
			break
		var pos := _street_global(st)
		var sp := world_to_screen(pos)
		if sp.x < 0.0 or sp.x > size.x or sp.y < 0.0 or sp.y > size.y:
			continue
		draw_string(font, sp + Vector2(3.0, -3.0), str(st.get("name", "")), HORIZONTAL_ALIGNMENT_LEFT, -1, 11, Color(0.85, 0.9, 0.88, 0.8))
		shown += 1
