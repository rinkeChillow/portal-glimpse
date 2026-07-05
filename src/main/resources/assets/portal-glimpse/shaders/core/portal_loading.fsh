#version 150

// The six captured cubemap faces (same order/orientation as portal_panorama.fsh):
//   0 = south (+Z), 1 = west (-X), 2 = north (-Z), 3 = east (+X), 4 = up (+Y), 5 = down (-Y)
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;

in vec3 rayDir;

out vec4 fragColor;

vec4 sampleCube(vec3 d) {
	// Horizontal (first) component negated vs. the textbook convention so the captured faces read
	// left-to-right (matches portal_panorama.fsh, which was validated against the debug cube).
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
	fragColor = vec4(sampleCube(normalize(rayDir)).rgb, 1.0);
}
