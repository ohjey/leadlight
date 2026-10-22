package dev.leadlight.mixin.metal;

import com.llamalad7.mixinextras.sugar.Local;
import com.metallum.mtl.MTLDevice;
import com.metallum.mtl.MTLPixelFormat;
import com.metallum.mtl.MTLRenderPipelineDescriptor;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.leadlight.metal.MultiTarget;
import dev.leadlight.metal.PipelineTargets;
import dev.leadlight.metal.SamplerSlots;
import dev.leadlight.render.ShadowCompare;
import com.metallum.render.LeadlightMetalBridge;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.foreign.MemorySegment;

/**
 * Builds a pipeline for every colour target it declares rather than for target nought alone
 * ({@link PipelineTargets}), and keeps the sampler slots its stages were cross compiled with
 * ({@link SamplerSlots}).
 */
@Mixin(targets = "com.metallum.render.MetalCompiledRenderPipeline", remap = false)
public abstract class MetalCompiledRenderPipelineMixin implements SamplerSlots.Holder, MultiTarget.Pipeline {

	@Unique
	private byte @Nullable [] leadlight$samplerSlots;

	@Unique
	private @Nullable PipelineTargets leadlight$targets;

	@Unique
	private @Nullable RenderPipeline leadlight$info;

	/** Bits of the resources read through a shadow comparison, or -1 until first asked. */
	@Unique
	private long leadlight$compared = -1L;

	@Shadow
	abstract MemorySegment getNativePipeline(boolean depth);

	@Redirect(method = "createPipeline", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/metallum/mtl/MTLDevice;newRenderPipelineState("
							+ "Lcom/metallum/mtl/MTLRenderPipelineDescriptor;)Ljava/lang/foreign/MemorySegment;"))
	private static MemorySegment leadlight$allTargets(MTLDevice device, MTLRenderPipelineDescriptor descriptor,
			@Local(argsOnly = true) RenderPipeline pipeline,
			@Local(argsOnly = true, ordinal = 1) MTLPixelFormat depthFormat) {
		PipelineTargets.beforeBuild(pipeline, device, descriptor, depthFormat != MTLPixelFormat.Invalid);
		return device.newRenderPipelineState(descriptor);
	}

	@Redirect(method = "createPipeline", require = 1,
			at = @At(value = "INVOKE", target = "Lcom/metallum/mtl/MTLRenderPipelineDescriptor;close()V"))
	private static void leadlight$keepDescriptor(MTLRenderPipelineDescriptor descriptor) {
		if (!PipelineTargets.kept(descriptor)) {
			descriptor.close();
		}
	}

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void leadlight$built(CallbackInfo callback, @Local(argsOnly = true) RenderPipeline pipeline) {
		this.leadlight$samplerSlots = SamplerSlots.take();
		this.leadlight$targets = PipelineTargets.take(pipeline);
		this.leadlight$info = pipeline;
	}

	@Override
	public long leadlight$comparedMask() {
		long mask = this.leadlight$compared;
		if (mask < 0L) {
			RenderPipeline info = this.leadlight$info;
			java.util.Set<String> names = info == null ? java.util.Set.of() : ShadowCompare.compared(info);
			mask = names.isEmpty() ? 0L : LeadlightMetalBridge.bindingMask(this, names);
			this.leadlight$compared = mask;
		}
		return mask;
	}

	@Override
	public byte @Nullable [] leadlight$samplerSlots() {
		return this.leadlight$samplerSlots;
	}

	@Override
	public MemorySegment leadlight$nativePipeline(boolean depth, long[] formats) {
		PipelineTargets targets = this.leadlight$targets;
		if (targets == null || targets.matches(formats)) {
			return this.getNativePipeline(depth);
		}
		return targets.variant(depth, formats);
	}

	@Inject(method = "close", at = @At("HEAD"), require = 1)
	private void leadlight$close(CallbackInfo callback) {
		if (this.leadlight$targets != null) {
			this.leadlight$targets.close();
			this.leadlight$targets = null;
		}
	}
}
