package dev.leadlight.mixin.metal;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.metallum.mtl.MTLPixelFormat;
import com.metallum.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.leadlight.metal.MultiTarget;
import dev.leadlight.metal.SamplerSlots;
import dev.leadlight.metal.Objc;
import dev.leadlight.render.storage.StorageBuffers;
import com.metallum.mtl.MTLBuffer;
import dev.leadlight.render.storage.StorageImages;
import com.metallum.render.LeadlightMetalBridge;
import dev.leadlight.metal.MetalHost;
import dev.leadlight.render.ShadowCompare;
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
	private Object leadlight$pipeline;

	@Unique
	private MultiTarget.@Nullable Encoder leadlight$encoder;

	@Unique
	private @Nullable GpuTextureView @Nullable [] leadlight$extra;

	/** Clears the extra targets are still owed, null once the encoder has been opened with them. */
	@Unique
	private @Nullable Vector4fc @Nullable [] leadlight$extraClears;

	/** The Metal pixel format of each target after nought, nought where a slot is empty. */
	@Unique
	private long[] leadlight$formats = NO_EXTRA;

	@Override
	public void leadlight$extraTargets(MultiTarget.Encoder encoder, @Nullable GpuTextureView[] targets,
			@Nullable Vector4fc[] clears) {
		boolean anyClear = false;
		long[] formats = new long[targets.length];
		for (int i = 0; i < targets.length; i++) {
			anyClear |= clears[i] != null;
			formats[i] = targets[i] == null ? 0L
					: MTLPixelFormat.from(targets[i].texture().getFormat()).value;
		}

		this.leadlight$encoder = encoder;
		this.leadlight$extra = targets;
		this.leadlight$extraClears = anyClear ? clears : null;
		this.leadlight$formats = formats;
	}

	@Inject(method = "renderEncoder", at = @At("HEAD"), cancellable = true, require = 1)
	private void leadlight$multiTargetEncoder(CallbackInfoReturnable<MTLRenderCommandEncoder> callback) {
		GpuTextureView[] extra = this.leadlight$extra;
		if (extra == null) {
			return;
		}

		MTLRenderCommandEncoder encoder = this.leadlight$encoder.leadlight$encoder(this.colorTexture,
				this.depthTexture, extra, this.clearColor, this.clearDepth, this.leadlight$extraClears);
		this.clearColor = null;
		this.clearDepth = null;
		this.leadlight$extraClears = null;
		callback.setReturnValue(encoder);
	}

	@Inject(method = "materializePendingClear", at = @At("HEAD"), require = 1)
	private void leadlight$extraClears(CallbackInfo callback) {
		if (this.leadlight$extraClears != null) {
			((MetalRenderPassInvoker) this).leadlight$renderEncoder();
		}
	}

	@WrapOperation(method = "bindDrawState", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/metallum/render/MetalCompiledRenderPipeline;getNativePipeline(Z)"
							+ "Ljava/lang/foreign/MemorySegment;"))
	private MemorySegment leadlight$pipeline(@Coerce Object pipeline, boolean depth,
			Operation<MemorySegment> original) {
		this.leadlight$pipeline = pipeline;
		if (this.leadlight$formats.length > 0 || hasExtraTargets(pipeline)) {
			return ((MultiTarget.Pipeline) pipeline).leadlight$nativePipeline(depth, this.leadlight$formats);
		}

		return original.call(pipeline, depth);
	}

	@Unique
	private static boolean hasExtraTargets(Object pipeline) {
		return pipeline instanceof MultiTarget.Pipeline;
	}

	/** The name of the resource being pushed, caught for the storage images bound by name. */
	@Unique
	private @Nullable String leadlight$pushing;

	@Inject(method = "pushDescriptor", at = @At("HEAD"), require = 1)
	private void leadlight$pushing(MTLRenderCommandEncoder encoder, @Coerce Object binding,
			CallbackInfo callback) {
		this.leadlight$pushing = LeadlightMetalBridge.resourceName(binding);
	}

	@Redirect(method = "pushDescriptor", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/metallum/render/MetalRenderPass;bindTextureAndSampler("
							+ "Lcom/metallum/mtl/MTLRenderCommandEncoder;Ljava/lang/foreign/MemorySegment;"
							+ "Ljava/lang/foreign/MemorySegment;JI)V"))
	private void leadlight$bind(MTLRenderCommandEncoder encoder, MemorySegment texture,
			MemorySegment sampler, long index, int stageMask) {
		SamplerSlots.Holder holder = this.leadlight$pipeline instanceof SamplerSlots.Holder held ? held : null;
		byte[] slots = holder == null ? null : holder.leadlight$samplerSlots();
		int binding = (int) index;
		if (holder != null && ShadowCompare.noted() && binding < Long.SIZE
				&& (holder.leadlight$comparedMask() & (1L << binding)) != 0L) {
			sampler = MetalHost.compareSampler();
		}

		// A pack's own image, or a sampler hanging off one, is bound by name over whatever the pass
		// was handed for it, as VulkanRenderPassMixin does on Vulkan.
		String name = this.leadlight$pushing;
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

	/**
	 * A pack's storage buffer, bound by name over the placeholder the pass was handed for it, the
	 * way {@code VulkanRenderPassMixin} puts the real buffer into the descriptor on Vulkan.
	 */
	@Redirect(method = "pushDescriptor", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/metallum/render/MetalRenderPass;bindBuffer("
							+ "Lcom/metallum/mtl/MTLRenderCommandEncoder;Lcom/metallum/mtl/MTLBuffer;JJI)V"))
	private void leadlight$bindBuffer(MTLRenderCommandEncoder encoder, MTLBuffer buffer, long offset,
			long index, int stageMask) {
		String name = this.leadlight$pushing;
		StorageBuffers.Bound storage = name == null ? null : StorageBuffers.bound(name);
		MemorySegment handle = storage != null ? MemorySegment.ofAddress(storage.buffer()) : buffer.handle();
		long at = storage != null ? 0L : offset;
		if ((stageMask & STAGE_VERTEX) != 0) {
			Objc.send(encoder.handle(), "setVertexBuffer:offset:atIndex:", handle, at, index);
		}
		if ((stageMask & STAGE_FRAGMENT) != 0) {
			Objc.send(encoder.handle(), "setFragmentBuffer:offset:atIndex:", handle, at, index);
		}
	}
}
