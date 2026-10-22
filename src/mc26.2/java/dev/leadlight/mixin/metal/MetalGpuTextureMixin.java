package dev.leadlight.mixin.metal;

import com.metallum.mtl.MTLTextureUsage;
import dev.leadlight.render.TextureUsage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a colour target be written by a compute shader, what {@code VulkanConstMixin} does on
 * Vulkan: the texture being created on this thread asked for it through {@link TextureUsage}, and
 * Metal wants {@code ShaderWrite} on the texture for that, plus {@code PixelFormatView} so an image
 * can be stored into through a view of another format of the same width.
 */
@Mixin(targets = "com.metallum.render.MetalGpuTexture", remap = false)
public abstract class MetalGpuTextureMixin {

	@Inject(method = "toMtlTextureUsage", at = @At("RETURN"), cancellable = true, require = 1)
	private static void leadlight$storage(int usage, CallbackInfoReturnable<Long> callback) {
		if (TextureUsage.storageRequested()) {
			callback.setReturnValue(callback.getReturnValueJ() | MTLTextureUsage.ShaderWrite.value
					| MTLTextureUsage.PixelFormatView.value | MTLTextureUsage.ShaderRead.value);
		}
	}
}
