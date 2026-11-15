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


func _ready() -> void:
	randomize()
	region = TileLoader.load_region()
	_build_world()
	_build_ui()

	map.setup(region)
	map.visible = true
	hud.visible = false
	teleport_panel.visible = false
	loading_label.visible = false

	if region.is_empty():
		status_label.text = "Ошибка загрузки region.json"
		map.visible = false
		loading_label.visible = true
		loading_label.text = "Нет данных региона"


func _build_world() -> void:
	var environment := Environment.new()
	environment.background_mode = Environment.BG_COLOR
	environment.background_color = Color(0.62, 0.77, 0.86, 1.0)
	environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	environment.ambient_light_color = Color(0.9, 0.93, 0.95)
	environment.ambient_light_energy = 1.1
	environment.fog_enabled = true
	environment.fog_light_color = Color(0.62, 0.77, 0.86)
	environment.fog_density = 0.00045
	env = WorldEnvironment.new()
	env.environment = environment
	add_child(env)

	camera = Camera3D.new()
	camera.fov = 65.0
	camera.near = 0.4
	camera.far = 2400.0
	add_child(camera)

	tile_root = Node3D.new()
	add_child(tile_root)


func _build_ui() -> void:
	var ui := CanvasLayer.new()
	add_child(ui)

	map = RegionMap.new()
	map.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	map.mouse_filter = Control.MOUSE_FILTER_STOP
	map.tapped.connect(_on_map_tapped)
	ui.add_child(map)

	hud = Control.new()
	hud.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	hud.mouse_filter = Control.MOUSE_FILTER_IGNORE
	ui.add_child(hud)

	loading_label = _label("Загрузка участка…", 22, true)
	loading_label.set_anchors_and_offsets_preset(Control.PRESET_CENTER)
	loading_label.grow_horizontal = Control.GROW_DIRECTION_BOTH
	loading_label.grow_vertical = Control.GROW_DIRECTION_BOTH
	hud.add_child(loading_label)

	status_label = _label("", 16, true)
	status_label.set_anchors_preset(Control.PRESET_TOP_LEFT)
	status_label.offset_left = 16.0
	status_label.offset_top = 16.0
	status_label.offset_right = 680.0
	status_label.offset_bottom = 96.0
	status_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	hud.add_child(status_label)

	var map_btn := Button.new()
	map_btn.text = "Карта"
	map_btn.set_anchors_preset(Control.PRESET_TOP_RIGHT)
	map_btn.offset_left = -140.0
	map_btn.offset_top = 16.0
	map_btn.offset_right = -16.0
	map_btn.offset_bottom = 76.0
	map_btn.pressed.connect(_on_map_button)
	hud.add_child(map_btn)

	speed_button = Button.new()
	speed_button.text = SPEED_LABELS[speed_index]
	speed_button.set_anchors_preset(Control.PRESET_TOP_RIGHT)
	speed_button.offset_left = -320.0
	speed_button.offset_top = 16.0
	speed_button.offset_right = -152.0
	speed_button.offset_bottom = 76.0
	speed_button.pressed.connect(_on_speed_button)
	hud.add_child(speed_button)

	snap_button = Button.new()
	snap_button.text = "По дорогам"
	snap_button.set_anchors_preset(Control.PRESET_TOP_RIGHT)
	snap_button.offset_left = -500.0
	snap_button.offset_top = 16.0
	snap_button.offset_right = -332.0
	snap_button.offset_bottom = 76.0
	snap_button.pressed.connect(_on_snap_button)
	hud.add_child(snap_button)

	_add_dpad_button("Вперёд", Control.PRESET_BOTTOM_LEFT, Vector2(20, -210), Vector2(150, -150), "fwd")
	_add_dpad_button("Назад", Control.PRESET_BOTTOM_LEFT, Vector2(20, -70), Vector2(150, -10), "back")
	_add_dpad_button("Влево", Control.PRESET_BOTTOM_LEFT, Vector2(170, -140), Vector2(290, -80), "left")
	_add_dpad_button("Вправо", Control.PRESET_BOTTOM_LEFT, Vector2(320, -140), Vector2(440, -80), "right")

	teleport_panel = PanelContainer.new()
	teleport_panel.set_anchors_preset(Control.PRESET_BOTTOM_WIDE)
	teleport_panel.offset_left = 16.0
	teleport_panel.offset_top = -210.0
	teleport_panel.offset_right = -16.0
	teleport_panel.offset_bottom = -16.0
	ui.add_child(teleport_panel)

	var vb := VBoxContainer.new()
	teleport_panel.add_child(vb)
	teleport_label = _label("", 15, false)
	teleport_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	vb.add_child(teleport_label)
	teleport_button = Button.new()
	teleport_button.text = "Перейти на участок"
	teleport_button.custom_minimum_size = Vector2(0, 64)
	teleport_button.pressed.connect(_on_teleport_pressed)
	vb.add_child(teleport_button)
	teleport_panel.visible = false


