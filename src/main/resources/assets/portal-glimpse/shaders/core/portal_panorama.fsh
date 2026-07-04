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

// Portal frame — all camera-relative / world-axis aligned, matching the vertex position.
//   PortalCenter  : vector from the eye to the portal centre
//   PortalForward : unit direction the viewer looks THROUGH the portal (into the destination)
//   PortalRight   : unit in-plane horizontal = the viewer's right-hand direction
//   HalfSpan      : half of the opening's LARGER dimension (blocks); normalising both axes by it
//                   reproduces the postcard's cover-fit crop (short side shows less of the face)
//   Spread        : framing width. 1.0 = the postcard's cover-fit; larger fans the view wider so
//                   the destination looks SMALLER (fixes the telephoto-at-range zoom).
//   Parallax      : depth strength — how far the view swings as the eye moves off the portal axis.
//
// The old model cast the true view ray into a sphere, which tied the framing to the portal's
// on-screen angular size: far away the portal subtends a tiny angle, so it sampled a tiny cone of
// the cubemap and looked magnified. This model builds the sample direction from the fragment's
// position ON THE OPENING instead, so the framing is independent of viewer distance — the scene no
// longer zooms in as you back away — and depth comes from a bounded parallax shift, not ray/sphere
// geometry (design doc §4.1).
uniform vec3 PortalCenter;
uniform vec3 PortalForward;
uniform vec3 PortalRight;
uniform float HalfSpan;
uniform float Spread;
uniform float Parallax;

in vec3 worldRel;  // camera-relative world position of this fragment on the portal plane

out vec4 fragColor;

vec4 sampleCube(vec3 d) {
	// Horizontal (first) component of each face is negated vs. the textbook convention: the
	// captured faces (and the debug cube) read left-to-right, so this un-mirrors them.
	vec3 a = abs(d);
	if (a.x >= a.y && a.x >= a.z) {
		if (d.x > 0.0) {
			return texture(Sampler3, vec2(d.z, -d.y) / a.x * 0.5 + 0.5);  // +X east
		}
		return texture(Sampler1, vec2(-d.z, -d.y) / a.x * 0.5 + 0.5);     // -X west
	} else if (a.y >= a.z) {
		if (d.y > 0.0) {
			return texture(Sampler4, vec2(-d.x, d.z) / a.y * 0.5 + 0.5);  // +Y up
		}
		return texture(Sampler5, vec2(-d.x, -d.z) / a.y * 0.5 + 0.5);     // -Y down
	} else {
		if (d.z > 0.0) {
			return texture(Sampler0, vec2(-d.x, -d.y) / a.z * 0.5 + 0.5); // +Z south
		}
		return texture(Sampler2, vec2(d.x, -d.y) / a.z * 0.5 + 0.5);      // -Z north
	}
}

void main() {
	const vec3 up = vec3(0.0, 1.0, 0.0);

	// Where this fragment sits on the opening, in units of HalfSpan: the larger dimension's edge is
	// ±1.0, the shorter one is less (that asymmetry IS the cover-fit crop the postcard uses).
	vec3 rel = worldRel - PortalCenter;
	float lx = dot(rel, PortalRight) / HalfSpan;
	float ly = dot(rel, up) / HalfSpan;

	// Base look direction: straight through the portal, fanned across the opening by Spread. No
	// dependence on how far the eye is — the framing (and content scale) is fixed at every distance.
	vec3 baseDir = PortalForward + PortalRight * (lx * Spread) + up * (ly * Spread);

	// Parallax: the eye's offset from the portal centre, projected into the plane. Moving right
	// swings the view left, like looking through a real window — depth without the telephoto zoom.
	vec3 eyeRel = -PortalCenter;
	float ex = dot(eyeRel, PortalRight);
	float ey = dot(eyeRel, up);
	vec3 dir = baseDir - PortalRight * (ex * Parallax) - up * (ey * Parallax);

	fragColor = vec4(sampleCube(normalize(dir)).rgb, GlimpseAlpha);
}
