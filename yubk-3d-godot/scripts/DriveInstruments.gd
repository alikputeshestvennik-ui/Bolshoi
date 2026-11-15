class_name DriveInstruments
extends Control

var player_pos := Vector2.ZERO
var heading := 0.0
var speed_kmh := 0.0
var altitude := 0.0
var route_km := 0.0
var speed_mode := ""
var street_name := ""
var local_roads: Array = []
var panel := StyleBoxFlat.new()
var _roads_tile := ""
var _roads_origin := Vector2(INF, INF)


func _ready() -> void:
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	panel.bg_color = Color(0.025, 0.065, 0.09, 0.92)
	panel.border_color = Color("40616c")
	panel.set_border_width_all(1)
	panel.set_corner_radius_all(14)


func update_state(tile: Dictionary, point: Vector2, yaw: float, speed: float, metres: float, speed_label: String) -> void:
	player_pos = point
	heading = fposmod(yaw, 360.0)
	speed_kmh = speed
	altitude = TileLoader.pavement_height(tile, point.x, point.y)
	route_km = metres / 1000.0
	speed_mode = speed_label
	# The spatial neighborhood changes much less often than the speed/compass.
	# In particular, standing still must not allocate/deduplicate 63 cells at 10 Hz.
	var tile_id := str(tile.get("id", ""))
	if tile_id != _roads_tile or point.distance_squared_to(_roads_origin) >= 24.0 * 24.0:
		_refresh_roads(tile, point)
		_roads_tile = tile_id
		_roads_origin = point
	var nearest := INF
	street_name = "Местная дорога"
	for road in local_roads:
		var distance := Geometry2D.get_closest_point_to_segment(point, road["a"], road["b"]).distance_squared_to(point)
		road["distance"] = distance
		if distance < nearest:
			nearest = distance
			street_name = road["name"]
	if street_name.is_empty() or street_name.begins_with("Безымянная"):
		street_name = "Местная дорога"
	queue_redraw()


func _refresh_roads(tile: Dictionary, point: Vector2) -> void:
	local_roads.clear()
	var seen := {}
	for x in range(-4, 5):
		for z in range(-3, 4):
			for segment in TileLoader.nearby(tile, "road", point.x + x * 64.0, point.y + z * 64.0):
				var key := hash(segment)
				if seen.has(key):
					continue
				seen[key] = true
				var a := Vector2(segment[0], segment[1])
				var b := Vector2(segment[2], segment[3])
				var name := str(segment[4]) if segment.size() > 4 else ""
				local_roads.append({"a": a, "b": b, "name": name, "distance": INF})


func _text(point: Vector2, text: String, font_size: int, color := Color("e6efe9")) -> void:
	draw_string(ThemeDB.fallback_font, point, text, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size, color)


func _clip_line(a: Vector2, b: Vector2, box: Rect2) -> PackedVector2Array:
	var delta := b - a
	var lo := 0.0
	var hi := 1.0
	for axis in range(2):
		if absf(delta[axis]) < 0.00001:
			if a[axis] < box.position[axis] or a[axis] > box.end[axis]:
				return PackedVector2Array()
			continue
		var t0 := (box.position[axis] - a[axis]) / delta[axis]
		var t1 := (box.end[axis] - a[axis]) / delta[axis]
		lo = maxf(lo, minf(t0, t1))
		hi = minf(hi, maxf(t0, t1))
		if lo > hi:
			return PackedVector2Array()
	return PackedVector2Array([a + delta * lo, a + delta * hi])


func _draw() -> void:
	var mini := Rect2(20, 88, 188, 144)
	draw_style_box(panel, mini)
	_text(mini.position + Vector2(12, 21), "ОКРЕСТНОСТИ", 11, Color("a9c0c3"))
	_text(mini.position + Vector2(158, 21), "С ↑", 11, Color("ffc36b"))
	var box := Rect2(mini.position + Vector2(12, 30), Vector2(164, 83))
	var center := box.get_center()
	draw_rect(box, Color("102832"))
	for road in local_roads:
		var a: Vector2 = center + (road["a"] - player_pos) * 0.4
		var b: Vector2 = center + (road["b"] - player_pos) * 0.4
		var clipped := _clip_line(a, b, box)
		if clipped.size() == 2:
			var selected: bool = road["name"] == street_name or (street_name == "Местная дорога" and road["distance"] < 100.0)
			draw_line(clipped[0], clipped[1], Color("ffc36b") if selected else Color("6b8a8c"), 2.8 if selected else 1.5, true)
	var direction := Vector2(sin(deg_to_rad(heading)), -cos(deg_to_rad(heading)))
	var side := Vector2(-direction.y, direction.x)
	draw_circle(center, 10.0, Color("10202b"))
	draw_colored_polygon(PackedVector2Array([center + direction * 10.0, center - direction * 6.0 + side * 6.0, center - direction * 3.0, center - direction * 6.0 - side * 6.0]), Color("66dcf1"))
	var street := street_name
	while ThemeDB.fallback_font.get_string_size(street, HORIZONTAL_ALIGNMENT_LEFT, -1, 12).x > 164.0:
		street = street.left(street.length() - 2) + "…"
	_text(mini.position + Vector2(12, 133), street, 12)

	# A short compass tape remains legible between the status and action cards.
	var compass := Rect2(size.x / 2.0 - 110, 18, 220, 48)
	draw_style_box(panel, compass)
	var bearings := ["С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"]
	var bearing: String = bearings[int(floor((heading + 22.5) / 45.0)) % 8]
	_text(compass.position + Vector2(14, 31), "%s  %03d°" % [bearing, int(heading)], 18)
	_text(compass.position + Vector2(148, 30), "%.0f м" % altitude, 14, Color("a9c0c3"))

	var gauge := Rect2(size.x / 2.0 - 120, size.y - 135, 240, 72)
	draw_style_box(panel, gauge)
	_text(gauge.position + Vector2(14, 35), "%02d" % int(round(speed_kmh)), 28)
	_text(gauge.position + Vector2(67, 33), "км/ч", 13, Color("a9c0c3"))
	_text(gauge.position + Vector2(148, 22), "МАРШРУТ", 10, Color("a9c0c3"))
	_text(gauge.position + Vector2(148, 43), "%.1f км" % route_km, 16, Color("ffc36b"))
	_text(gauge.position + Vector2(14, 53), "Режим: " + speed_mode, 11, Color("a9c0c3"))
	var bar := Rect2(gauge.position + Vector2(14, 61), Vector2(212, 3))
	draw_rect(bar, Color("294751"))
	bar.size.x *= clampf(speed_kmh / 70.0, 0.0, 1.0)
	draw_rect(bar, Color("65d5ee"))