func _label(text: String, size_px: int, bold: bool) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size_px)
	l.add_theme_color_override("font_color", Color(0.93, 0.97, 0.93))
	return l


func _add_dpad_button(text: String, preset: int, a: Vector2, b: Vector2, action: String) -> void:
	var btn := Button.new()
	btn.text = text
	btn.set_anchors_preset(preset)
	btn.offset_left = a.x
	btn.offset_top = a.y
	btn.offset_right = b.x
	btn.offset_bottom = b.y
	btn.custom_minimum_size = Vector2(b.x - a.x, b.y - a.y)
	btn.button_down.connect(_on_dpad_down.bind(action))
	btn.button_up.connect(_on_dpad_up.bind(action))
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
	var place := _nearest_place(c.get("route_m", 0.0), 7000.0)
	var name := str(c.get("tile_name", "ЮБК"))
	var km := float(c.get("route_m", 0.0)) / 1000.0
	var place_name := place.get("name", "Южнобережное шоссе") if not place.is_empty() else "Южнобережное шоссе"
	return "%s · %.1f км от Севастополя\n%s" % [name, km, str(place_name)]


func _candidate_at(x: float, z: float) -> Dictionary:
	var route = region.get("route", [])
	if route.size() < 2:
		return {}
	var best_d := 900.0 * 900.0
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
			bx = px
			bz = pz
			bh = float(a[2]) + (float(b[2]) - float(a[2])) * t
			bm = float(a[3]) + (float(b[3]) - float(a[3])) * t
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
	mode = "map"
	hud.visible = false
	teleport_panel.visible = false
	loading_label.visible = false
	map.visible = true
	_unload_tile()


func _show_walk() -> void:
	mode = "walk"
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
	pos = Vector2(lx, lz)
	heading_deg = heading
	pitch_deg = 0.0
	_update_camera()


func _unload_tile() -> void:
	if current_mesh != null:
		current_mesh.queue_free()
		current_mesh = null
	current_tile = {}


func _process(delta: float) -> void:
	if mode != "walk" or current_tile.is_empty():
		return
	var speed := SPEEDS[speed_index]
	if forward_input != 0.0:
		var steps := maxi(1, int(ceil(speed * delta / 0.2)))
		var step_dt := delta / steps
		var rad := deg_to_rad(heading_deg)
		for i in range(steps):
			var nx := pos.x + sin(rad) * forward_input * speed * step_dt
			var nz := pos.y - cos(rad) * forward_input * speed * step_dt
			if snap_enabled:
				var snapped := _snap_to_roads(Vector2(nx, nz))
				nx = snapped.x
				nz = snapped.y
			if TileLoader.blocked(current_tile, nx, nz):
				break
			pos = Vector2(nx, nz)
	if turn_input != 0.0:
		heading_deg += turn_input * 78.0 * delta
	_update_camera()
	_update_status()


func _update_camera() -> void:
	var eye := TileLoader.ground_height(current_tile, pos.x, pos.y) + 1.9
	camera.position = Vector3(pos.x, eye, pos.y)
	var yaw := deg_to_rad(heading_deg)
	var pitch := deg_to_rad(pitch_deg)
	var look := Vector3(pos.x + sin(yaw) * cos(pitch), eye + sin(pitch), pos.y - cos(yaw) * cos(pitch))
	camera.look_at(look, Vector3.UP)


func _update_status() -> void:
	if current_tile.is_empty():
		return
	var tile_name := str(current_tile.get("name", "ЮБК"))
	var place := _nearest_place(_route_m_from_local(), 8000.0)
	var place_name := place.get("name", "ЮБК") if not place.is_empty() else "ЮБК"
	status_label.text = "%s\n%s · %.1f км" % [tile_name, str(place_name), _route_m_from_local() / 1000.0]


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
	var roads = current_tile.get("roads", [])
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
		heading_deg -= event.relative.x * 0.35
		pitch_deg = clampf(pitch_deg - event.relative.y * 0.35, -75.0, 75.0)
