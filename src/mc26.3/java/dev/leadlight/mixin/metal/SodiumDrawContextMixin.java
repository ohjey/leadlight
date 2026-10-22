package dev.leadlight.mixin.metal;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.leadlight.render.TerrainDraw;
import net.caffeinemc.mods.sodium.client.gpu.device.context.GLDrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds the terrain program's uniform block and samplers into the pass Sodium has just opened, as
 * {@code VKDrawContextMixin} does on Vulkan. Sodium for 26.3 draws through its OpenGL context on any
 * device that is not Vulkan's, Metallum 0.0.25's included ({@code ChunkBackend}), so that is the
 * context whose {@code setContext} the bind sits on here.
 */
@Mixin(value = GLDrawContext.class, remap = false)
public abstract class SodiumDrawContextMixin {

	@Inject(method = "setContext", at = @At("HEAD"), require = 1)
	private void leadlight$bind(RenderPass pass, RenderPipeline pipeline, CallbackInfo callback) {
		TerrainDraw.bind(pass, pipeline);
	}
}
