#version 150

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

// Camera-relative world position of this vertex — i.e. the ray from the camera to the portal
// surface. Interpolated across the quad, it becomes the per-fragment view direction we sample the
// panorama cubemap with (the "window into another world" parallax, design doc §4.1).
out vec3 rayDir;

void main() {
	gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
	rayDir = Position;
}
