package dev.leadlight.mixin.metal;

import io.github.kokodio.metaljvm.metal.MTLCompileOptions;
import io.github.kokodio.metaljvm.metal.MTLLanguageVersion;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compiles every MSL library with {@code preserveInvariance} and pinned to MSL 3.1, which Metallum
 * 0.0.25 leaves unset by handing Metal no compile options at all, for the reasons the 26.2 build's
 * {@code MTLDeviceMixin} gives: a mob's eyes and a banner's pattern drawn over their bodies at the
 * same depth through two programs, and a language version that otherwise follows whichever SDK the
 * running Java was linked against.
 */
@Mixin(targets = "com.metallum.render.MetalUtilities", remap = false)
public abstract class MetalUtilitiesMixin {

	@Unique
	private static volatile @Nullable MTLCompileOptions leadlight$options;

	@Unique
	private static final Object LEADLIGHT$LOCK = new Object();

	@ModifyArg(method = "newLibrary", index = 1, require = 1,
			at = @At(value = "INVOKE",
					target = "Lio/github/kokodio/metaljvm/metal/MTLDevice;newLibraryWithSource("
							+ "Ljava/lang/String;Lio/github/kokodio/metaljvm/metal/MTLCompileOptions;)"
							+ "Lio/github/kokodio/metaljvm/metal/MTLLibrary;"))
	private static MTLCompileOptions leadlight$compileOptions(MTLCompileOptions options) {
		MTLCompileOptions made = leadlight$options;
		if (made != null) {
			return made;
		}

		// Libraries compile on the engine's workers as well as the render thread.
		synchronized (LEADLIGHT$LOCK) {
			made = leadlight$options;
			if (made != null) {
				return made;
			}
			made = MTLCompileOptions.alloc().init();
			made.setPreserveInvariance(true);
			made.setLanguageVersion(MTLLanguageVersion.Version3_1);
			leadlight$options = made;
			return made;
		}
	}

	@ModifyArg(method = "newLibrary", index = 0, require = 1,
			at = @At(value = "INVOKE",
					target = "Lio/github/kokodio/metaljvm/metal/MTLDevice;newLibraryWithSource("
							+ "Ljava/lang/String;Lio/github/kokodio/metaljvm/metal/MTLCompileOptions;)"
							+ "Lio/github/kokodio/metaljvm/metal/MTLLibrary;"))
	private static String leadlight$dump(String msl) {
		String dir = System.getProperty("leadlight.dumpMsl");
		if (dir != null && msl.contains("[[position")) {
			try {
				Files.createDirectories(Path.of(dir));
				Files.writeString(Path.of(dir, Integer.toHexString(msl.hashCode()) + ".metal"), msl);
			} catch (IOException ignored) {
			}
		}
		return msl;
	}
}
