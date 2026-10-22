package dev.leadlight.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Development aid: lets the debug script turn the camera on every rendered frame. Takes none of
 * {@code render}'s arguments, which 26.3 dropped.
 */
@Mixin(GameRenderer.class)
public abstract class DebugFrameMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void debugScript$frame(CallbackInfo callback) {
		dev.leadlight.dev.DebugScript.frame();
	}
}
