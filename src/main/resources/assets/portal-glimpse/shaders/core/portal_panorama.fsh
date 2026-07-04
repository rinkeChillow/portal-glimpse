#version 150

// The six captured cubemap faces (design doc §3.2). Face order matches the capture yaw/pitch:
//   0 = south (+Z), 1 = west (-X), 2 = north (-Z), 3 = east (+X), 4 = up (+Y), 5 = down (-Y)
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;

uniform float GlimpseAlpha;

// Interior-mapping parameters (design doc §4.1): the panorama is treated as a sphere of radius
// SphereRadius centered at PortalCenter (both camera-relative). We intersect the view ray with
// that sphere and sample by the direction from the centre to the exit point — so the portal shows
// the forward view in the middle and the sides at the edges, with real parallax as the player moves.
uniform vec3 PortalCenter;
uniform float SphereRadius;

in vec3 rayDir;

out vec4 fragColor;

vec4 sampleCube(vec3 d) {
	vec3 a = abs(d);
	if (a.x >= a.y && a.x >= a.z) {
		if (d.x > 0.0) {
			return texture(Sampler3, vec2(-d.z, -d.y) / a.x * 0.5 + 0.5); // +X east
		}
		return texture(Sampler1, vec2(d.z, -d.y) / a.x * 0.5 + 0.5);     // -X west
	} else if (a.y >= a.z) {
		if (d.y > 0.0) {
			return texture(Sampler4, vec2(d.x, d.z) / a.y * 0.5 + 0.5);  // +Y up
		}
		return texture(Sampler5, vec2(d.x, -d.z) / a.y * 0.5 + 0.5);     // -Y down
	} else {
		if (d.z > 0.0) {
			return texture(Sampler0, vec2(d.x, -d.y) / a.z * 0.5 + 0.5); // +Z south
		}
		return texture(Sampler2, vec2(-d.x, -d.y) / a.z * 0.5 + 0.5);    // -Z north
	}
}

void main() {
	vec3 rayFromEye = normalize(rayDir);

	// Ray (origin = camera) vs sphere (centre = PortalCenter, radius = SphereRadius). Take the far
	// intersection — the wall of the room behind the portal.
	float b = dot(rayFromEye, PortalCenter);
	float disc = b * b - (dot(PortalCenter, PortalCenter) - SphereRadius * SphereRadius);

	vec3 dir;
	if (disc <= 0.0) {
		dir = rayFromEye; // degenerate (ray misses the room) — fall back to the plain view ray
	} else {
		float t = b + sqrt(disc);
		dir = t * rayFromEye - PortalCenter;
	}

	fragColor = vec4(sampleCube(dir).rgb, GlimpseAlpha);
}
