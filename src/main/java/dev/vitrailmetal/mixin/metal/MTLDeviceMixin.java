package dev.vitrailmetal.mixin.metal;

import com.metallum.mtl.MTLDevice;
import dev.vitrailmetal.metal.Objc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.lang.foreign.MemorySegment;

/**
 * Compiles every MSL library with {@code preserveInvariance}, which Metallum leaves off by handing
 * Metal no compile options at all.
 * <p>
 * Every vertex stage this engine writes declares {@code invariant gl_Position}, and SPIRV-Cross
 * carries that into the MSL as {@code [[invariant]]}, but Metal only honours it when the library is
 * compiled asking it to; otherwise fast math is free to fold each program's arithmetic its own way.
 * That matters wherever a pack draws the same vertex twice through two programs and relies on the
 * second landing at exactly the first one's depth: a mob's glowing eyes over its body (the game
 * tests them {@code GREATER_THAN_OR_EQUAL} with no bias), a banner's pattern over its cloth. Off,
 * the last bit of the two depths differs, a TAA jitter decides each frame which way, and the eyes
 * flicker on and off. MoltenVK turns the same option on for the same reason. The game's own
 * shaders and Sodium's declare nothing invariant, so nothing of theirs changes.
 */
@Mixin(value = MTLDevice.class, remap = false)
public abstract class MTLDeviceMixin {

	@Unique
	private static MemorySegment vitrail$options = MemorySegment.NULL;

	@org.spongepowered.asm.mixin.injection.Inject(method = "newFunction", at = @At("HEAD"))
	private void vitrail$dump(String msl, String entry,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<MemorySegment> callback) {
		String dir = System.getProperty("vitrailmetal.dumpMsl");
		if (dir != null && msl.contains("[[position")) {
			try {
				java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
				java.nio.file.Files.writeString(java.nio.file.Path.of(dir, Integer.toHexString(msl.hashCode()) + ".metal"), msl);
			} catch (java.io.IOException ignored) {
			}
		}
	}

	@ModifyArg(method = "newFunction", index = 2, require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/metallum/objc/Msg;sendPtr(Ljava/lang/foreign/MemorySegment;"
							+ "Ljava/lang/foreign/MemorySegment;Ljava/lang/foreign/MemorySegment;"
							+ "Ljava/lang/foreign/MemorySegment;)Ljava/lang/foreign/MemorySegment;"))
	private MemorySegment vitrail$compileOptions(MemorySegment options) {
		if (Objc.nil(vitrail$options)) {
			MemorySegment made = Objc.ptr(Objc.ptr(Objc.cls("MTLCompileOptions"), "alloc"), "init");
			Objc.send(made, "setPreserveInvariance:", true);
			vitrail$options = made;
		}
		return vitrail$options;
	}
}
