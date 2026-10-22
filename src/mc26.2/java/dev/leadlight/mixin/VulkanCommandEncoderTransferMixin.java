package dev.leadlight.mixin;

import dev.leadlight.render.timing.TransferProbe;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * Feeds {@link TransferProbe} from the one Vulkan encoder every {@code CommandEncoder} shares, which
 * is the only place that knows whether a pass is open whoever asked.
 * <p>
 * One injection per call rather than a name matching several overloads: the five-argument
 * {@code copyTextureToBuffer} hands over to the nine-argument one, and counting both would count one
 * copy twice.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderTransferMixin {

	@Shadow
	private VulkanRenderPass currentRenderPass;

	@Unique
	private Supplier<String> leadlight$open() {
		return this.currentRenderPass == null ? null : this.currentRenderPass.getLabel();
	}

	@Inject(method = "writeToBuffer(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Ljava/nio/ByteBuffer;)V",
			at = @At("HEAD"))
	private void leadlight$writeToBuffer(CallbackInfo ci) {
		TransferProbe.seen("writeToBuffer", leadlight$open());
	}

	@Inject(method = "copyToBuffer(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
			+ "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("HEAD"))
	private void leadlight$copyToBuffer(CallbackInfo ci) {
		TransferProbe.seen("copyToBuffer", leadlight$open());
	}

	@Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
			at = @At("HEAD"))
	private void leadlight$writeToTexture(CallbackInfo ci) {
		TransferProbe.seen("writeToTexture", leadlight$open());
	}

	@Inject(method = "copyBufferToTexture(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;IIII"
			+ "Lcom/mojang/blaze3d/textures/GpuTexture;IIIIII)V", at = @At("HEAD"))
	private void leadlight$copyBufferToTexture(CallbackInfo ci) {
		TransferProbe.seen("copyBufferToTexture", leadlight$open());
	}

	@Inject(method = "copyTextureToBuffer(Lcom/mojang/blaze3d/textures/GpuTexture;"
			+ "Lcom/mojang/blaze3d/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V", at = @At("HEAD"))
	private void leadlight$copyTextureToBuffer(CallbackInfo ci) {
		TransferProbe.seen("copyTextureToBuffer", leadlight$open());
	}

	@Inject(method = "copyTextureToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;"
			+ "Lcom/mojang/blaze3d/textures/GpuTexture;IIIIIII)V", at = @At("HEAD"))
	private void leadlight$copyTextureToTexture(CallbackInfo ci) {
		TransferProbe.seen("copyTextureToTexture", leadlight$open());
	}

	@Inject(method = "clearColorTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;)V",
			at = @At("HEAD"))
	private void leadlight$clearColorTexture(CallbackInfo ci) {
		TransferProbe.seen("clearColorTexture", leadlight$open());
	}

	@Inject(method = "clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V", at = @At("HEAD"))
	private void leadlight$clearDepthTexture(CallbackInfo ci) {
		TransferProbe.seen("clearDepthTexture", leadlight$open());
	}

	@Inject(method = "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;"
			+ "Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V", at = @At("HEAD"))
	private void leadlight$clearColorAndDepthTextures(CallbackInfo ci) {
		TransferProbe.seen("clearColorAndDepthTextures", leadlight$open());
	}

	@Inject(method = "clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;"
			+ "Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;DIIII)V", at = @At("HEAD"))
	private void leadlight$clearColorAndDepthRegion(CallbackInfo ci) {
		TransferProbe.seen("clearColorAndDepthTextures (region)", leadlight$open());
	}

	@Inject(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)"
			+ "Lcom/mojang/blaze3d/systems/RenderPassBackend;", at = @At("HEAD"))
	private void leadlight$createRenderPass(CallbackInfoReturnable<?> cir) {
		TransferProbe.seen("createRenderPass", leadlight$open());
	}
}
