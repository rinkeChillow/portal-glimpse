package com.rinke.portalglimpse.render;

import java.util.function.Function;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/**
 * The RTT glimpse's own render layer — a combination vanilla doesn't ship, assembled from vanilla phases
 * (see the access widener). It needs all three of these at once:
 *
 * <ul>
 *   <li><b>The beacon-beam PROGRAM.</b> Iris picks a shaderpack's gbuffer program from the vanilla render
 *       type, and {@code gbuffers_beaconbeam} is unlit in both packs — BSL's whole fragment shader is
 *       {@code gl_FragData[0] = albedo;}, and Photon's {@code get_material_mask()} returns material 32
 *       "full emissive". That's what stops AO creasing the box seams and gives every face identical shading.</li>
 *   <li><b>TRANSLUCENT blending.</b> The fade dissolves by {@code discard}ing pixels, leaving them transparent
 *       in the FBO; without blending those read as BLACK. Vanilla's translucent beacon-beam variant has this
 *       but drops depth.</li>
 *   <li><b>ALL_MASK.</b> Writes colour AND depth, so the glimpse keeps occluding clouds and water. Vanilla's
 *       non-translucent beacon-beam variant has this but is opaque (hence the black fade).</li>
 * </ul>
 *
 * <p>Vanilla only offers those last two as an either/or ({@code getBeaconBeam(tex, translucent)} picks
 * TRANSLUCENT+COLOR_MASK or NO_TRANSPARENCY+ALL_MASK), which is why this layer exists.
 */
public final class PortalRenderLayers {

	/** Same vertex format the entity layers use — position/colour/uv/overlay/light/normal, matching what
	 * {@code VanillaGlimpseRenderer#emitRttVertex} writes. */
	private static final VertexFormat FORMAT = VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL;

	/** Depth-writing variant — used only while the glimpse is essentially opaque. */
	private static final Function<Identifier, RenderLayer> WITH_DEPTH = Util.memoize(
			(Identifier texture) -> build(texture, RenderPhase.ALL_MASK, "portal_glimpse_unlit"));

	/** Colour-only variant — used while the glimpse is fading. {@code ALL_MASK} would write depth even for
	 * FULLY TRANSPARENT fragments (depth isn't blended), stamping the portal plane's depth over whatever is
	 * visible through the glimpse; the pack's deferred pass then shades those pixels against that bogus depth
	 * (and beaconbeam zeroes the gbuffer data buffers), so the world seen through a fading portal turned flat
	 * and unshaded. Dropping the depth write while transparent avoids that entirely. */
	private static final Function<Identifier, RenderLayer> COLOR_ONLY = Util.memoize(
			(Identifier texture) -> build(texture, RenderPhase.COLOR_MASK, "portal_glimpse_unlit_nodepth"));

	private static RenderLayer build(Identifier texture, RenderPhase.WriteMaskState writeMask, String name) {
		return RenderLayer.of(
				name,
				FORMAT,
				VertexFormat.DrawMode.QUADS,
				1536,
				false, // no crumbling overlay
				true,  // translucent: sorts with the transparent geometry
				RenderLayer.MultiPhaseParameters.builder()
						.program(RenderPhase.BEACON_BEAM_PROGRAM)
						.texture(new RenderPhase.Texture(texture, false, false))
						.transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
						.writeMaskState(writeMask)
						.depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
						.cull(RenderPhase.ENABLE_CULLING)
						.lightmap(RenderPhase.ENABLE_LIGHTMAP)
						.overlay(RenderPhase.ENABLE_OVERLAY_COLOR)
						.build(false));
	}

	private PortalRenderLayers() {
	}

	/** @param writeDepth true only when the glimpse is opaque enough that every fragment it covers is real
	 *                    content — see {@link #COLOR_ONLY} for why depth must be off while it's fading. */
	public static RenderLayer unlitGlimpse(Identifier texture, boolean writeDepth) {
		return writeDepth ? WITH_DEPTH.apply(texture) : COLOR_ONLY.apply(texture);
	}
}
