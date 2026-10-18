package com.metallum.render;

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
}
