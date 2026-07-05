#version 150

in vec3 Position;

out vec2 screenUv;

void main() {
	// Full-screen quad in clip space (corners at ±1); map to 0..1 screen coordinates for tiling.
	gl_Position = vec4(Position.xy, 0.0, 1.0);
	screenUv = Position.xy * 0.5 + 0.5;
}
