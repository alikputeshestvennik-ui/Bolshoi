class_name WorldStream
extends Node3D

# Fixed budgets apply to resident and in-flight data together. Decoding never
# touches the scene tree or GPU; only one small mesh is uploaded per frame.
const MAX_VERTICES := 480000
const MAX_CHUNKS := 96
const MAX_JOB_VERTICES := 18000
const DETAIL_RADIUS := 640.0
const RETAIN_MARGIN := 96.0

var chunks := {}
var _region_grid := {}
var _region_tiles := {}
var _page_key := Vector2i(100000, 100000)
var _cell_m := 128.0
var resident := {}
var desired := {}
var failed := {}
var resident_vertices := 0
var material: StandardMaterial3D
var _thread: Thread
var _in_flight := ""
var _focus := Vector2(INF, INF)
var _pending: Array[String] = []
var _stopped := false
var _cells := {}
var _coverage: Image
var _coverage_texture: ImageTexture
var _grid_origin := Vector2i.ZERO
var _coverage_dirty := false


func setup(tile_id: String) -> bool:
	var manifest := TileLoader.load_json(TileLoader.ROUTE_DIR + tile_id + ".stream.json")
	if int(manifest.get("version", 0)) != 1:
		return false
	for chunk in manifest.get("chunks", []):
		chunks[str(chunk["file"])] = chunk
	if chunks.is_empty():
		return false
	for chunk in chunks.values():
		if int(chunk.get("vertices", 0)) <= 0 or int(chunk["vertices"]) > MAX_JOB_VERTICES:
			return false
	var min_cell := Vector2i(1000000, 1000000)
	var max_cell := Vector2i(-1000000, -1000000)
	for id in chunks:
		var c: Array = chunks[id].get("cell", [])
		if c.size() != 2:
			return false
		var cell := Vector2i(int(c[0]), int(c[1]))
		if not _cells.has(cell):
			_cells[cell] = []
		_cells[cell].append(id)
		min_cell = min_cell.min(cell)
		max_cell = max_cell.max(cell)
	_grid_origin = min_cell
	var extent := max_cell - min_cell + Vector2i.ONE
	if extent.x > 256 or extent.y > 256:
		return false
	_coverage = Image.create(extent.x, extent.y, false, Image.FORMAT_R8)
	_coverage.fill(Color.BLACK)
	_coverage_texture = ImageTexture.create_from_image(_coverage)
	material = TileLoader.tile_material()
	return not chunks.is_empty()


func setup_region(data: Dictionary, focus: Vector2) -> bool:
	_region_grid = data.get("spatial_grid", {})
	if _region_grid.is_empty():
		return false
	_cell_m = float(_region_grid["cell_m"])
	for tile in data.get("tiles", []):
		_region_tiles[Vector2i(int(tile["col"]), int(tile["row"]))] = str(tile["id"])
	var page_m := float(_region_grid["page_m"])
	_grid_origin = Vector2i(int(_region_grid["min_col"] * page_m / _cell_m), int(_region_grid["min_row"] * page_m / _cell_m))
	var extent := Vector2i(int((_region_grid["max_col"] - _region_grid["min_col"]) * page_m / _cell_m), int((_region_grid["max_row"] - _region_grid["min_row"]) * page_m / _cell_m))
	if extent.x <= 0 or extent.y <= 0 or extent.x > 512 or extent.y > 512:
		return false
	_coverage = Image.create(extent.x, extent.y, false, Image.FORMAT_R8)
	_coverage.fill(Color.BLACK)
	_coverage_texture = ImageTexture.create_from_image(_coverage)
	material = TileLoader.tile_material()
	_load_neighborhood(focus)
	return true


func _load_neighborhood(focus: Vector2) -> void:
	var page_m := float(_region_grid["page_m"])
	var key := Vector2i(int(floor(focus.x / page_m)), int(floor(focus.y / page_m)))
	if key == _page_key:
		return
	_page_key = key
	chunks.clear()
	_cells.clear()
	failed.clear()
	# At most nine small manifests. Geometry stays under the same single budget
	# while crossing pages; resident jobs are retained by their stable file path.
	for col in range(key.x - 1, key.x + 2):
		for row in range(key.y - 1, key.y + 2):
			var tile_id: String = _region_tiles.get(Vector2i(col, row), "")
			if tile_id.is_empty():
				continue
			var manifest := TileLoader.load_json(TileLoader.ROUTE_DIR + tile_id + ".stream.json")
			for chunk in manifest.get("chunks", []):
				var count := int(chunk.get("vertices", 0))
				if count <= 0 or count > MAX_JOB_VERTICES:
					continue
				var id := str(chunk["file"])
				chunks[id] = chunk
				var c: Array = chunk["cell"]
				var cell := Vector2i(int(c[0]), int(c[1]))
				if not _cells.has(cell):
					_cells[cell] = []
				_cells[cell].append(id)
	_coverage_dirty = true


