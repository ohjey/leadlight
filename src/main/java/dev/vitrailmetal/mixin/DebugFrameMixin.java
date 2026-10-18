package dev.vitrailmetal.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Development aid: lets the debug script turn the camera on every rendered frame. */
@Mixin(GameRenderer.class)
public abstract class DebugFrameMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void debugScript$frame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo callback) {
		dev.vitrailmetal.dev.DebugScript.frame();
	}
}
