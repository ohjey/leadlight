package dev.vitrailmetal.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.metallum.render.VitrailMetalBridge;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vitrailmetal.mixin.access.GpuDeviceAccessor;
import org.lwjgl.vulkan.VK10;

import java.lang.foreign.MemorySegment;

/**
 * What the engine asks of a Vulkan device, answered for the Metal one Metallum brings up, which
 * this mod assumes is an Apple GPU of the M1 generation or later (Metallum runs nowhere else).
 */
public final class MetalHost {

	private MetalHost() {
	}

	/** Whether the game came up on Metallum's backend. */
	public static boolean isMetal(GpuDevice device) {
		return "Metal".equals(device.getDeviceInfo().backendName());
	}

	/**
	 * A Vulkan format feature bit, answered for Metal on Apple silicon: every colour format can be
	 * written from a shader and filtered unless it is an integer one (Apple GPUs filter 32 bit floats
	 * too), and a mip chain is filled by the blit encoder for every colour format and no depth one.
	 */
	public static boolean formatFeature(GpuFormat format, int bit) {
		boolean depth = format.name().startsWith("D") || format.name().contains("DEPTH");
		boolean integer = format.name().endsWith("_UINT") || format.name().endsWith("_SINT");
		return switch (bit) {
			case VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT -> !depth;
			case VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT -> !integer;
			case VK10.VK_FORMAT_FEATURE_BLIT_SRC_BIT, VK10.VK_FORMAT_FEATURE_BLIT_DST_BIT ->
					!depth && !integer;
			default -> !depth;
		};
	}

	private static MemorySegment compareSampler = MemorySegment.NULL;

	/**
	 * The sampler a {@code sampler2DShadow} lookup runs on: linear, clamped, comparing LEQUAL, the
	 * pair Iris binds for hardware shadow filtering, made once for the device's life. Metal's
	 * {@code sample_compare} reads its comparison off the sampler state, and a sampler made without
	 * one compares with "never", which is a scene with every light in shadow.
	 */
	public static MemorySegment compareSampler() {
		if (!Objc.nil(compareSampler)) {
			return compareSampler;
		}

		GpuDevice device = RenderSystem.getDevice();
		MemorySegment metal = VitrailMetalBridge.device(((GpuDeviceAccessor) device).vitrail$backend()).handle();
		MemorySegment descriptor = Objc.ptr(Objc.ptr(Objc.cls("MTLSamplerDescriptor"), "alloc"), "init");
		Objc.send(descriptor, "setMinFilter:", 1L);
		Objc.send(descriptor, "setMagFilter:", 1L);
		Objc.send(descriptor, "setMipFilter:", 0L);
		Objc.send(descriptor, "setSAddressMode:", 0L);
		Objc.send(descriptor, "setTAddressMode:", 0L);
		Objc.send(descriptor, "setCompareFunction:", 3L);
		compareSampler = Objc.ptr(metal, "newSamplerStateWithDescriptor:", descriptor);
		Objc.release(descriptor);
		return compareSampler;
	}
}
