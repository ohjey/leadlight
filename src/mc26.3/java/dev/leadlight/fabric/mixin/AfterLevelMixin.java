package dev.leadlight.fabric.mixin;

import dev.leadlight.platform.EngineStages;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of the level on Fabric: inside the level's render, once its frame graph has run and the
 * model view matrix it pushed is popped again, which is the line Iris ends its own level render on
 * ({@code mixin/MixinLevelRenderer.java}, {@code iris$endLevelRender} at the same
 * {@code popMatrix}).
 * <p>
 * Not after the level renderer returns, which is where this used to be. Mods draw their world
 * overlays at the {@code RETURN} of this same method, MaLiLib's world-last renderers among them
 * (Litematica's boxes, MiniHUD's light levels), straight into the main target and with the game's
 * own programs. Behind the return, the pack's final was drawn over them and they were never seen;
 * here, the final is already in the main target when they draw, so they land on the pack's image as
 * they land on Iris's. Nothing between this line and the return draws: the frame's features are
 * closed and the sections compiled, uploaded and culled for the next frame.
 * <p>
 * The 26.3 half: the level's render takes the frame's state rather than the tracker and the model
 * view matrix.
 */
@Mixin(LevelRenderer.class)
public abstract class AfterLevelMixin {

	@Inject(method = "render",
			at = @At(value = "INVOKE",
					target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;",
					shift = At.Shift.AFTER),
			require = 1)
	private void leadlight$afterLevel(CallbackInfo ci) {
		EngineStages.afterLevel();
	}
}
