#version 150

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

// Camera-relative world position of this vertex = the view ray from the eye to the portal
// surface. It's bob-free (both endpoints are), so the sampled content stays glued to the (bobbed)
// quad instead of swimming — the panorama tracks the frame like the postcard does. Interpolated
// across the quad it becomes the per-fragment view direction (design doc §4.1).
out vec3 rayDir;

void main() {
	gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
	rayDir = Position;
}
