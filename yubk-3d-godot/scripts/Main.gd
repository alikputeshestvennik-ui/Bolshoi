extends Node3D

const ORIGIN_LAT := 44.55
const ORIGIN_LON := 33.95
const SX := 79461.56265170258
const SZ := 111122.98436768592
const SPEEDS := [5.0, 40.0 / 3.6, 70.0 / 3.6]
const SPEED_LABELS := ["5 м/с", "40 км/ч", "70 км/ч"]

var region: Dictionary = {}
var mode := "map"

var camera: Camera3D
var env: WorldEnvironment
var tile_root: Node3D
var map: RegionMap
var map_chrome: Control
var resume_button: Button
var look_hint: Label
var status_time := 0.0
var hud: Control
var loading_label: Label
var status_label: Label
var speed_button: Button
var snap_button: Button
var teleport_panel: PanelContainer
var teleport_label: Label
var teleport_button: Button

var current_tile: Dictionary = {}
var current_mesh: MeshInstance3D
var pos := Vector2.ZERO
var heading_deg := 0.0
var pitch_deg := 0.0
var forward_input := 0.0
var turn_input := 0.0
var speed_index := 2
var snap_enabled := true
var pending_candidate: Dictionary = {}
var eye_height := NAN
var instruments: DriveInstruments
var travel_speed := 0.0
var world_stream: WorldStream
var _suspended := false


func _ready() -> void:
	Engine.max_fps = 30
	OS.low_processor_usage_mode = true
	OS.low_processor_usage_mode_sleep_usec = 33333
	randomize()
	region = TileLoader.load_region()
	_build_world()
	_build_ui()

	map.setup(region)
	map.visible = true
	hud.visible = false
	teleport_panel.visible = false
	loading_label.visible = false
	get_viewport().disable_3d = true
	set_process(false)

	if region.is_empty():
		status_label.text = "Ошибка загрузки region.json"
		hud.visible = true
		map.visible = false
		loading_label.visible = true
		loading_label.text = "Нет данных региона"


func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_PAUSED:
		_suspended = true
		get_viewport().disable_3d = true
		forward_input = 0.0
		turn_input = 0.0
		set_process(false)
		if world_stream != null:
			world_stream.set_process(false)
	elif what == NOTIFICATION_APPLICATION_RESUMED:
		_suspended = false
		get_viewport().disable_3d = mode != "walk"
		set_process(mode == "walk")
		if world_stream != null:
			world_stream.set_process(mode == "walk")


func _build_world() -> void:
	var environment := Environment.new()
	var sky_material := ProceduralSkyMaterial.new()
	sky_material.sky_top_color = Color("4586b3")
	sky_material.sky_horizon_color = Color("c3d5db")
	sky_material.ground_horizon_color = Color("c3d5db")
	sky_material.ground_bottom_color = Color("6d7665")
	var sky := Sky.new()
	sky.sky_material = sky_material
	environment.sky = sky
	environment.background_mode = Environment.BG_SKY
	environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	environment.ambient_light_color = Color(0.9, 0.93, 0.95)
	environment.ambient_light_energy = 1.1
	environment.fog_enabled = true
	# Match the sky horizon; the previous thin, blue fog exposed the tile edge.
	environment.fog_light_color = sky_material.sky_horizon_color
	environment.fog_density = 0.0011
	env = WorldEnvironment.new()
	env.environment = environment
	add_child(env)

	camera = Camera3D.new()
	camera.fov = 65.0
	camera.near = 0.15
	camera.far = 2400.0
	add_child(camera)

	tile_root = Node3D.new()
	add_child(tile_root)


