#version 150

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

// Camera-relative world position of this vertex on the portal plane. It's world-axis aligned (the
// Java emits vertices as worldPos - cameraPos, no rotation) and bob-free, so the fragment shader can
// work out where on the opening each fragment sits and build the sample direction from that (design
// doc §4.1) — independent of the viewer's distance, which is what stops the far-away telephoto zoom.
out vec3 worldRel;

void main() {
	gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
	worldRel = Position;
}
