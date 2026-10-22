package dev.leadlight.mixin.metal;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets Metallum 0.0.25 build pipelines on more than one thread. The engine compiles a pack's
 * pipelines ahead of their first draw on its own workers, as the game's frontend allows, and each
 * pipeline's constructor reaches the device's MSL function cache and depth-stencil cache, two plain
 * hash maps that a second thread breaks ({@code ConcurrentModificationException} from
 * {@code getOrCompileFunction}). A concurrent map still compiles each function once, and different
 * functions side by side.
 */
@Mixin(targets = "com.metallum.render.MetalDevice", remap = false)
public abstract class MetalDeviceMixin {

	@Shadow
	@Final
	@Mutable
	private Map<Object, Object> functionCache;

	@Shadow
	@Final
	@Mutable
	private Map<Long, Object> depthStencilStates;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void leadlight$concurrentCaches(CallbackInfo callback) {
		this.functionCache = new ConcurrentHashMap<>(this.functionCache);
		this.depthStencilStates = new ConcurrentHashMap<>(this.depthStencilStates);
	}
}