func _build_ui() -> void:
	var ui := CanvasLayer.new()
	add_child(ui)
	var root := Control.new()
	root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.theme = _theme()
	ui.add_child(root)

	map = RegionMap.new()
	map.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	map.mouse_filter = Control.MOUSE_FILTER_STOP
	map.tapped.connect(_on_map_tapped)
	root.add_child(map)

	map_chrome = Control.new()
	map_chrome.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	map_chrome.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(map_chrome)
	var title := PanelContainer.new()
	_place(title, Control.PRESET_TOP_LEFT, Rect2(20, 18, 485, 66))
	map_chrome.add_child(title)
	title.add_child(_label("ЮЖНЫЙ БЕРЕГ КРЫМА\nСевастополь — Алушта · Байдарская долина", 17, true))
	var hint := _label("Выберите точку на суше или любую улицу\nДва пальца — масштаб · перетаскивание — сдвиг", 14, false)
	_place(hint, Control.PRESET_BOTTOM_RIGHT, Rect2(-485, -63, 465, 45))
	hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	map_chrome.add_child(hint)
	var credits := _label("© OpenStreetMap · рельеф SRTM", 11, false)
	_place(credits, Control.PRESET_TOP_RIGHT, Rect2(-310, 22, 290, 24))
	credits.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	map_chrome.add_child(credits)
	var zoom_in := _button("+", _map_zoom.bind(1.5))
	_place(zoom_in, Control.PRESET_CENTER_RIGHT, Rect2(-78, -94, 58, 56))
	map_chrome.add_child(zoom_in)
	var zoom_out := _button("−", _map_zoom.bind(1.0 / 1.5))
	_place(zoom_out, Control.PRESET_CENTER_RIGHT, Rect2(-78, -30, 58, 56))
	map_chrome.add_child(zoom_out)
	var reset := _button("Обзор", map.reset_view)
	_place(reset, Control.PRESET_CENTER_RIGHT, Rect2(-112, 34, 92, 50))
	map_chrome.add_child(reset)
	resume_button = _button("Вернуться в 3D", _show_walk)
	_place(resume_button, Control.PRESET_TOP_RIGHT, Rect2(-230, 56, 210, 54))
	resume_button.visible = false
	map_chrome.add_child(resume_button)

	hud = Control.new()
	hud.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	hud.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(hud)
	instruments = DriveInstruments.new()
	instruments.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	hud.add_child(instruments)
	loading_label = _label("Загрузка участка…", 24, true)
	_place(loading_label, Control.PRESET_CENTER, Rect2(-220, -40, 440, 80))
	loading_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	loading_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	hud.add_child(loading_label)
	var status_panel := PanelContainer.new()
	_place(status_panel, Control.PRESET_TOP_LEFT, Rect2(20, 18, 310, 58))
	hud.add_child(status_panel)
	status_label = _label("", 14, true)
	status_panel.add_child(status_label)
	var actions := HBoxContainer.new()
	actions.add_theme_constant_override("separation", 10)
	_place(actions, Control.PRESET_TOP_RIGHT, Rect2(-470, 18, 450, 56))
	hud.add_child(actions)
	snap_button = _button("По дорогам", _on_snap_button)
	snap_button.custom_minimum_size = Vector2(155, 56)
	actions.add_child(snap_button)
	speed_button = _button(SPEED_LABELS[speed_index], _on_speed_button)
	speed_button.custom_minimum_size = Vector2(145, 56)
	actions.add_child(speed_button)
	var map_btn := _button("Карта", _on_map_button)
	map_btn.custom_minimum_size = Vector2(130, 56)
	actions.add_child(map_btn)

	_add_dpad_button("ВПЕРЁД", Control.PRESET_BOTTOM_LEFT, Vector2(24, -160), Vector2(174, -92), "fwd")
	_add_dpad_button("НАЗАД", Control.PRESET_BOTTOM_LEFT, Vector2(24, -82), Vector2(174, -24), "back")
	_add_dpad_button("↶", Control.PRESET_BOTTOM_RIGHT, Vector2(-220, -100), Vector2(-128, -24), "left")
	_add_dpad_button("↷", Control.PRESET_BOTTOM_RIGHT, Vector2(-116, -100), Vector2(-24, -24), "right")
	look_hint = _label("Проведите по экрану, чтобы осмотреться", 14, false)
	_place(look_hint, Control.PRESET_BOTTOM_WIDE, Rect2(200, -49, -440, 26))
	look_hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	hud.add_child(look_hint)

	teleport_panel = PanelContainer.new()
	_place(teleport_panel, Control.PRESET_BOTTOM_WIDE, Rect2(210, -157, -420, 82))
	root.add_child(teleport_panel)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 20)
	teleport_panel.add_child(row)
	teleport_label = _label("", 16, false)
	teleport_label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	teleport_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	row.add_child(teleport_label)
	teleport_button = _button("Открыть в 3D →", _on_teleport_pressed)
	teleport_button.custom_minimum_size = Vector2(220, 54)
	row.add_child(teleport_button)
	teleport_panel.visible = false


