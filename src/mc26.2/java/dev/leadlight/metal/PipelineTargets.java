package dev.leadlight.metal;

import com.metallum.mtl.MTLBlendFactor;
import com.metallum.mtl.MTLBlendOperation;
import com.metallum.mtl.MTLColorWriteMask;
import com.metallum.mtl.MTLDevice;
import com.metallum.mtl.MTLPixelFormat;
import com.metallum.mtl.MTLRenderPipelineDescriptor;
import com.metallum.objc.ObjC;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.leadlight.Leadlight;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The pipeline half of {@link MultiTarget}. Metal wants a pipeline's colour formats to match the
 * pass it draws in exactly, target by target, so each pipeline keeps the descriptor it was built
 * from and builds one more native pipeline for every set of targets after nought it meets: the
 * targets it declares carry their own blend and write mask, a target the pass has and the pipeline
 * does not write is declared at the pass's format with nothing written, and a target the pipeline
 * writes and the pass lacks is left out.
 */
public final class PipelineTargets implements AutoCloseable {

	/** What Metallum's constructor is building on this thread, both depth halves of it. */
	private static final ThreadLocal<PipelineTargets> BUILDING = new ThreadLocal<>();

	private final RenderPipeline pipeline;
	private final MTLDevice device;
	private final @Nullable ColorTargetState[] states;
	/** The format each target after nought is declared at, nought where the pipeline writes none. */
	private final long[] declared;
	private @Nullable MTLRenderPipelineDescriptor withDepth;
	private @Nullable MTLRenderPipelineDescriptor withoutDepth;
	private final Map<String, MemorySegment> variants = new HashMap<>();

	private PipelineTargets(RenderPipeline pipeline, MTLDevice device) {
		this.pipeline = pipeline;
		this.device = device;
		ColorTargetState[] all = pipeline.getColorTargetStates();
		int count = all == null ? 0 : Math.min(all.length, MultiTarget.MAX_TARGETS);
		this.states = new ColorTargetState[Math.max(0, count - 1)];
		this.declared = new long[this.states.length];
		for (int i = 1; i < count; i++) {
			this.states[i - 1] = all[i];
			this.declared[i - 1] = all[i] == null ? 0L : MTLPixelFormat.from(all[i].format()).value;
		}
	}

	/**
	 * Called just before Metallum builds a native pipeline: configures the targets after nought the
	 * pipeline declares, and keeps the descriptor for the variants.
	 */
	public static void beforeBuild(RenderPipeline pipeline, MTLDevice device,
			MTLRenderPipelineDescriptor descriptor, boolean depth) {
		PipelineTargets building = BUILDING.get();
		if (building == null || building.pipeline != pipeline) {
			if (building != null) {
				building.close();
			}
			building = new PipelineTargets(pipeline, device);
			BUILDING.set(building);
		}

		building.configure(descriptor, building.declared);
		if (depth) {
			building.withDepth = descriptor;
		} else {
			building.withoutDepth = descriptor;
		}
	}

	/** Whether a descriptor is kept by {@link #beforeBuild}, so must not be closed by Metallum. */
	public static boolean kept(MTLRenderPipelineDescriptor descriptor) {
		PipelineTargets building = BUILDING.get();
		return building != null && (building.withDepth == descriptor || building.withoutDepth == descriptor);
	}

	/** The descriptors kept while {@code pipeline} was built on this thread. */
	public static @Nullable PipelineTargets take(RenderPipeline pipeline) {
		PipelineTargets building = BUILDING.get();
		BUILDING.remove();
		if (building != null && building.pipeline != pipeline) {
			building.close();
			return null;
		}
		return building;
	}

	/** Whether a pass with these formats after target nought can use the pipeline as built. */
	public boolean matches(long[] formats) {
		int length = Math.max(formats.length, this.declared.length);
		for (int i = 0; i < length; i++) {
			long pass = i < formats.length ? formats[i] : 0L;
			long own = i < this.declared.length ? this.declared[i] : 0L;
			if (pass != own) {
				return false;
			}
		}
		return true;
	}

	/** The native pipeline for a pass whose targets after nought have these formats. */
	public MemorySegment variant(boolean depth, long[] formats) {
		String key = (depth ? "d" : "n") + Arrays.toString(formats);
		MemorySegment variant = this.variants.get(key);
		if (variant != null) {
			return variant;
		}

		MTLRenderPipelineDescriptor descriptor = depth ? this.withDepth : this.withoutDepth;
		if (descriptor == null) {
			return MemorySegment.NULL;
		}

		configure(descriptor, formats);
		variant = this.device.newRenderPipelineState(descriptor);
		configure(descriptor, this.declared);
		this.variants.put(key, variant);
		if (ObjC.isNil(variant)) {
			Leadlight.logger().error("Metal: {} could not be built for a pass with targets {} after nought",
					this.pipeline.getLocation(), Arrays.toString(formats));
		}
		return variant;
	}

	private void configure(MTLRenderPipelineDescriptor descriptor, long[] formats) {
		for (int i = 0; i < MultiTarget.MAX_TARGETS - 1; i++) {
			long format = i < formats.length ? formats[i] : 0L;
			int slot = i + 1;
			if (format == 0L) {
				descriptor.setColorAttachmentFormat(slot, MTLPixelFormat.Invalid);
				descriptor.disableBlending(slot, MTLColorWriteMask.All.value);
				continue;
			}

			descriptor.setColorAttachmentFormat(slot, format);
			ColorTargetState state = i < this.states.length ? this.states[i] : null;
			if (state == null) {
				descriptor.disableBlending(slot, MTLColorWriteMask.None.value);
				continue;
			}

			long writeMask = MTLColorWriteMask.from(state.writeMask());
			Optional<BlendFunction> blend = state.blendFunction();
			if (blend.isPresent()) {
				BlendFunction function = blend.get();
				descriptor.setBlendState(slot,
						MTLBlendFactor.from(function.color().sourceFactor()),
						MTLBlendFactor.from(function.color().destFactor()),
						MTLBlendOperation.from(function.color().op()),
						MTLBlendFactor.from(function.alpha().sourceFactor()),
						MTLBlendFactor.from(function.alpha().destFactor()),
						MTLBlendOperation.from(function.alpha().op()),
						writeMask);
			} else {
				descriptor.disableBlending(slot, writeMask);
			}
		}
	}

	@Override
	public void close() {
		for (MemorySegment variant : this.variants.values()) {
			if (!ObjC.isNil(variant)) {
				ObjC.release(variant);
			}
		}
		this.variants.clear();
		if (this.withDepth != null) {
			this.withDepth.close();
			this.withDepth = null;
		}
		if (this.withoutDepth != null) {
			this.withoutDepth.close();
			this.withoutDepth = null;
		}
	}
}
