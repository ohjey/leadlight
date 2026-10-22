package dev.leadlight.mixin.metal;

import com.metallum.mtl.MTLCommandBuffer;
import dev.leadlight.Leadlight;
import dev.leadlight.metal.Objc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Says when the GPU refused a frame. Metallum counts a command buffer that ended in error as one
 * that completed and releases it without a word, and Metal then drops every frame after it, so the
 * window keeps the last picture it showed while the game runs on behind it. Read once each, when
 * Metallum releases the buffer after its frame, and said for the first few only.
 */
@Mixin(value = MTLCommandBuffer.class, remap = false)
public abstract class MTLCommandBufferMixin {

	@Unique
	private static final long LEADLIGHT$STATUS_ERROR = 5L;

	@Unique
	private static final AtomicInteger LEADLIGHT$REPORTED = new AtomicInteger();

	@Inject(method = "close", at = @At("HEAD"))
	private void leadlight$reportError(CallbackInfo ci) {
		MemorySegment handle = ((MTLCommandBuffer) (Object) this).handle();
		if (Objc.nil(handle) || LEADLIGHT$REPORTED.get() >= 5) {
			return;
		}

		long status = Objc.ptr(handle, "status").address();
		if (status != LEADLIGHT$STATUS_ERROR) {
			return;
		}

		MemorySegment error = Objc.ptr(handle, "error");
		String text = Objc.nil(error) ? "no error object" : Objc.string(Objc.ptr(error, "description"));
		Leadlight.logger().error("Metal: a frame's command buffer failed on the GPU ({} of the first 5): {}",
				LEADLIGHT$REPORTED.incrementAndGet(), text);
	}
}