func _theme() -> Theme:
	var theme := Theme.new()
	theme.default_font_size = 17
	for state in ["normal", "hover", "pressed", "disabled", "focus"]:
		var box := StyleBoxFlat.new()
		box.bg_color = Color(0.035, 0.085, 0.12, 0.88)
		box.border_color = Color("40616c")
		box.set_border_width_all(1)
		box.set_corner_radius_all(12)
		box.content_margin_left = 18
		box.content_margin_right = 18
		box.content_margin_top = 10
		box.content_margin_bottom = 10
		if state == "pressed":
			box.bg_color = Color("c18a43")
			box.border_color = Color("ffca7e")
		elif state == "hover":
			box.bg_color = Color("284856")
		theme.set_stylebox(state, "Button", box)
		if state == "normal":
			theme.set_stylebox("panel", "PanelContainer", box)
	theme.set_color("font_color", "Button", Color("f3f5e9"))
	theme.set_color("font_pressed_color", "Button", Color("ffffff"))
	return theme


func _place(control: Control, preset: int, rect: Rect2) -> void:
	control.set_anchors_preset(preset)
	control.offset_left = rect.position.x
	control.offset_top = rect.position.y
	control.offset_right = rect.end.x
	control.offset_bottom = rect.end.y


func _label(text: String, size_px: int, _bold: bool) -> Label:
	var label := Label.new()
	label.text = text
	label.mouse_filter = Control.MOUSE_FILTER_IGNORE
	label.add_theme_font_size_override("font_size", size_px)
	label.add_theme_color_override("font_color", Color("f3f5e9"))
	label.add_theme_color_override("font_shadow_color", Color(0.01, 0.03, 0.04, 0.9))
	label.add_theme_constant_override("shadow_offset_y", 1)
	return label


func _button(text: String, callback: Callable) -> Button:
	var button := Button.new()
	button.text = text
	button.focus_mode = Control.FOCUS_NONE
	button.pressed.connect(callback)
	return button


func _map_zoom(factor: float) -> void:
	map.zoom_at(factor, map.size / 2.0)


func _add_dpad_button(text: String, preset: int, a: Vector2, b: Vector2, action: String) -> void:
	var btn := DriveButton.new()
	btn.text = text
	btn.focus_mode = Control.FOCUS_NONE
	_place(btn, preset, Rect2(a, b - a))
	btn.held.connect(_on_dpad_down.bind(action))
	btn.released.connect(_on_dpad_up.bind(action))
	hud.add_child(btn)


func _on_dpad_down(action: String) -> void:
	match action:
		"fwd": forward_input = 1.0
		"back": forward_input = -1.0
		"left": turn_input = -1.0
		"right": turn_input = 1.0


func _on_dpad_up(action: String) -> void:
	match action:
		"fwd", "back": forward_input = 0.0
		"left", "right": turn_input = 0.0


func _on_map_button() -> void:
	_show_map()


func _on_speed_button() -> void:
	speed_index = (speed_index + 1) % SPEEDS.size()
	speed_button.text = SPEED_LABELS[speed_index]


func _on_snap_button() -> void:
	snap_enabled = not snap_enabled
	snap_button.text = "По дорогам" if snap_enabled else "Свободно"


