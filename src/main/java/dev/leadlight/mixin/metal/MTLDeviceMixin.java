package dev.leadlight.mixin.metal;

import com.metallum.mtl.MTLDevice;
import dev.leadlight.metal.Objc;
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
 * <p>
 * The options also pin the language to MSL 3.1. Left unset, Metal picks the newest version the
 * running executable was linked against, so the same shader compiles on one Java and not on another:
 * the launcher's runtime (Zulu, built on the macOS 13.3 SDK) gets MSL 3.0, a recent JDK the newest.
 * 3.1 is what {@link dev.leadlight.metal.MetalKernel} tells SPIRV-Cross to write for computes.
 */
@Mixin(value = MTLDevice.class, remap = false)
public abstract class MTLDeviceMixin {

	/** {@code MTLLanguageVersion3_1}: major in the high half, minor in the low. */
	@Unique
	private static final long LEADLIGHT$MSL_3_1 = (3L << 16) | 1;

	@Unique
	private static MemorySegment leadlight$options = MemorySegment.NULL;

	@org.spongepowered.asm.mixin.injection.Inject(method = "newFunction", at = @At("HEAD"))
	private void leadlight$dump(String msl, String entry,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<MemorySegment> callback) {
		String dir = System.getProperty("leadlight.dumpMsl");
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
	private MemorySegment leadlight$compileOptions(MemorySegment options) {
		if (Objc.nil(leadlight$options)) {
			MemorySegment made = Objc.ptr(Objc.ptr(Objc.cls("MTLCompileOptions"), "alloc"), "init");
			Objc.send(made, "setPreserveInvariance:", true);
			Objc.send(made, "setLanguageVersion:", LEADLIGHT$MSL_3_1);
			leadlight$options = made;
		}
		return leadlight$options;
	}
}
