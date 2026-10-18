package dev.vitrailmetal.mixin.dh;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tells Distant Horizons that Metallum's backend is not OpenGL.
 * <p>
 * DH reads the game's backend once, by name, and has two answers for it: {@code "Vulkan"} is
 * Vulkan and anything else is OpenGL ({@code MinecraftRenderWrapper.getMcRenderingApi}). On Metal
 * that makes it OpenGL, and the OpenGL answer is taken at its word: its lightmap mixin casts the
 * game's lightmap to a {@code GlTexture} for its id on every frame, which on Metallum throws in the
 * first frame and stops the game at the loading screen. DH draws through Blaze3D either way, and its
 * Vulkan answer is the one that asks nothing of a backend but the device the game already uses, so
 * that is the answer Metal is given: the road DH takes under Vitrail on Vulkan, and the one
 * {@link dev.vitrailmetal.dh.DhLods} reads its geometry from.
 * <p>
 * By name and {@link Pseudo}, as every reach into DH in this engine is: DH is not something to build
 * against, and an instance without it never loads the target.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper",
		remap = false)
public abstract class DhRenderingApiMixin {

	@Redirect(method = "getMcRenderingApi", require = 0,
			at = @At(value = "INVOKE",
					target = "Ljava/lang/String;equalsIgnoreCase(Ljava/lang/String;)Z"))
	private boolean vitrail$metalIsNotOpenGl(String backend, String vulkan) {
		return backend.equalsIgnoreCase(vulkan) || "Metal".equals(backend);
	}
}
