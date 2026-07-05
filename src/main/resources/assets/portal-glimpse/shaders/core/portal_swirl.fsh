#version 150

// The block atlas; we sample the nether-portal sprite's (animated) rectangle out of it.
uniform sampler2D Sampler0;

// The current animation frame's UV rectangle within the atlas, and the veil opacity.
uniform vec2 SpriteMin;
uniform vec2 SpriteMax;
uniform float Alpha;

in vec2 screenUv;

out vec4 fragColor;

void main() {
	// One 16×16 portal frame stretched across the whole screen — like the vanilla portal loading
	// swirl. Snap to the frame's texel grid so it stays blocky (nearest-neighbour) instead of the
	// atlas's smoothing blurring it out.
	const float FRAME = 16.0;
	vec2 snapped = (floor(min(screenUv, vec2(0.99999)) * FRAME) + 0.5) / FRAME;
	vec2 atlasUv = SpriteMin + snapped * (SpriteMax - SpriteMin);
	vec4 texel = texture(Sampler0, atlasUv);
	fragColor = vec4(texel.rgb, texel.a * Alpha);
}
