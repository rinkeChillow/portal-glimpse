#version 150

in vec3 Position;

// Camera basis captured at the moment of portal entry (world axes) plus the FOV half-angle tangents.
uniform vec3 Forward;
uniform vec3 Right;
uniform vec3 Up;
uniform float TanX;
uniform float TanY;

out vec3 rayDir;

void main() {
	// Position is a full-screen quad in clip space (corners at ±1); pass it straight through.
	gl_Position = vec4(Position.xy, 0.0, 1.0);
	// The view ray to this screen corner is linear in the screen coordinates for a pinhole camera,
	// so interpolating the corner rays gives the exact per-fragment direction (design doc §4.1).
	rayDir = Forward + Position.x * TanX * Right + Position.y * TanY * Up;
}
