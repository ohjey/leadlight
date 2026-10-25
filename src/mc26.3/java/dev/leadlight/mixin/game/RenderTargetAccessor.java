package dev.leadlight.mixin.game;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A target's colour image, read and set, for pointing the main target at a layer for a while. */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {

	@Accessor("colorTexture")
	GpuTexture leadlight$colorTexture();

	@Accessor("colorTexture")
	void leadlight$setColorTexture(GpuTexture texture);

	@Accessor("colorTextureView")
	GpuTextureView leadlight$colorTextureView();

	@Accessor("colorTextureView")
	void leadlight$setColorTextureView(GpuTextureView view);
}
