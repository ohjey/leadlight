package com.metallum.render;

import com.metallum.mtl.MTLDevice;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Part of Vitrail for Metallum, kept in Metallum's package so the engine can reach the handful of
 * package-private Metallum members it needs. Everything here mirrors one call Metallum makes itself.
 */
public final class VitrailMetalBridge {

	private VitrailMetalBridge() {
	}

	/** The Metal texture behind a view, or NULL for none. */
	public static MemorySegment nativeHandle(@Nullable GpuTextureView view) {
		return view == null ? MemorySegment.NULL : ((MetalGpuTextureView) view).nativeHandle();
	}

	/** The Metal texture behind a texture. */
	public static MemorySegment nativeHandle(GpuTexture texture) {
		return ((MetalGpuTexture) texture).nativeHandle();
	}

	/** Performs a deferred clear of this texture now, as Metallum does before using it otherwise. */
	public static void flushPendingClear(Object encoder, GpuTexture texture) {
		((MetalCommandEncoder) encoder).flushPendingClear((MetalGpuTexture) texture);
	}

	/** Metallum calls this on every texture a pass draws into, so a later clear is not skipped. */
	public static void markContentsDirty(GpuTexture texture) {
		((MetalGpuTexture) texture).markContentsDirty();
	}

	/** The Metal device behind Metallum's device backend. */
	public static MTLDevice device(Object deviceBackend) {
		return ((MetalDevice) deviceBackend).metalDevice();
	}

	/** The Metal buffer behind a buffer, as it stands now (a dynamic one changes backing). */
	public static MemorySegment nativeHandle(GpuBuffer buffer) {
		return ((MetalGpuBuffer) buffer).nativeHandle();
	}

	/** The Metal sampler state behind a sampler. */
	public static MemorySegment nativeHandle(GpuSampler sampler) {
		return ((MetalGpuSampler) sampler).nativeHandle();
	}

	/** Queues a release for when the frames that may still use a handle have finished. */
	public static void releaseLater(Object deviceBackend, MemorySegment handle) {
		((MetalDevice) deviceBackend).queueResourceRelease(handle);
	}

	/** A bit for each resource of a compiled pipeline whose name is one of these. */
	public static long bindingMask(Object compiledPipeline, java.util.Set<String> names) {
		long mask = 0L;
		for (MetalCompiledRenderPipeline.ResourceBinding binding
				: ((MetalCompiledRenderPipeline) compiledPipeline).resources()) {
			if (names.contains(binding.name()) && binding.bindingIndex() < Long.SIZE) {
				mask |= 1L << binding.bindingIndex();
			}
		}
		return mask;
	}

	/** Runs this once the frames still in flight have finished, on Metallum's own queue. */
	public static boolean destroyLater(Object encoderBackend, Runnable destruction) {
		if (encoderBackend instanceof MetalCommandEncoder encoder) {
			encoder.queueForDestroy(destruction);
			return true;
		}
		return false;
	}

	/** The name a pipeline resource binding of Metallum's carries. */
	public static String resourceName(Object binding) {
		return ((MetalCompiledRenderPipeline.ResourceBinding) binding).name();
	}
}
