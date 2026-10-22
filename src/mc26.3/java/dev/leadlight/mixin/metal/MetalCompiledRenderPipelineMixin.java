package dev.leadlight.mixin.metal;

import com.metallum.render.LeadlightMetalBridge;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.leadlight.metal.SamplerSlots;
import dev.leadlight.render.ShadowCompare;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Carries, on each of Metallum 0.0.25's pipelines, the sampler slot table its shaders were compiled
 * with and which of its bindings a pack compares against. Metallum 0.0.25 builds one native pipeline
 * for each set of attachments itself, so nothing here builds variants as the 26.2 build must.
 */
@Mixin(targets = "com.metallum.render.MetalCompiledRenderPipeline", remap = false)
public abstract class MetalCompiledRenderPipelineMixin implements SamplerSlots.Holder {

	@Unique
	private byte @Nullable [] leadlight$samplerSlots;

	@Unique
	private @Nullable String leadlight$name;

	@Unique
	private long leadlight$compared = -1L;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void leadlight$built(@Coerce Object device, BackendRenderPipeline.CreateInfo info,
			@Coerce Object compiled, CallbackInfo callback) {
		this.leadlight$samplerSlots = SamplerSlots.claim(compiled);
		this.leadlight$name = info.name();
	}

	@Override
	public long leadlight$comparedMask() {
		long mask = this.leadlight$compared;
		if (mask < 0L) {
			String name = this.leadlight$name;
			Set<String> names = name == null ? Set.of() : ShadowCompare.compared(name);
			mask = names.isEmpty() ? 0L : LeadlightMetalBridge.bindingMask(this, names);
			this.leadlight$compared = mask;
		}

		return mask;
	}

	@Override
	public byte @Nullable [] leadlight$samplerSlots() {
		return this.leadlight$samplerSlots;
	}
}
