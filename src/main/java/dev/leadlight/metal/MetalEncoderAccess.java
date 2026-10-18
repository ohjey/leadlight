package dev.leadlight.metal;

import com.metallum.mtl.MTLBlitCommandEncoder;
import com.metallum.mtl.MTLCommandBuffer;
import com.metallum.mtl.MTLFence;

/** Added to Metallum's command encoder: what an encoder of the engine's own needs to sit beside its. */
public interface MetalEncoderAccess {

	/** The fence every Metallum encoder waits on as it opens and updates as it closes. */
	MTLFence leadlight$fence();

	/** The command buffer of the frame being recorded. */
	MTLCommandBuffer leadlight$commandBuffer();

	/** Closes whatever Metal encoder Metallum has open. */
	void leadlight$endEncoder();

	/** A Metal blit encoder in Metallum's sequence, closed again by {@link #leadlight$endEncoder}. */
	MTLBlitCommandEncoder leadlight$blit();
}
