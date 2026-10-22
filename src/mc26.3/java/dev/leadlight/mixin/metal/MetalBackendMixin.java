package dev.leadlight.mixin.metal;

import com.metallum.render.MetalBackend;
import dev.leadlight.Leadlight;
import dev.leadlight.pack.model.ProgramStage;
import dev.leadlight.render.BufferBlending;
import dev.leadlight.render.GeometryStage;
import dev.leadlight.glsl.SharedMemory;
import dev.leadlight.glsl.VendorExtensions;
import dev.leadlight.render.WideSamplerSets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumSet;
import java.util.Set;

/**
 * Tells the engine what the Metal device can do before the first pack is read, as the 26.2 build
 * does over Metallum 0.0.24: one blend function an attachment, no geometry stage, sixteen samplers
 * a stage, threadgroup memory shared, and no subgroup operations in the stages Metal leaves them out.
 */
@Mixin(value = MetalBackend.class, remap = false)
public abstract class MetalBackendMixin {

	@Inject(method = "createDevice", at = @At("HEAD"), require = 1)
	private void leadlight$deviceFacts(CallbackInfoReturnable<?> callback) {
		BufferBlending.serve(true);
		GeometryStage.serve(false);
		WideSamplerSets.serve(false, 16, 0);
		VendorExtensions.serveMoltenVk(true);
		SharedMemory.serve(true);
		VendorExtensions.serve(_ -> false, _ -> { });
		Set<ProgramStage> noSubgroups = VendorExtensions.serveSubgroupStages(
				EnumSet.of(ProgramStage.FRAGMENT, ProgramStage.COMPUTE));
		Leadlight.logger().info("Metal device: per-attachment blending, no geometry stage, subgroup "
				+ "operations absent from stages {}", noSubgroups);
	}
}