func _on_map_tapped(world: Vector2) -> void:
	var c := _candidate_at(world.x, world.y)
	if c.is_empty():
		teleport_panel.visible = false
		return
	pending_candidate = c
	map.set_selection(Vector2(c["x"], c["z"]))
	teleport_label.text = _candidate_text(c)
	teleport_panel.visible = true


func _on_teleport_pressed() -> void:
	if pending_candidate.is_empty():
		return
	var c := pending_candidate
	pending_candidate = {}
	teleport_panel.visible = false
	_show_walk()
	_start_walk(c)


func _candidate_text(c: Dictionary) -> String:
	if region.has("spatial_grid"):
		var place := _nearest_place_at(Vector2(c["x"], c["z"]))
		return "%s\n%s" % [str(place.get("name", "Южный Крым")), str(c.get("street", "Свободная прогулка"))]
	var place := _nearest_place(c.get("route_m", 0.0), 7000.0)
	var name := str(c.get("tile_name", "ЮБК"))
	var km := float(c.get("route_m", 0.0)) / 1000.0
	var place_name: String = str(place.get("name", "Южнобережное шоссе")) if not place.is_empty() else "Южнобережное шоссе"
	return "%s · %.1f км от Севастополя\n%s" % [name, km, str(place_name)]


func _candidate_at(x: float, z: float) -> Dictionary:
	if region.has("spatial_grid"):
		return _regional_candidate(Vector2(x, z))
	var route = region.get("route", [])
	if route.size() < 2:
		return {}
	var best_d := 900.0 * 900.0
	var found := false
	var bx := 0.0
	var bz := 0.0
	var bh := 0.0
	var bm := 0.0
	for i in range(route.size() - 1):
		var a = route[i]
		var b = route[i + 1]
		var ax := float(a[0])
		var az := float(a[1])
		var cx := float(b[0])
		var cz := float(b[1])
		var vx := cx - ax
		var vz := cz - az
		var len := vx * vx + vz * vz
		var t := 0.0
		if len > 0.0:
			t = clampf(((x - ax) * vx + (z - az) * vz) / len, 0.0, 1.0)
		var px := ax + t * vx
		var pz := az + t * vz
		var d := (x - px) * (x - px) + (z - pz) * (z - pz)
		if d < best_d:
			best_d = d
			found = true
			bx = px
			bz = pz
			bh = float(a[2]) + (float(b[2]) - float(a[2])) * t
			bm = float(a[3]) + (float(b[3]) - float(a[3])) * t
	if not found:
		return {}
	var tile := _tile_for_route_m(bm)
	if tile.is_empty():
		return {}
	var origin = tile.get("origin", [ORIGIN_LAT, ORIGIN_LON])
	var local_x := bx + (ORIGIN_LON - float(origin[1])) * SX
	var local_z := bz + (float(origin[0]) - ORIGIN_LAT) * SZ
	return {
		"x": bx, "z": bz, "heading": bh, "route_m": bm,
		"local_x": local_x, "local_z": local_z,
		"tile_id": str(tile["id"]), "tile_name": str(tile["name"])
	}


func _tile_at(point: Vector2) -> Dictionary:
	for tile in region.get("tiles", []):
		if point.x >= float(tile["x0"]) and point.x < float(tile["x1"]) and point.y >= float(tile["z0"]) and point.y < float(tile["z1"]):
			return tile
	return {}


func _inside_region(point: Vector2) -> bool:
	var bounds: Array = region.get("bounds", [])
	if bounds.size() < 4:
		return false
	var lon := ORIGIN_LON + point.x / SX
	var lat := ORIGIN_LAT - point.y / SZ
	return lon >= bounds[0] and lon <= bounds[2] and lat >= bounds[1] and lat <= bounds[3]


func _nearest_place_at(point: Vector2) -> Dictionary:
	var best := {}
	var distance := INF
	for place in region.get("places", []):
		var d := point.distance_squared_to(Vector2(float(place["x"]), float(place["z"])))
		if d < distance:
			distance = d
			best = place
	return best


