package dev.leadlight.mixin.metal;

import com.metallum.render.LeadlightMetalBridge;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.leadlight.metal.MetalHost;
import dev.leadlight.metal.SamplerSlots;
import dev.leadlight.render.ShadowCompare;
import dev.leadlight.render.storage.StorageBuffers;
import dev.leadlight.render.storage.StorageImages;
import io.github.kokodio.metaljvm.metal.MTLBuffer;
import io.github.kokodio.metaljvm.metal.MTLRenderCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLSamplerState;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.foreign.MemorySegment;

/**
 * Binds each sampler at the slot its stage was compiled to read it from ({@link SamplerSlots}), a
 * comparison sampler where a pack compares against a shadow map, and a pack's own images and
 * buffers by name. Metallum 0.0.25 draws into several colour targets itself, so unlike the 26.2
 * build nothing here opens encoders.
 */
@Mixin(targets = "com.metallum.render.MetalRenderPass", remap = false)
public abstract class MetalRenderPassMixin {

	/** Metallum's own stage bits on a resource binding. */
	@Unique
	private static final int STAGE_VERTEX = 1;

	@Unique
	private static final int STAGE_FRAGMENT = 2;

	@Unique
	private static final int SAMPLER_SLOTS = 16;

	@Unique
	private @Nullable Object leadlight$pipeline;

	/** The name of the resource being pushed, caught for the storage images bound by name. */
	@Unique
	private @Nullable String leadlight$pushing;

	@Unique
	private int leadlight$binding;

	@Inject(method = "setPipeline", at = @At("HEAD"), require = 1)
	private void leadlight$pipeline(BackendRenderPipeline pipeline, CallbackInfo callback) {
		this.leadlight$pipeline = pipeline;
	}

	@Inject(method = "pushDescriptor", at = @At("HEAD"), require = 1)
	private void leadlight$pushing(MTLRenderCommandEncoder encoder, @Coerce Object binding,
			CallbackInfo callback) {
		this.leadlight$pushing = LeadlightMetalBridge.resourceName(binding);
		this.leadlight$binding = LeadlightMetalBridge.bindingIndex(binding);
	}

	@Redirect(method = "pushDescriptor", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/metallum/render/MetalRenderPass;bindTextureAndSampler("
							+ "Lio/github/kokodio/metaljvm/metal/MTLRenderCommandEncoder;"
							+ "Lio/github/kokodio/metaljvm/metal/MTLTexture;"
							+ "Lio/github/kokodio/metaljvm/metal/MTLSamplerState;JI)V"))
	private void leadlight$bind(MTLRenderCommandEncoder encoder, MTLTexture texture,
			MTLSamplerState sampler, long index, int stageMask) {
		SamplerSlots.Holder holder = this.leadlight$pipeline instanceof SamplerSlots.Holder held ? held : null;
		byte[] slots = holder == null ? null : holder.leadlight$samplerSlots();
		int binding = this.leadlight$binding;
		if (holder != null && ShadowCompare.noted() && binding >= 0 && binding < Long.SIZE
				&& (holder.leadlight$comparedMask() & (1L << binding)) != 0L) {
			MemorySegment compare = MetalHost.compareSampler();
			if (compare.address() != 0L) {
				sampler = new MTLSamplerState(compare.address());
			}
		}

		// A pack's own image, or a sampler hanging off one, is bound by name over whatever the pass
		// was handed for it, as VulkanRenderPassMixin does on Vulkan.
		String name = this.leadlight$pushing;
		if (name != null) {
			StorageImages.Bound image = StorageImages.bound(name);
			if (image != null) {
				texture = new MTLTexture(image.view());
			}
		}

		if ((stageMask & STAGE_VERTEX) != 0) {
			encoder.setVertexTexture(texture, index);
			int slot = leadlight$slot(slots, binding, SamplerSlots.VERTEX, index);
			if (slot >= 0 && slot < SAMPLER_SLOTS) {
				encoder.setVertexSamplerState(sampler, slot);
			}
		}
		if ((stageMask & STAGE_FRAGMENT) != 0) {
			encoder.setFragmentTexture(texture, index);
			int slot = leadlight$slot(slots, binding, SamplerSlots.FRAGMENT, index);
			if (slot >= 0 && slot < SAMPLER_SLOTS) {
				encoder.setFragmentSamplerState(sampler, slot);
			}
		}
	}

	/**
	 * The sampler slot a stage reads a binding from: Metallum's own index for a pipeline compiled
	 * without a table, and none for a binding the table does not list, which no stage samples.
	 */
	@Unique
	private static int leadlight$slot(byte @Nullable [] slots, int binding, int stage, long index) {
		if (slots == null) {
			return (int) index;
		}

		return binding * 2 + stage < slots.length ? slots[binding * 2 + stage] : -1;
	}

	/**
	 * A pack's storage buffer, bound by name over the placeholder the pass was handed for it, the
	 * way {@code VulkanRenderPassMixin} puts the real buffer into the descriptor on Vulkan.
	 */
	@Redirect(method = "pushDescriptor", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/metallum/render/MetalRenderPass;bindBuffer("
							+ "Lio/github/kokodio/metaljvm/metal/MTLRenderCommandEncoder;"
							+ "Lio/github/kokodio/metaljvm/metal/MTLBuffer;JJI)V"))
	private void leadlight$bindBuffer(MTLRenderCommandEncoder encoder, MTLBuffer buffer, long offset,
			long index, int stageMask) {
		String name = this.leadlight$pushing;
		StorageBuffers.Bound storage = name == null ? null : StorageBuffers.bound(name);
		MTLBuffer bound = storage != null ? new MTLBuffer(storage.buffer()) : buffer;
		long at = storage != null ? 0L : offset;
		if ((stageMask & STAGE_VERTEX) != 0) {
			encoder.setVertexBuffer(bound, at, index);
		}
		if ((stageMask & STAGE_FRAGMENT) != 0) {
			encoder.setFragmentBuffer(bound, at, index);
		}
	}
}