func far_material() -> ShaderMaterial:
	var mat := ShaderMaterial.new()
	mat.shader = preload("res://scripts/FarTerrain.gdshader")
	mat.set_shader_parameter("coverage", _coverage_texture)
	mat.set_shader_parameter("grid_origin", Vector2(_grid_origin))
	mat.set_shader_parameter("cell_m", _cell_m)
	return mat


func _update_coverage() -> void:
	# Coarse terrain must not bury the exact roads or rivers. Keep it until
	# every detailed chunk in this cell and its boundary neighbours is loaded.
	_coverage.fill(Color.BLACK)
	var loaded := {}
	for id in resident:
		var c: Array = resident[id].get_meta("cell")
		var cell := Vector2i(c[0], c[1])
		loaded[cell] = int(loaded.get(cell, 0)) + 1
	for cell in loaded:
		var complete := true
		for dx in range(-1, 2):
			for dz in range(-1, 2):
				var neighbor: Vector2i = cell + Vector2i(dx, dz)
				if int(loaded.get(neighbor, 0)) < _cells.get(neighbor, []).size():
					complete = false
		if complete:
			var pixel: Vector2i = cell - _grid_origin
			if pixel.x >= 0 and pixel.y >= 0 and pixel.x < _coverage.get_width() and pixel.y < _coverage.get_height():
				_coverage.set_pixel(pixel.x, pixel.y, Color.WHITE)
	_coverage_texture.update(_coverage)
	_coverage_dirty = false


func _distance_squared(chunk: Dictionary, focus: Vector2) -> float:
	var b: Array = chunk["bounds"]
	var closest := Vector2(clampf(focus.x, b[0], b[2]), clampf(focus.y, b[1], b[3]))
	return closest.distance_squared_to(focus)


func update_focus(focus: Vector2, force: bool = false) -> void:
	if _stopped or (not force and focus.distance_squared_to(_focus) < 24.0 * 24.0):
		return
	_focus = focus
	if not _region_grid.is_empty():
		_load_neighborhood(focus)
	var candidates: Array = []
	for id in chunks:
		if failed.has(id):
			continue
		var distance := _distance_squared(chunks[id], focus)
		var radius := DETAIL_RADIUS + (RETAIN_MARGIN if resident.has(id) else 0.0)
		if distance <= radius * radius:
			candidates.append({"id": id, "distance": distance})
	candidates.sort_custom(func(a: Dictionary, b: Dictionary) -> bool: return a["distance"] < b["distance"])
	desired.clear()
	_pending.clear()
	var budget := 0
	for candidate in candidates:
		var id := str(candidate["id"])
		var count := int(chunks[id]["vertices"])
		if budget + count > MAX_VERTICES - MAX_JOB_VERTICES or desired.size() >= MAX_CHUNKS:
			continue
		desired[id] = true
		budget += count
		if not resident.has(id):
			_pending.append(id)
	for id in resident.keys():
		if not desired.has(id):
			resident_vertices -= int(resident[id].get_meta("vertices"))
			resident[id].free()
			resident.erase(id)
			_coverage_dirty = true


func _process(_delta: float) -> void:
	if _stopped:
		return
	if _thread != null:
		if _thread.is_alive():
			return
		var arrays: Array = _thread.wait_to_finish()
		_thread = null
		var id := _in_flight
		_in_flight = ""
		if arrays.is_empty():
			failed[id] = true
		elif desired.has(id) and not resident.has(id):
			var instance := MeshInstance3D.new()
			instance.mesh = TileLoader.mesh_from_arrays(arrays)
			instance.material_override = material
			instance.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
			add_child(instance)
			instance.set_meta("vertices", int(chunks[id]["vertices"]))
			instance.set_meta("cell", chunks[id]["cell"])
			resident[id] = instance
			resident_vertices += int(chunks[id]["vertices"])
			_coverage_dirty = true
	if _coverage_dirty:
		_update_coverage()
	# There is at most one queued decode, so rapid movement and teleports never
	# leave a backlog of obsolete decompressions or mesh uploads.
	while not _pending.is_empty():
		var id: String = _pending.pop_front()
		if not desired.has(id) or resident.has(id) or failed.has(id):
			continue
		_thread = Thread.new()
		_in_flight = id
		var error := _thread.start(TileLoader.load_vertex_arrays.bind(
			TileLoader.ROUTE_DIR + str(chunks[id]["file"]), MAX_JOB_VERTICES, int(chunks[id]["vertices"])))
		if error != OK:
			_thread = null
			_in_flight = ""
			failed[id] = true
		break


func is_settled() -> bool:
	return _thread == null and _pending.is_empty()


func stop() -> void:
	_stopped = true
	set_process(false)
	_pending.clear()
	desired.clear()
	if _thread != null:
		_thread.wait_to_finish()
		_thread = null
	for instance in resident.values():
		instance.free()
	resident.clear()
	resident_vertices = 0


func _exit_tree() -> void:
	stop()