func _regional_candidate(point: Vector2) -> Dictionary:
	if not _inside_region(point):
		return {}
	var page := _tile_at(point)
	if page.is_empty():
		return {}
	var tile := TileLoader.tile_world(str(page["id"]))
	if tile.is_empty():
		return {}
	var selected := point
	var heading := 0.0
	var street := "Свободная прогулка"
	var distance := 160.0 * 160.0
	# Select a local street, with every village and side road eligible. The
	# route polyline has no part in deciding where a regional teleport lands.
	for road in tile.get("roads", []):
		var a := Vector2(float(road[0]), float(road[1]))
		var b := Vector2(float(road[2]), float(road[3]))
		var v := b - a
		if v.length_squared() < 0.01:
			continue
		var q := a + v * clampf((point - a).dot(v) / v.length_squared(), 0.0, 1.0)
		var d := q.distance_squared_to(point)
		if d < distance and not TileLoader.blocked(tile, q.x, q.y):
			distance = d
			selected = q
			heading = rad_to_deg(atan2(v.x, -v.y))
			street = str(road[4])
	if TileLoader.blocked(tile, selected.x, selected.y):
		return {}
	return {"x": selected.x, "z": selected.y, "local_x": selected.x, "local_z": selected.y,
		"heading": heading, "tile_id": str(page["id"]), "tile_name": str(page["name"]), "street": street}


func _tile_for_route_m(route_m: float) -> Dictionary:
	for t in region.get("tiles", []):
		if route_m >= float(t["start_m"]) and route_m <= float(t["end_m"]):
			return t
	return {}


func _nearest_place(route_m: float, max_m: float) -> Dictionary:
	var best: Dictionary = {}
	var best_d := max_m
	for p in region.get("places", []):
		var d := absf(float(p["route_m"]) - route_m)
		if d < best_d:
			best_d = d
			best = p
	return best


func _show_map() -> void:
	OS.low_processor_usage_mode = true
	mode = "map"
	get_viewport().disable_3d = true
	set_process(false)
	if world_stream != null:
		world_stream.set_process(false)
	forward_input = 0.0
	turn_input = 0.0
	tile_root.visible = false
	map_chrome.visible = true
	resume_button.visible = not current_tile.is_empty()
	if not current_tile.is_empty():
		var origin = current_tile["origin"]
		map.player = pos + Vector2((float(origin[1]) - ORIGIN_LON) * SX, (ORIGIN_LAT - float(origin[0])) * SZ)
		map.has_player = true
		map.queue_redraw()
	hud.visible = false
	teleport_panel.visible = false
	loading_label.visible = false
	map.visible = true


func _show_walk() -> void:
	OS.low_processor_usage_mode = false
	mode = "walk"
	get_viewport().disable_3d = false
	set_process(not _suspended)
	if world_stream != null:
		world_stream.set_process(not _suspended)
	forward_input = 0.0
	turn_input = 0.0
	map_chrome.visible = false
	tile_root.visible = true
	map.visible = false
	hud.visible = true
	teleport_panel.visible = false


func _start_walk(c: Dictionary) -> void:
	loading_label.visible = true
	loading_label.text = "Загрузка участка…"
	await get_tree().process_frame
	_load_tile(str(c["tile_id"]), float(c.get("local_x", 0.0)), float(c.get("local_z", 0.0)), float(c.get("heading", 0.0)))
	loading_label.visible = false


