package dev.vitrailmetal.metal;

import com.metallum.objc.AutoreleasePool;
import com.metallum.render.VitrailMetalBridge;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.vitrailmetal.mixin.access.CommandEncoderAccessor;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Collection;

/**
 * One Metal compute encoder in the frame Metallum is recording, opened and closed the way Metallum
 * opens and closes its own: the encoder open before it is ended, the frame's fence waited on as it
 * opens and updated as it closes, which is how every encoder of the frame is ordered after the one
 * before (Metallum's textures are untracked, so Metal orders nothing by itself).
 */
public final class MetalCompute implements AutoCloseable {

	private final MetalEncoderAccess access;
	private final AutoreleasePool pool;
	private final MemorySegment encoder;

	private MetalCompute(MetalEncoderAccess access, AutoreleasePool pool, MemorySegment encoder) {
		this.access = access;
		this.pool = pool;
		this.encoder = encoder;
	}

	/**
	 * Opens one, after doing the deferred clears Metallum still owes the textures the dispatch is
	 * about to read or write, or answers null where the game is not on Metallum.
	 */
	public static @Nullable MetalCompute open(CommandEncoder apiEncoder, Collection<GpuTexture> touched) {
		Object backend = ((CommandEncoderAccessor) apiEncoder).vitrail$backend();
		if (!(backend instanceof MetalEncoderAccess access)) {
			return null;
		}

		for (GpuTexture texture : touched) {
			VitrailMetalBridge.flushPendingClear(backend, texture);
		}

		access.vitrail$endEncoder();
		AutoreleasePool pool = AutoreleasePool.push();
		MemorySegment encoder = Objc.ptr(access.vitrail$commandBuffer().handle(), "computeCommandEncoder");
		Objc.send(encoder, "waitForFence:", access.vitrail$fence().handle());
		return new MetalCompute(access, pool, encoder);
	}

	public MemorySegment encoder() {
		return this.encoder;
	}

	public void pipeline(MemorySegment state) {
		Objc.send(this.encoder, "setComputePipelineState:", state);
	}

	public void buffer(MemorySegment buffer, long offset, int index) {
		Objc.send(this.encoder, "setBuffer:offset:atIndex:", buffer, offset, (long) index);
	}

	public void texture(MemorySegment texture, int index) {
		Objc.send(this.encoder, "setTexture:atIndex:", texture, (long) index);
	}

	public void sampler(MemorySegment sampler, int index) {
		Objc.send(this.encoder, "setSamplerState:atIndex:", sampler, (long) index);
	}

	public void dispatch(int x, int y, int z, int localX, int localY, int localZ) {
		Objc.dispatch(this.encoder, Math.max(0, x), Math.max(0, y), Math.max(0, z), localX, localY, localZ);
	}

	@Override
	public void close() {
		try {
			Objc.send(this.encoder, "updateFence:", this.access.vitrail$fence().handle());
			Objc.send(this.encoder, "endEncoding");
		} finally {
			this.pool.close();
		}
	}
}
