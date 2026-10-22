package dev.leadlight.mixin.metal;

import com.metallum.render.LeadlightMetalBridge;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.leadlight.metal.MetalEncoderAccess;
import dev.leadlight.render.MipmapCommands;
import io.github.kokodio.metaljvm.metal.MTLBlitCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLCommandBuffer;
import io.github.kokodio.metaljvm.metal.MTLFence;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.lang.foreign.MemorySegment;

/**
 * What the engine's dispatches, blits and mip chains need of Metallum 0.0.25's command encoder, as
 * native pointers ({@link MetalEncoderAccess}). Metallum 0.0.25 draws into several colour targets
 * itself, so the encoder half of the 26.2 build's multiple target support has no counterpart here.
 */
@Mixin(targets = "com.metallum.render.MetalCommandEncoder", remap = false)
public abstract class MetalCommandEncoderMixin implements MetalEncoderAccess, MipmapCommands {

	@Shadow
	@Final
	private MTLFence fence;

	@Shadow
	abstract void endEncoder();

	@Shadow
	abstract MTLCommandBuffer commandBuffer();

	@Shadow
	abstract MTLBlitCommandEncoder blitCommandEncoder();

	@Override
	public MemorySegment leadlight$fence() {
		return MemorySegment.ofAddress(this.fence.handle());
	}

	@Override
	public MemorySegment leadlight$commandBuffer() {
		return MemorySegment.ofAddress(this.commandBuffer().handle());
	}

	@Override
	public void leadlight$endEncoder() {
		this.endEncoder();
	}

	@Override
	public MemorySegment leadlight$blit() {
		return MemorySegment.ofAddress(this.blitCommandEncoder().handle());
	}

	/**
	 * Fills a mip chain the way {@code glGenerateMipmap} does, which on Metal is one blit command.
	 * A depth format is refused, as Metal refuses it, and the caller then keeps its readers on the
	 * base level.
	 */
	@Override
	public boolean leadlight$generateMipmaps(GpuTexture texture) {
		if (texture.getMipLevels() <= 1 || texture.isClosed() || texture.getFormat().hasDepthAspect()) {
			return false;
		}

		LeadlightMetalBridge.flushPendingClear(this, texture);
		this.blitCommandEncoder().generateMipmapsForTexture(
				new MTLTexture(LeadlightMetalBridge.nativeHandle(texture).address()));
		this.endEncoder();
		LeadlightMetalBridge.markContentsDirty(texture);
		return true;
	}

	/** The pass's Metal encoder is opened lazily at its first draw, so there is none to move yet. */
	@Override
	public boolean leadlight$viewport(int width, int height) {
		return false;
	}
}
