package dev.leadlight.mixin.metal;

import dev.leadlight.render.TextureUsage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the textures a pack's compute writes ({@code colorimgN}) writable from a shader and viewable
 * in another format, as on 26.2: Metallum allocates them for reading and drawing only.
 */
@Mixin(targets = "com.metallum.render.MetalGpuTexture", remap = false)
public abstract class MetalGpuTextureMixin {

	/** {@code MTLTextureUsageShaderRead}, {@code ShaderWrite} and {@code PixelFormatView}. */
	@Unique
	private static final long LEADLIGHT$STORAGE = 0x01L | 0x02L | 0x10L;

	@Inject(method = "toMtlTextureUsage", at = @At("RETURN"), cancellable = true, require = 1)
	private static void leadlight$storage(int usage, CallbackInfoReturnable<Long> callback) {
		if (TextureUsage.storageRequested()) {
			callback.setReturnValue(callback.getReturnValueJ() | LEADLIGHT$STORAGE);
		}
	}
}
