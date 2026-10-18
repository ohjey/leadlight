package com.metallum.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.ObjC;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Part of Vitrail for Metallum, kept in Metallum's package because the command buffer's method that
 * opens a render encoder from a whole pass descriptor is package-private there.
 */
public final class VitrailMetalEncoders {

	private VitrailMetalEncoders() {
	}

	/**
	 * Opens a render encoder over colour target nought and every extra target that is not null,
	 * each loaded or cleared, and the depth target, the way Metallum opens one for a single target.
	 */
	public static MTLRenderCommandEncoder open(MTLCommandBuffer commands, MemorySegment[] colors,
			@Nullable Vector4fc[] clears, MemorySegment depth, @Nullable Double clearDepth,
			double width, double height) {
		try (AutoreleasePool pool = AutoreleasePool.push();
				MTLRenderPassDescriptor descriptor = new MTLRenderPassDescriptor()) {
			for (int i = 0; i < colors.length; i++) {
				if (colors[i] == null || ObjC.isNil(colors[i])) {
					continue;
				}
				Vector4fc clear = clears[i];
				descriptor.colorAttachment(i, colors[i],
						clear != null ? MTLRenderPassDescriptor.LOAD_ACTION_CLEAR
								: MTLRenderPassDescriptor.LOAD_ACTION_LOAD,
						MTLRenderPassDescriptor.STORE_ACTION_STORE, clear);
			}
			if (!ObjC.isNil(depth)) {
				descriptor.depthAttachment(depth, clearDepth != null
						? MTLRenderPassDescriptor.LOAD_ACTION_CLEAR
						: MTLRenderPassDescriptor.LOAD_ACTION_LOAD,
						MTLRenderPassDescriptor.STORE_ACTION_STORE, clearDepth);
				if (MTLPixelFormat.hasStencil(MTLTexture.pixelFormat(depth))) {
					descriptor.stencilAttachment(depth, MTLRenderPassDescriptor.LOAD_ACTION_LOAD,
							MTLRenderPassDescriptor.STORE_ACTION_STORE);
				}
			}
			MTLRenderCommandEncoder encoder = commands.makeRenderCommandEncoder(descriptor);
			encoder.setViewport(0.0, 0.0, width, height, 0.0, 1.0);
			return encoder;
		}
	}
}
