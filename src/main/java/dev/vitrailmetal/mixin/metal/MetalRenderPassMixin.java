package dev.vitrailmetal.mixin.metal;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.mtl.MTLPixelFormat;
import com.metallum.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.vitrailmetal.metal.MultiTarget;
import dev.vitrailmetal.metal.SamplerSlots;
import dev.vitrailmetal.render.storage.StorageImages;
import com.metallum.render.VitrailMetalBridge;
import dev.vitrailmetal.metal.MetalHost;
import dev.vitrailmetal.render.ShadowCompare;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.foreign.MemorySegment;

/**
 * What a pass needs to draw a shader pack on Metal and Metallum does not do: draw into several
 * colour targets at once ({@link MultiTarget}), and bind each sampler at the slot its stage was
 * compiled to read it from ({@link SamplerSlots}).
 */
@Mixin(targets = "com.metallum.render.MetalRenderPass", remap = false)
public abstract class MetalRenderPassMixin implements MultiTarget.Pass {

	/** Metallum's own stage bits on a resource binding. */
	@Unique
	private static final int STAGE_VERTEX = 1;

	@Unique
	private static final int STAGE_FRAGMENT = 2;

	@Unique
	private static final long[] NO_EXTRA = new long[0];

	@Shadow
	@Final
	private GpuTextureView colorTexture;

	@Shadow
	@Final
	private GpuTextureView depthTexture;

	@Shadow
	private Vector4fc clearColor;

	@Shadow
	private Double clearDepth;

	/** The pipeline the pass last set up for drawing, caught where its native state is bound. */
	@Unique
	private Object vitrail$pipeline;

	@Unique
	private MultiTarget.@Nullable Encoder vitrail$encoder;

	@Unique
	private @Nullable GpuTextureView @Nullable [] vitrail$extra;

	/** Clears the extra targets are still owed, null once the encoder has been opened with them. */
	@Unique
	private @Nullable Vector4fc @Nullable [] vitrail$extraClears;

	/** The Metal pixel format of each target after nought, nought where a slot is empty. */
	@Unique
	private long[] vitrail$formats = NO_EXTRA;

	@Override
	public void vitrail$extraTargets(MultiTarget.Encoder encoder, @Nullable GpuTextureView[] targets,
			@Nullable Vector4fc[] clears) {
		boolean anyClear = false;
		long[] formats = new long[targets.length];
		for (int i = 0; i < targets.length; i++) {
			anyClear |= clears[i] != null;
			formats[i] = targets[i] == null ? 0L
					: MTLPixelFormat.from(targets[i].texture().getFormat()).value;
		}

		this.vitrail$encoder = encoder;
		this.vitrail$extra = targets;
		this.vitrail$extraClears = anyClear ? clears : null;
		this.vitrail$formats = formats;
	}

	@Inject(method = "renderEncoder", at = @At("HEAD"), cancellable = true, require = 1)
	private void vitrail$multiTargetEncoder(CallbackInfoReturnable<MTLRenderCommandEncoder> callback) {
		GpuTextureView[] extra = this.vitrail$extra;
		if (extra == null) {
			return;
		}

		MTLRenderCommandEncoder encoder = this.vitrail$encoder.vitrail$encoder(this.colorTexture,
				this.depthTexture, extra, this.clearColor, this.clearDepth, this.vitrail$extraClears);
		this.clearColor = null;
		this.clearDepth = null;
		this.vitrail$extraClears = null;
		callback.setReturnValue(encoder);
	}

	@Inject(method = "materializePendingClear", at = @At("HEAD"), require = 1)
	private void vitrail$extraClears(CallbackInfo callback) {
		if (this.vitrail$extraClears != null) {
			((MetalRenderPassInvoker) this).vitrail$renderEncoder();
		}
	}

	@WrapOperation(method = "bindDrawState", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/metallum/render/MetalCompiledRenderPipeline;getNativePipeline(Z)"
							+ "Ljava/lang/foreign/MemorySegment;"))
	private MemorySegment vitrail$pipeline(@Coerce Object pipeline, boolean depth,
			Operation<MemorySegment> original) {
		this.vitrail$pipeline = pipeline;
		if (this.vitrail$formats.length > 0 || hasExtraTargets(pipeline)) {
			return ((MultiTarget.Pipeline) pipeline).vitrail$nativePipeline(depth, this.vitrail$formats);
		}

		return original.call(pipeline, depth);
	}

	@Unique
	private static boolean hasExtraTargets(Object pipeline) {
		return pipeline instanceof MultiTarget.Pipeline;
	}

	/** The name of the resource being pushed, caught for the storage images bound by name. */
	@Unique
	private @Nullable String vitrail$pushing;

	@Inject(method = "pushDescriptor", at = @At("HEAD"), require = 1)
	private void vitrail$pushing(MTLRenderCommandEncoder encoder, @Coerce Object binding,
			CallbackInfo callback) {
		this.vitrail$pushing = VitrailMetalBridge.resourceName(binding);
	}

	@Redirect(method = "pushDescriptor", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/metallum/render/MetalRenderPass;bindTextureAndSampler("
							+ "Lcom/metallum/mtl/MTLRenderCommandEncoder;Ljava/lang/foreign/MemorySegment;"
							+ "Ljava/lang/foreign/MemorySegment;JI)V"))
	private void vitrail$bind(MTLRenderCommandEncoder encoder, MemorySegment texture,
			MemorySegment sampler, long index, int stageMask) {
		SamplerSlots.Holder holder = this.vitrail$pipeline instanceof SamplerSlots.Holder held ? held : null;
		byte[] slots = holder == null ? null : holder.vitrail$samplerSlots();
		int binding = (int) index;
		if (holder != null && ShadowCompare.noted() && binding < Long.SIZE
				&& (holder.vitrail$comparedMask() & (1L << binding)) != 0L) {
			sampler = MetalHost.compareSampler();
		}

		// A pack's own image, or a sampler hanging off one, is bound by name over whatever the pass
		// was handed for it, as VulkanRenderPassMixin does on Vulkan.
		String name = this.vitrail$pushing;
		if (name != null) {
			StorageImages.Bound image = StorageImages.bound(name);
			if (image != null) {
				texture = image.texture();
			}
		}

		if ((stageMask & STAGE_VERTEX) != 0) {
			encoder.setVertexTexture(texture, index);
			int slot = SamplerSlots.slot(slots, binding, SamplerSlots.VERTEX);
			if (slot >= 0 && slot < 16) {
				encoder.setVertexSamplerState(sampler, slot);
			}
		}
		if ((stageMask & STAGE_FRAGMENT) != 0) {
			encoder.setFragmentTexture(texture, index);
			int slot = SamplerSlots.slot(slots, binding, SamplerSlots.FRAGMENT);
			if (slot >= 0 && slot < 16) {
				encoder.setFragmentSamplerState(sampler, slot);
			}
		}
	}
}
