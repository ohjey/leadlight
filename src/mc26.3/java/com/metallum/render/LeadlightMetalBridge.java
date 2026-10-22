package com.metallum.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.kokodio.metaljvm.foundation.NSObject;
import io.github.kokodio.metaljvm.metal.MTLFunction;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Set;

/**
 * Leadlight's way into Metallum 0.0.25, whose renderer classes are package-private: this class sits
 * in their package and hands back what the engine needs of them as native pointers, so the code
 * asking does not depend on which Metallum, or which Objective-C binding, wraps them. The 26.2
 * build has its own, over Metallum 0.0.24, with the same methods.
 */
public final class LeadlightMetalBridge {

	private LeadlightMetalBridge() {
	}

	private static MemorySegment pointer(@Nullable NSObject object) {
		return object == null || object.handle() == 0L ? MemorySegment.NULL
				: MemorySegment.ofAddress(object.handle());
	}

	/** The {@code id<MTLTexture>} a view draws and samples through. */
	public static MemorySegment nativeHandle(@Nullable GpuTextureView view) {
		return view == null ? MemorySegment.NULL : pointer(((MetalGpuTextureView) view).metalTexture());
	}

	public static MemorySegment nativeHandle(GpuTexture texture) {
		return pointer(((MetalGpuTexture) texture).metalTexture());
	}

	public static MemorySegment nativeHandle(GpuBuffer buffer) {
		return pointer(((MetalGpuBuffer) buffer).metalBuffer());
	}

	public static MemorySegment nativeHandle(GpuSampler sampler) {
		return pointer(((MetalGpuSampler) sampler).metalSampler());
	}

	/** Does the clear Metallum still owes {@code texture}, before something else writes or reads it. */
	public static void flushPendingClear(Object encoder, GpuTexture texture) {
		((MetalCommandEncoder) encoder).flushPendingClear((MetalGpuTexture) texture);
	}

	public static void markContentsDirty(GpuTexture texture) {
		((MetalGpuTexture) texture).markContentsDirty();
	}

	/** The {@code id<MTLDevice>} behind Metallum's device backend. */
	public static MemorySegment deviceHandle(Object deviceBackend) {
		return pointer(((MetalDevice) deviceBackend).metalDevice());
	}

	/**
	 * Compiles one MSL function on Metallum's device through Metallum's own cache and compile, so the
	 * options this engine puts on every library ({@code MetalUtilitiesMixin}) apply; NULL where it
	 * did not compile, Metallum having logged why.
	 */
	public static MemorySegment newFunction(Object deviceBackend, String msl, String entry) {
		MTLFunction function = ((MetalDevice) deviceBackend).getOrCompileFunction(msl, entry, entry);
		return pointer(function);
	}

	/** The {@code MTLPixelFormat} Metallum allocates a texture of this format as. */
	public static long pixelFormat(GpuFormat format) {
		return MetalConversions.pixelFormat(format).value;
	}

	/** Releases a native object once the GPU work submitted so far has finished with it. */
	public static void releaseLater(Object deviceBackend, MemorySegment handle) {
		if (handle.address() != 0L) {
			((MetalDevice) deviceBackend).queueResourceRelease(new NSObject(handle.address()));
		}
	}

	/** Runs {@code destruction} once the frames in flight no longer read what it frees. */
	public static boolean destroyLater(Object encoderBackend, Runnable destruction) {
		if (encoderBackend instanceof MetalCommandEncoder encoder) {
			encoder.queueForDestroy(destruction);
			return true;
		}

		return false;
	}

	/** The bits of the bindings named, by binding index, of one of Metallum's pipelines. */
	public static long bindingMask(Object compiledPipeline, Set<String> names) {
		long mask = 0L;
		for (MetalCompiledRenderPipeline.ResourceBinding binding
				: ((MetalCompiledRenderPipeline) compiledPipeline).resources()) {
			if (names.contains(binding.name()) && binding.bindingIndex() < Long.SIZE) {
				mask |= 1L << binding.bindingIndex();
			}
		}

		return mask;
	}

	/** The uniform name one of Metallum's resource bindings was reflected under. */
	public static String resourceName(Object binding) {
		return ((MetalCompiledRenderPipeline.ResourceBinding) binding).name();
	}

	/** The binding one of Metallum's resource bindings was declared at, which its slot table is keyed by. */
	public static int bindingIndex(Object binding) {
		return ((MetalCompiledRenderPipeline.ResourceBinding) binding).bindingIndex();
	}
}
