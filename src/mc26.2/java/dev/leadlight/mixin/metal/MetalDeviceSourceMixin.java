package dev.leadlight.mixin.metal;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import dev.leadlight.metal.GlslComments;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import dev.leadlight.metal.MultiTarget;

/**
 * Prepares a stage's text the way Metallum does, comments out and defines in, with the comments
 * taken out by {@link GlslComments} rather than by Metallum's two regular expressions, which cut
 * pack code in half wherever a line comment holds an opening block comment.
 */
@Mixin(targets = "com.metallum.render.MetalDevice", remap = false)
public abstract class MetalDeviceSourceMixin {

	@Inject(method = "prepareShaderSource", at = @At("HEAD"), cancellable = true, require = 1)
	private static void leadlight$prepare(String source, ShaderDefines defines,
			CallbackInfoReturnable<String> callback) {
		callback.setReturnValue(GlslPreprocessor.injectDefines(
				GlslComments.strip(source).stripLeading(), defines));
	}

	/** Metal draws into eight colour targets at once; Metallum says one, which it then enforces. */
	@Inject(method = "buildDeviceInfo", at = @At("RETURN"), cancellable = true, require = 1)
	private void leadlight$colourTargets(CallbackInfoReturnable<DeviceInfo> callback) {
		DeviceInfo info = callback.getReturnValue();
		DeviceLimits limits = info.limits();
		callback.setReturnValue(new DeviceInfo(info.name(), info.vendorName(), info.driverInfo(),
				info.isZZeroToOne(), info.backendName(), info.timestampPeriod(),
				new DeviceLimits(limits.maxAnisotropy(), limits.minUniformOffsetAlignment(),
						limits.maxTextureSize(), limits.maxMemoryAllocationSize(),
						limits.maxMultiDrawDirectInterleavedDrawCount(), MultiTarget.MAX_TARGETS),
				info.features(), info.underlyingExtensions(), info.hintsAndWorkarounds(), info.type()));
	}
}
