package dev.leadlight.metal;

import com.metallum.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Several colour targets in one pass, which Metallum 0.0.24 does not do: it attaches colour target
 * nought and builds every pipeline for that target alone. A shader pack writes three, four, up to
 * eight at once. What the mixins add to Metallum's package-private classes is spoken through these
 * interfaces.
 */
public final class MultiTarget {

	/** Metal's limit on colour attachments in one render pass. */
	public static final int MAX_TARGETS = 8;

	private MultiTarget() {
	}

	/** Added to Metallum's render pass. */
	public interface Pass {

		/**
		 * The targets after nought, {@code targets[i]} being target {@code i + 1}, null where a slot
		 * is empty, and the clear each one is still owed.
		 */
		void leadlight$extraTargets(Encoder encoder, @Nullable GpuTextureView[] targets,
				@Nullable Vector4fc[] clears);
	}

	/** Added to Metallum's command encoder. */
	public interface Encoder {

		/** The Metal encoder drawing into all of these, opened now if the open one does not. */
		MTLRenderCommandEncoder leadlight$encoder(GpuTextureView color, @Nullable GpuTextureView depth,
				@Nullable GpuTextureView[] extra, @Nullable Vector4fc clearColor,
				@Nullable Double clearDepth, @Nullable Vector4fc @Nullable [] extraClears);
	}

	/** Added to Metallum's compiled pipeline. */
	public interface Pipeline {

		/**
		 * The native pipeline for a pass whose targets after nought have these Metal pixel formats,
		 * nought where a slot is empty.
		 */
		MemorySegment leadlight$nativePipeline(boolean depth, long[] formats);
	}
}
