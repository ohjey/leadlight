package dev.vitrailmetal.mixin.metal;

import com.metallum.render.MetalDrawContext;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import dev.vitrailmetal.render.TerrainDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds the terrain program's uniform block and samplers into the pass Sodium has just opened,
 * which is what {@code VKDrawContextMixin} does on Vulkan: under Metallum, Sodium draws through
 * Metallum's own context, which overrides the method that hook sits on.
 */
@Mixin(value = MetalDrawContext.class, remap = false)
public abstract class MetalDrawContextMixin {

	@Inject(method = "setContext", at = @At("HEAD"), require = 1)
	private void vitrail$bind(RenderPass pass, RenderPipeline pipeline, CallbackInfo callback) {
		TerrainDraw.bind(pass, pipeline);
	}
}
