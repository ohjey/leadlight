package dev.leadlight.mixin.metal;

import com.metallum.render.MetalBackend;
import dev.leadlight.Leadlight;
import dev.leadlight.glsl.SharedMemory;
import dev.leadlight.glsl.VendorExtensions;
import dev.leadlight.pack.model.ProgramStage;
import dev.leadlight.render.BufferBlending;
import dev.leadlight.render.GeometryStage;
import dev.leadlight.render.WideSamplerSets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumSet;
import java.util.Set;

/**
 * What {@code VulkanBackendMixin} learns of a Vulkan device as it is created, answered for the Metal
 * device Metallum creates instead.
 * <p>
 * Every shader reaches Metal the way it reaches MoltenVK: shaderc to SPIR-V, then SPIRV-Cross to
 * MSL. So every workaround Vitrail keeps for MoltenVK applies here word for word, and the engine is
 * told it is on MoltenVK. Metal blends each colour attachment on its own, has no geometry stage at
 * all, runs simd-group operations in the fragment and compute stages but SPIRV-Cross refuses them in
 * a vertex one, and offers none of the vendor extensions a pack may gate an instruction on.
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
