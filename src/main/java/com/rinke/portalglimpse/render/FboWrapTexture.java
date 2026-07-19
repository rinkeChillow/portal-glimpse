package com.rinke.portalglimpse.render;

import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.resource.ResourceManager;

/**
 * A {@link net.minecraft.client.texture.Texture} whose GL id IS a framebuffer's colour attachment, so
 * an offscreen render target can be sampled by a normal {@code RenderLayer} (and therefore patched by
 * Iris into its gbuffers). Owns nothing — the framebuffer manages the attachment's lifecycle.
 */
public final class FboWrapTexture extends AbstractTexture {

	private final Framebuffer fbo;

	public FboWrapTexture(Framebuffer fbo) {
		this.fbo = fbo;
	}

	@Override
	public int getGlId() {
		return fbo.getColorAttachment();
	}

	@Override
	public void load(ResourceManager manager) {
	}

	@Override
	public void close() {
		// The framebuffer owns the texture; don't let the texture manager delete it.
	}
}
