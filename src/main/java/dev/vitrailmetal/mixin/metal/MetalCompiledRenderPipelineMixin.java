package dev.vitrailmetal.mixin.metal;

import com.llamalad7.mixinextras.sugar.Local;
import com.metallum.mtl.MTLDevice;
import com.metallum.mtl.MTLPixelFormat;
import com.metallum.mtl.MTLRenderPipelineDescriptor;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.vitrailmetal.metal.MultiTarget;
import dev.vitrailmetal.metal.PipelineTargets;
import dev.vitrailmetal.metal.SamplerSlots;
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
	private byte @Nullable [] vitrail$samplerSlots;

	@Unique
	private @Nullable PipelineTargets vitrail$targets;

	@Shadow
	abstract MemorySegment getNativePipeline(boolean depth);

	@Redirect(method = "createPipeline", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/metallum/mtl/MTLDevice;newRenderPipelineState("
							+ "Lcom/metallum/mtl/MTLRenderPipelineDescriptor;)Ljava/lang/foreign/MemorySegment;"))
	private static MemorySegment vitrail$allTargets(MTLDevice device, MTLRenderPipelineDescriptor descriptor,
			@Local(argsOnly = true) RenderPipeline pipeline,
			@Local(argsOnly = true, ordinal = 1) MTLPixelFormat depthFormat) {
		PipelineTargets.beforeBuild(pipeline, device, descriptor, depthFormat != MTLPixelFormat.Invalid);
		return device.newRenderPipelineState(descriptor);
	}

	@Redirect(method = "createPipeline", require = 1,
			at = @At(value = "INVOKE", target = "Lcom/metallum/mtl/MTLRenderPipelineDescriptor;close()V"))
	private static void vitrail$keepDescriptor(MTLRenderPipelineDescriptor descriptor) {
		if (!PipelineTargets.kept(descriptor)) {
			descriptor.close();
		}
	}

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void vitrail$built(CallbackInfo callback, @Local(argsOnly = true) RenderPipeline pipeline) {
		this.vitrail$samplerSlots = SamplerSlots.take();
		this.vitrail$targets = PipelineTargets.take(pipeline);
	}

	@Override
	public byte @Nullable [] vitrail$samplerSlots() {
		return this.vitrail$samplerSlots;
	}

	@Override
	public MemorySegment vitrail$nativePipeline(boolean depth, long[] formats) {
		PipelineTargets targets = this.vitrail$targets;
		if (targets == null || targets.matches(formats)) {
			return this.getNativePipeline(depth);
		}
		return targets.variant(depth, formats);
	}

	@Inject(method = "close", at = @At("HEAD"), require = 1)
	private void vitrail$close(CallbackInfo callback) {
		if (this.vitrail$targets != null) {
			this.vitrail$targets.close();
			this.vitrail$targets = null;
		}
	}
}
