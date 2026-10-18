package dev.leadlight.mixin.metal;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.leadlight.metal.SamplerSlots;
import org.lwjgl.util.spvc.Spvc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The SPIRV-Cross options Metallum leaves at their defaults and a shader pack cannot do without.
 * <p>
 * <strong>Fragment outputs are padded to four components.</strong> Vulkan takes a {@code vec3}
 * written into an RGBA target and leaves the fourth channel alone; Metal refuses to build a pipeline
 * whose fragment output has fewer components than the attachment's pixel format, and packs declare
 * {@code vec3} and even {@code float} outputs into four channel targets all the time.
 * <p>
 * <strong>Samplers are numbered per stage from nought</strong>, by {@link SamplerSlots}, since
 * Metal has sixteen sampler slots and Metallum would hand out one per binding.
 */
@Mixin(targets = "com.metallum.render.MetalCrossShaderCompiler", remap = false)
public abstract class MetalCrossShaderCompilerMixin {

	@Inject(method = "compile", at = @At("HEAD"), require = 1)
	private static void leadlight$begin(CallbackInfoReturnable<?> callback) {
		SamplerSlots.begin();
	}

	@WrapOperation(method = "spirvToMsl", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/util/spvc/Spvc;spvc_compiler_install_compiler_options(JJ)I"))
	private static int leadlight$options(long compiler, long options, Operation<Integer> original) {
		Spvc.spvc_compiler_options_set_bool(options,
				Spvc.SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
		int result = original.call(compiler, options);
		SamplerSlots.remap(compiler);
		return result;
	}

	/**
	 * A one or three dimensional image is let through Metallum's check as two dimensional, as
	 * Vitrail lets it through the game's: the texture bound is the one the engine allocated in the
	 * declared shape, and the MSL declares that shape.
	 */
	@WrapOperation(method = "addToBindGroup", require = 1,
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/glsl/SpvSampler;dimensions()I"))
	private static int leadlight$allowLineAndVolume(@Coerce Object sampler, Operation<Integer> original) {
		int dimension = original.call(sampler);
		return dimension == 0 || dimension == 2 ? 1 : dimension;
	}
}
