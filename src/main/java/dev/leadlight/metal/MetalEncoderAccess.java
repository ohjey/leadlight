package dev.leadlight.metal;

import java.lang.foreign.MemorySegment;

/**
 * What a dispatch, a blit or a copy of this engine needs of Metallum's command encoder, as native
 * pointers so that the code asking does not depend on which Metallum wraps them. Implemented on
 * the encoder by each version's {@code MetalCommandEncoderMixin}.
 */
public interface MetalEncoderAccess {

	/** The fence every encoder of the frame waits on as it opens and updates as it closes. */
	MemorySegment leadlight$fence();

	/** The frame's command buffer. */
	MemorySegment leadlight$commandBuffer();

	/** Ends whichever encoder Metallum has open, so another can be opened on the buffer. */
	void leadlight$endEncoder();

	/** Metallum's blit encoder, opened if none is. */
	MemorySegment leadlight$blit();
}
