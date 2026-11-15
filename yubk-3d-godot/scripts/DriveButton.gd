class_name DriveButton
extends Button

signal held
signal released

# Handle each finger directly, so moving and turning work simultaneously.
var finger := -1
var mouse_held := false


func _ready() -> void:
	visibility_changed.connect(_cancel)


func _gui_input(event: InputEvent) -> void:
	if event is InputEventScreenTouch and event.pressed and finger == -1:
		finger = event.index
		button_pressed = true
		held.emit()
		accept_event()
	elif event is InputEventScreenDrag and event.index == finger:
		accept_event()
	elif event is InputEventMouseButton and event.device != InputEvent.DEVICE_ID_EMULATION and event.button_index == MOUSE_BUTTON_LEFT:
		mouse_held = event.pressed
		button_pressed = mouse_held
		if mouse_held:
			held.emit()
		else:
			released.emit()
		accept_event()


func _input(event: InputEvent) -> void:
	if event is InputEventScreenTouch and not event.pressed and event.index == finger:
		_cancel()
		get_viewport().set_input_as_handled()
	elif event is InputEventMouseButton and not event.pressed and mouse_held:
		_cancel()


func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_OUT:
		_cancel()


func _cancel() -> void:
	if finger != -1 or mouse_held:
		finger = -1
		mouse_held = false
		button_pressed = false
		released.emit()
