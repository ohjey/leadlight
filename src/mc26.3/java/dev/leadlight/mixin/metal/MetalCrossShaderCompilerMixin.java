package dev.leadlight.mixin.metal;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.leadlight.metal.SamplerSlots;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spvc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


/**
 * What Metallum 0.0.25's SPIR-V to MSL step is given beyond its own options, for the same two reasons
 * as on 26.2: fragment outputs padded to the components of their target, which Metal requires and
 * packs do not write, and samplers numbered per stage so a stage reading more than sixteen textures
 * still has a sampler slot for each (see {@link SamplerSlots}).
 * <p>
 * The slots are placed after Metallum's own binding table, which puts texture, buffer and sampler of
 * a binding at one Metal index, since SPIRV-Cross keeps the last placement of a binding. And they
 * reach the pipeline through the compiled result rather than through the thread: Metallum builds
 * the native pipeline later, in the {@code Pending} it hands back.
 */
@Mixin(targets = "com.metallum.render.MetalCrossShaderCompiler", remap = false)
public abstract class MetalCrossShaderCompilerMixin {

	@Inject(method = "compile", at = @At("HEAD"), require = 1)
	private static void leadlight$begin(CallbackInfoReturnable<?> callback) {
		SamplerSlots.begin();
	}

	@Inject(method = "compile", at = @At("RETURN"), require = 1)
	private static void leadlight$keep(CallbackInfoReturnable<Object> callback) {
		byte[] slots = SamplerSlots.take(true);
		if (callback.getReturnValue() != null) {
			SamplerSlots.attach(callback.getReturnValue(), slots);
		}
	}

	@WrapOperation(method = "spirvToMsl", require = 1,
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/util/spvc/Spvc;spvc_compiler_install_compiler_options(JJ)I"))
	private static int leadlight$options(long compiler, long options, Operation<Integer> original) {
		Spvc.spvc_compiler_options_set_bool(options,
				Spvc.SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
		return original.call(compiler, options);
	}

	@WrapOperation(method = "spirvToMsl", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/metallum/render/MetalCrossShaderCompiler;remapResourceBindings("
							+ "Lorg/lwjgl/system/MemoryStack;J[I)V"))
	private static void leadlight$slots(MemoryStack stack, long compiler, int[] metalIndices,
			Operation<Void> original) {
		original.call(stack, compiler, metalIndices);
		SamplerSlots.remap(compiler, metalIndices);
	}
}
