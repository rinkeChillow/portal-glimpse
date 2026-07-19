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
// 1 = screen-door DISSOLVE (RTT path: an opaque gbuffer can't blend alpha, so fade by discarding an ordered
// fraction of pixels); 0 = smooth alpha (the blended overlay / non-shader paths, unchanged).
uniform float DitherFade;

// Interior-mapping parameters (design doc §4.1): the panorama is treated as a sphere of radius
// SphereRadius centered at PortalCenter (both camera-relative). We intersect the view ray with
// that sphere and sample by the direction from the centre to the exit point — so the portal shows
// the forward view in the middle and the sides at the edges, with real parallax as the player moves.
uniform vec3 PortalCenter;
uniform float SphereRadius;

in vec3 rayDir;

out vec4 fragColor;

// 4x4 ordered (Bayer) dither threshold in [0,1) for the fragment's screen pixel.
float bayer4x4(vec2 p) {
	int i = int(mod(p.x, 4.0)) + int(mod(p.y, 4.0)) * 4;
	float m[16] = float[16](
		0.0,  8.0,  2.0,  10.0,
		12.0, 4.0,  14.0, 6.0,
		3.0,  11.0, 1.0,  9.0,
		15.0, 7.0,  13.0, 5.0);
	return (m[i] + 0.5) / 16.0;
}

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
	vec3 rayFromEye = normalize(rayDir);

	// Ray (origin = camera) vs sphere (centre = PortalCenter, radius = SphereRadius). Take the far
	// intersection — the wall of the room behind the portal.
	float b = dot(rayFromEye, PortalCenter);
	float disc = b * b - (dot(PortalCenter, PortalCenter) - SphereRadius * SphereRadius);

	// Ray misses the sphere → draw NOTHING. The old code fell back to `dir = rayFromEye`, which
	// samples the cubemap like a skybox and painted a second, full-size copy of the panorama across
	// the whole portal whenever the sphere was smaller than the portal (i.e. the camera sat outside
	// it). Discarding those fragments leaves only the single interior-mapped sphere.
	if (disc <= 0.0) {
		discard;
	}

	float t = b + sqrt(disc);
	vec3 dir = t * rayFromEye - PortalCenter;
	vec3 col = sampleCube(dir).rgb;

	if (DitherFade > 0.5) {
		// RTT: dissolve — keep a GlimpseAlpha fraction of pixels, discard the rest (opaque, alpha = 1).
		// Hard floor first: below it, drop EVERYTHING so the fade reaches true zero. Without this the
		// ordered dither still keeps ~1/16 of pixels at very low alpha, and the shaderpack's bloom smears
		// those few bright pixels into a haze that never fully clears (the "minimum opacity" floor). The
		// upper end is likewise snapped to fully-opaque so a full fade-in doesn't leave dither holes.
		if (GlimpseAlpha < 0.06) {
			discard;
		}
		if (GlimpseAlpha < 0.98 && GlimpseAlpha < bayer4x4(gl_FragCoord.xy)) {
			discard;
		}
		fragColor = vec4(col, 1.0);
	} else {
		fragColor = vec4(col, GlimpseAlpha); // blended paths: smooth alpha fade
	}
}
