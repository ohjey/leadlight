package dev.leadlight.mixin.metal;

import com.metallum.mtl.MTLCommandBuffer;
import com.metallum.mtl.MTLCommandEncoder;
import com.metallum.mtl.MTLFence;
import com.metallum.mtl.MTLRenderCommandEncoder;
import com.metallum.mtl.MTLRenderStages;
import com.metallum.mtl.LeadlightMetalEncoders;
import com.metallum.render.LeadlightMetalBridge;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.leadlight.Leadlight;
import dev.leadlight.metal.MetalEncoderAccess;
import dev.leadlight.metal.MultiTarget;
import dev.leadlight.metal.Objc;
import dev.leadlight.render.MipmapCommands;
import com.metallum.mtl.MTLBlitCommandEncoder;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The encoder half of {@link MultiTarget}: hands a pass the targets after nought as it is created,
 * and opens a Metal encoder over all of them when that pass first draws.
 * <p>
 * Metallum keeps one Metal encoder open across passes that draw into the same colour and depth
 * images. It does not know about the extra targets, so a single target pass never carries on in an
 * encoder that has them, and a multiple target pass carries on only in one with the same set.
 */
@Mixin(targets = "com.metallum.render.MetalCommandEncoder", remap = false)
public abstract class MetalCommandEncoderMixin implements MultiTarget.Encoder, MetalEncoderAccess, MipmapCommands {

	@Shadow
	private MTLCommandEncoder currentEncoder;

	@Shadow
	private MemorySegment renderColorAttachment;

	@Shadow
	private MemorySegment renderDepthAttachment;

	@Shadow
	@Final
	private MTLFence fence;

	@Shadow
	@Final
	private Map<Object, Vector4fc> pendingColorClears;

	/** The targets after nought of the Metal encoder open now, or null while it has only nought. */
	@Unique
	private MemorySegment @Nullable [] leadlight$openExtra;

	@Unique
	private boolean leadlight$said;

	@Shadow
	abstract void endEncoder();

	@Shadow
	abstract MTLCommandBuffer commandBuffer();

	@Shadow
	abstract MTLBlitCommandEncoder blitCommandEncoder();

