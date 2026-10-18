package dev.vitrailmetal.metal;

import com.metallum.mtl.MTLBlitCommandEncoder;
import com.metallum.mtl.MTLCommandBuffer;
import com.metallum.mtl.MTLFence;

/** Added to Metallum's command encoder: what an encoder of the engine's own needs to sit beside its. */
public interface MetalEncoderAccess {

	/** The fence every Metallum encoder waits on as it opens and updates as it closes. */
	MTLFence vitrail$fence();

	/** The command buffer of the frame being recorded. */
	MTLCommandBuffer vitrail$commandBuffer();

	/** Closes whatever Metal encoder Metallum has open. */
	void vitrail$endEncoder();

	/** A Metal blit encoder in Metallum's sequence, closed again by {@link #vitrail$endEncoder}. */
	MTLBlitCommandEncoder vitrail$blit();
}
