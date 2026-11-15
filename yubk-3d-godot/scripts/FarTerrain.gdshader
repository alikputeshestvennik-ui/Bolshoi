shader_type spatial;
render_mode unshaded, cull_disabled;

uniform sampler2D coverage : filter_nearest, repeat_disable;
uniform vec2 grid_origin;
uniform float cell_m = 128.0;
varying vec2 terrain_xz;

void vertex() {
    terrain_xz = VERTEX.xz;
}

void fragment() {
    ivec2 cell = ivec2(floor(terrain_xz / cell_m - grid_origin));
    ivec2 extent = textureSize(coverage, 0);
    if (all(greaterThanEqual(cell, ivec2(0))) && all(lessThan(cell, extent))) {
        if (texelFetch(coverage, cell, 0).r > 0.5) {
            discard;
        }
    }
    ALBEDO = COLOR.rgb;
}