	@Inject(method = "createRenderPass", at = @At("HEAD"), require = 1)
	private void leadlight$trace(RenderPassDescriptor descriptor,
			CallbackInfoReturnable<RenderPassBackend> callback) {
		if (!dev.leadlight.dev.FrameTrace.on()) {
			return;
		}
		StringBuilder line = new StringBuilder("pass ").append(descriptor.label().get()).append(" ->");
		for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment : descriptor.colorAttachments()) {
			if (attachment == null) {
				line.append(" -");
				continue;
			}
			GpuTexture texture = attachment.textureView().texture();
			line.append(' ').append(texture.getLabel()).append('@')
					.append(Integer.toHexString(System.identityHashCode(texture)))
					.append(attachment.clearValue().isPresent() ? "[clear]" : "")
					.append(this.pendingColorClears.containsKey(texture) ? "[pending]" : "");
		}
		dev.leadlight.dev.FrameTrace.line(line.toString());
	}

	@Inject(method = "clearColorTexture", at = @At("HEAD"), require = 1)
	private void leadlight$traceClear(GpuTexture texture, Vector4fc colour, CallbackInfo callback) {
		dev.leadlight.dev.FrameTrace.line("clear " + texture.getLabel() + "@"
				+ Integer.toHexString(System.identityHashCode(texture)));
	}

	@Inject(method = "submit", at = @At("HEAD"), require = 1)
	private void leadlight$traceFrame(CallbackInfo callback) {
		dev.leadlight.dev.FrameTrace.endFrame();
	}

	@Inject(method = "createRenderPass", at = @At("RETURN"), require = 1)
	private void leadlight$extraTargets(RenderPassDescriptor descriptor,
			CallbackInfoReturnable<RenderPassBackend> callback) {
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments =
				descriptor.colorAttachments();
		int count = Math.min(attachments.size(), MultiTarget.MAX_TARGETS);
		if (count < 2) {
			return;
		}

		GpuTextureView[] targets = new GpuTextureView[count - 1];
		Vector4fc[] clears = new Vector4fc[count - 1];
		boolean any = false;
		for (int i = 1; i < count; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = attachments.get(i);
			if (attachment == null) {
				continue;
			}

			GpuTextureView view = attachment.textureView();
			GpuTexture texture = view.texture();
			Vector4fc clear = attachment.clearValue().orElse(null);
			Vector4fc pending = this.pendingColorClears.get(texture);
			if (pending != null && clear == null) {
				if (wholeTexture(view)) {
					this.pendingColorClears.remove(texture);
					clear = pending;
				} else {
					LeadlightMetalBridge.flushPendingClear(this, texture);
				}
			} else {
				this.pendingColorClears.remove(texture);
			}

			LeadlightMetalBridge.markContentsDirty(texture);
			targets[i - 1] = view;
			clears[i - 1] = clear;
			any = true;
		}

		if (any) {
			((MultiTarget.Pass) callback.getReturnValue()).leadlight$extraTargets(this, targets, clears);
			if (!this.leadlight$said) {
				this.leadlight$said = true;
				Leadlight.logger().info("Metal: first pass drawing into {} colour targets at once is {}",
						count, descriptor.label().get());
			}
		}
	}

	@Inject(method = "renderCommandEncoder", at = @At("HEAD"), require = 1)
	private void leadlight$leaveMultiTarget(CallbackInfoReturnable<MTLRenderCommandEncoder> callback) {
		if (this.leadlight$openExtra != null) {
			this.endEncoder();
		}
	}

	@Inject(method = "endEncoder", at = @At("TAIL"), require = 1)
	private void leadlight$ended(CallbackInfo callback) {
		this.leadlight$openExtra = null;
	}

	@Override
	public MTLRenderCommandEncoder leadlight$encoder(GpuTextureView color, @Nullable GpuTextureView depth,
			@Nullable GpuTextureView[] extra, @Nullable Vector4fc clearColor, @Nullable Double clearDepth,
			@Nullable Vector4fc @Nullable [] extraClears) {
		MemorySegment colorHandle = LeadlightMetalBridge.nativeHandle(color);
		MemorySegment depthHandle = LeadlightMetalBridge.nativeHandle(depth);
		MemorySegment[] open = this.leadlight$openExtra;
		if (open != null && clearColor == null && clearDepth == null && extraClears == null
				&& this.currentEncoder instanceof MTLRenderCommandEncoder encoder
				&& same(this.renderColorAttachment, colorHandle)
				&& same(this.renderDepthAttachment, depthHandle)
				&& sameTargets(open, extra)) {
			return encoder;
		}

		this.endEncoder();
		MemorySegment[] colors = new MemorySegment[extra.length + 1];
		Vector4fc[] clears = new Vector4fc[extra.length + 1];
		colors[0] = colorHandle;
		clears[0] = clearColor;
		MemorySegment[] handles = new MemorySegment[extra.length];
		for (int i = 0; i < extra.length; i++) {
			handles[i] = LeadlightMetalBridge.nativeHandle(extra[i]);
			colors[i + 1] = handles[i];
			clears[i + 1] = extraClears == null ? null : extraClears[i];
		}

		MTLRenderCommandEncoder encoder = LeadlightMetalEncoders.open(this.commandBuffer(), colors, clears,
				depthHandle, clearDepth, color.getWidth(0), color.getHeight(0));
		encoder.waitForFence(this.fence, MTLRenderStages.VertexAndFragment);
		this.currentEncoder = encoder;
		this.renderColorAttachment = colorHandle;
		this.renderDepthAttachment = depthHandle;
		this.leadlight$openExtra = handles;
		return encoder;
	}

	@Unique
	private static boolean wholeTexture(GpuTextureView view) {
		return view.baseMipLevel() == 0
				&& view.mipLevels() >= view.texture().getMipLevels()
				&& view.texture().getDepthOrLayers() == 1;
	}

	@Unique
	private static boolean sameTargets(MemorySegment[] open, @Nullable GpuTextureView[] wanted) {
		if (open.length != wanted.length) {
			return false;
		}
		for (int i = 0; i < open.length; i++) {
			if (!same(open[i], LeadlightMetalBridge.nativeHandle(wanted[i]))) {
				return false;
			}
		}
		return true;
	}

	@Unique
	private static boolean same(MemorySegment a, MemorySegment b) {
		return a.address() == b.address();
	}

	@Override
	public MemorySegment leadlight$fence() {
		return this.fence.handle();
	}

	@Override
	public MemorySegment leadlight$commandBuffer() {
		return this.commandBuffer().handle();
	}

	@Override
	public void leadlight$endEncoder() {
		this.endEncoder();
	}

	/**
	 * Fills a mip chain the way {@code glGenerateMipmap} does, which on Metal is one blit command.
	 * A depth format is refused, as Metal refuses it, and the caller then keeps its readers on the
	 * base level.
	 */
	@Override
	public boolean leadlight$generateMipmaps(GpuTexture texture) {
		if (texture.getMipLevels() <= 1 || texture.isClosed() || texture.getFormat().hasDepthAspect()) {
			return false;
		}

		LeadlightMetalBridge.flushPendingClear(this, texture);
		MTLBlitCommandEncoder blit = this.blitCommandEncoder();
		Objc.send(blit.handle(), "generateMipmapsForTexture:", LeadlightMetalBridge.nativeHandle(texture));
		this.endEncoder();
		LeadlightMetalBridge.markContentsDirty(texture);
		return true;
	}

	/** The pass's Metal encoder is opened lazily at its first draw, so there is none to move yet. */
	@Override
	public boolean leadlight$viewport(int width, int height) {
		return false;
	}

	@Override
	public MemorySegment leadlight$blit() {
		return this.blitCommandEncoder().handle();
	}
}
