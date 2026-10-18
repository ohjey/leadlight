package dev.vitrailmetal.mixin.metal;

import com.metallum.mtl.MTLRenderCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reaches Metallum's private method that opens (or carries on) a pass's Metal encoder. */
@Mixin(targets = "com.metallum.render.MetalRenderPass", remap = false)
public interface MetalRenderPassInvoker {

	@Invoker("renderEncoder")
	MTLRenderCommandEncoder vitrail$renderEncoder();
}
