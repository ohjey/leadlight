package dev.leadlight.mixin.game;

import dev.leadlight.render.PackChain;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Points the main target's colour at {@link PackChain#openModDraws()}'s layer for the length of
 * the game's outline phase, and composes what landed there once it is the game's own again.
 * <p>
 * The phase itself draws nothing on the main target: the game's glowing outlines go to a target of
 * their own. What draws on the main target inside it is a mod, by name: Litematica draws its
 * schematic at the head of this method and its overlay at the tail, each through a pass it opens on
 * the main target's colour and depth. Wrapping the whole method takes both, wherever another mod's
 * injection sits in it, and nothing else. Only the colour moves; the depth is the world's, so the
 * schematic still hides behind terrain and the terrain behind it.
 * <p>
 * Put back in a finally, before the composition: a main target left pointing at the layer would
 * take the rest of the frame with it, the pack's final included.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererModDrawsMixin {

	@WrapMethod(method = "executeOutline", require = 1)
	private void leadlight$modDraws(FeatureRenderDispatcher.PreparedFrame frame,
			Operation<Void> original) {
		TextureTarget layer = PackChain.openModDraws();
		RenderTarget main = layer == null ? null : Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (main == null) {
			original.call(frame);

			return;
		}

		RenderTargetAccessor target = (RenderTargetAccessor) main;
		GpuTexture texture = target.leadlight$colorTexture();
		GpuTextureView view = target.leadlight$colorTextureView();
		target.leadlight$setColorTexture(layer.getColorTexture());
		target.leadlight$setColorTextureView(layer.getColorTextureView());
		try {
			original.call(frame);
		} finally {
			target.leadlight$setColorTexture(texture);
			target.leadlight$setColorTextureView(view);
		}

		PackChain.closeModDraws();
	}
}