func _load_tile(tile_id: String, lx: float, lz: float, heading: float) -> void:
	if region.has("spatial_grid"):
		_load_region_tile(tile_id, Vector2(lx, lz), heading)
		return
	_unload_tile()
	current_tile = TileLoader.tile_world(tile_id)
	if current_tile.is_empty():
		status_label.text = "Нет данных участка " + tile_id
		return
	var mesh := TileLoader.load_tile_mesh(tile_id)
	if mesh == null:
		status_label.text = "Ошибка загрузки меша " + tile_id
		return
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = TileLoader.tile_material()
	tile_root.add_child(mi)
	current_mesh = mi
	world_stream = WorldStream.new()
	if not world_stream.setup(tile_id):
		world_stream.free()
		world_stream = null
		_unload_tile()
		status_label.text = "Ошибка данных участка " + tile_id
		return
	tile_root.add_child(world_stream)
	current_mesh.material_override = world_stream.far_material()
	world_stream.set_process(mode == "walk" and not _suspended)
	pos = Vector2(lx, lz)
	world_stream.update_focus(pos, true)
	heading_deg = heading
	pitch_deg = -4.0
	eye_height = NAN
	travel_speed = 0.0
	_update_camera()
	_update_status()


func _load_region_tile(tile_id: String, point: Vector2, heading: float) -> void:
	current_tile = TileLoader.tile_world(tile_id)
	if current_tile.is_empty():
		return
	if world_stream == null:
		world_stream = WorldStream.new()
		if not world_stream.setup_region(region, point):
			world_stream.free()
			world_stream = null
			status_label.text = "Ошибка данных региона"
			return
		tile_root.add_child(world_stream)
		var mi := MeshInstance3D.new()
		mi.mesh = TileLoader.mesh_from_arrays(TileLoader.load_vertex_arrays(TileLoader.ROUTE_DIR + "region.far.bin.gz", 200000))
		mi.material_override = world_stream.far_material()
		tile_root.add_child(mi)
		current_mesh = mi
	pos = point
	world_stream.update_focus(pos, true)
	world_stream.set_process(mode == "walk" and not _suspended)
	heading_deg = heading
	pitch_deg = -4.0
	eye_height = NAN
	travel_speed = 0.0
	_update_camera()
	_update_status()


func _ensure_region_page(point: Vector2) -> bool:
	if not _inside_region(point):
		return false
	var page := _tile_at(point)
	if page.is_empty():
		return false
	if str(page["id"]) != str(current_tile.get("id", "")):
		current_tile = TileLoader.tile_world(str(page["id"]))
	return not current_tile.is_empty()


func _unload_tile() -> void:
	if world_stream != null:
		world_stream.stop()
		world_stream.free()
		world_stream = null
	if current_mesh != null:
		current_mesh.free()
		current_mesh = null
	current_tile = {}


func _process(frame_delta: float) -> void:
	var delta := minf(frame_delta, 0.1)
	if mode != "walk" or current_tile.is_empty():
		return
	if world_stream != null:
		world_stream.update_focus(pos)
	var speed: float = SPEEDS[speed_index]
	var previous_pos := pos
	if forward_input != 0.0:
		var steps := maxi(1, int(ceil(speed * delta / 0.2)))
		var step_dt := delta / steps
		var rad := deg_to_rad(heading_deg)
		for i in range(steps):
			var nx: float = pos.x + sin(rad) * forward_input * speed * step_dt
			var nz: float = pos.y - cos(rad) * forward_input * speed * step_dt
			if region.has("spatial_grid") and not _ensure_region_page(Vector2(nx, nz)):
				break
			if snap_enabled:
				var snapped := _snap_to_roads(Vector2(nx, nz))
				nx = snapped.x
				nz = snapped.y
			if region.has("spatial_grid") and not _ensure_region_page(Vector2(nx, nz)):
				break
			if TileLoader.blocked(current_tile, nx, nz):
				if region.has("spatial_grid"):
					_ensure_region_page(pos)
				break
			pos = Vector2(nx, nz)
	if turn_input != 0.0:
		heading_deg += turn_input * 78.0 * delta
	travel_speed = minf(speed * 3.6, pos.distance_to(previous_pos) / maxf(delta, 0.001) * 3.6)
	_update_camera(delta)
	status_time += delta
	if status_time >= 0.1:
		status_time = 0.0
		_update_status()


