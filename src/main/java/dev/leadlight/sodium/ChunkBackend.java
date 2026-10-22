package dev.leadlight.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.leadlight.metal.MetalHost;
import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;

/**
 * Whether the game runs on OpenGL, where none of the engine's chunk programs is served.
 * <p>
 * Sodium's own draw backend is not the answer by itself. Sodium for 26.3 takes its Vulkan draw
 * contexts only for a Vulkan device and its OpenGL one for anything else, so on Metallum 0.0.25 it
 * reports {@code OPENGL} while the device underneath pushes constants, which is what the region
 * offset of the pack's chunk programs is read from, just as Vulkan's does.
 */
public final class ChunkBackend {

	private ChunkBackend() {
	}

	public static boolean openGl() {
		return DrawBackend.BACKEND == DrawBackend.OPENGL && !MetalHost.isMetal(RenderSystem.getDevice());
	}
}