func _update_camera(delta: float = 0.0) -> void:
	var reference := eye_height - 1.9 if not is_nan(eye_height) else NAN
	var target := TileLoader.pavement_height(current_tile, pos.x, pos.y, reference) + 1.9
	if is_nan(eye_height) or delta <= 0.0 or absf(target - eye_height) > 3.0:
		eye_height = target
	else:
		eye_height = lerpf(eye_height, target, 1.0 - exp(-14.0 * delta))
	var eye := eye_height
	camera.position = Vector3(pos.x, eye, pos.y)
	var yaw := deg_to_rad(heading_deg)
	var pitch := deg_to_rad(pitch_deg)
	var look := Vector3(pos.x + sin(yaw) * cos(pitch), eye + sin(pitch), pos.y - cos(yaw) * cos(pitch))
	camera.look_at(look, Vector3.UP)


func _update_status() -> void:
	if current_tile.is_empty():
		return
	var tile_name := str(current_tile.get("name", "ЮБК"))
	var route_m := _route_m_from_local()
	var place := _nearest_place_at(pos) if region.has("spatial_grid") else _nearest_place(route_m, 8000.0)
	var place_name: String = str(place.get("name", "ЮБК")) if not place.is_empty() else "ЮБК"
	status_label.text = "%s\nУлицы и окрестности" % place_name if region.has("spatial_grid") else "%s\n%s · %.1f км" % [tile_name, str(place_name), route_m / 1000.0]
	instruments.update_state(current_tile, pos, heading_deg, travel_speed, route_m, SPEED_LABELS[speed_index])


func _route_m_from_local() -> float:
	# Approximate route metre by projecting the current local position back to global
	# coordinates and finding the nearest route point.
	if current_tile.is_empty():
		return 0.0
	var origin = current_tile.get("origin", [ORIGIN_LAT, ORIGIN_LON])
	var gx := pos.x + (float(origin[1]) - ORIGIN_LON) * SX
	var gz := pos.y + (ORIGIN_LAT - float(origin[0])) * SZ
	return _route_m_at(gx, gz)


func _route_m_at(x: float, z: float) -> float:
	var route = region.get("route", [])
	if route.size() < 2:
		return 0.0
	var best := 0.0
	var best_d := 1.0e18
	for i in range(route.size() - 1):
		var a = route[i]
		var b = route[i + 1]
		var ax := float(a[0])
		var az := float(a[1])
		var cx := float(b[0])
		var cz := float(b[1])
		var vx := cx - ax
		var vz := cz - az
		var len := vx * vx + vz * vz
		var t := 0.0
		if len > 0.0:
			t = clampf(((x - ax) * vx + (z - az) * vz) / len, 0.0, 1.0)
		var px := ax + t * vx
		var pz := az + t * vz
		var d := (x - px) * (x - px) + (z - pz) * (z - pz)
		if d < best_d:
			best_d = d
			best = float(a[3]) + (float(b[3]) - float(a[3])) * t
	return best


func _snap_to_roads(p: Vector2) -> Vector2:
	var roads := TileLoader.nearby(current_tile, "road", p.x, p.y)
	if roads.is_empty():
		return p
	var best := 42.0 * 42.0
	var bx := p.x
	var bz := p.y
	for seg in roads:
		var ax := float(seg[0])
		var az := float(seg[1])
		var cx := float(seg[2])
		var cz := float(seg[3])
		var vx := cx - ax
		var vz := cz - az
		var len := vx * vx + vz * vz
		if len < 1.0:
			continue
		var t := clampf(((p.x - ax) * vx + (p.y - az) * vz) / len, 0.0, 1.0)
		var qx := ax + t * vx
		var qz := az + t * vz
		var dx := p.x - qx
		var dz := p.y - qz
		var d := dx * dx + dz * dz
		if d < best:
			best = d
			bx = qx
			bz = qz
	if best < 42.0 * 42.0:
		return Vector2(bx, bz)
	return p


func _unhandled_input(event: InputEvent) -> void:
	if mode != "walk":
		return
	if event is InputEventScreenDrag:
		heading_deg += event.relative.x * 0.2
		pitch_deg = clampf(pitch_deg - event.relative.y * 0.2, -75.0, 75.0)
