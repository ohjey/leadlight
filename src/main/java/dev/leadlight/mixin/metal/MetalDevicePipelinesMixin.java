package dev.leadlight.mixin.metal;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import dev.leadlight.Leadlight;
import dev.leadlight.mixin.access.RenderPipelineAccessor;
import dev.leadlight.render.EntityMesh;
import dev.leadlight.render.PackChain;
import dev.leadlight.render.StalePipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@code VulkanDeviceMixin} does to the Vulkan device's pipeline cache, done to Metallum's.
 * <p>
 * <strong>The game's entity pipelines leave the cache when the mesh they read changes.</strong> A
 * compiled pipeline has its vertex layout baked in; when a pack loads, the entity mesh grows the
 * identifiers and tangents a pack reads, and a pipeline compiled before that reads every vertex at
 * the wrong stride. They are taken out of the map so the next bind compiles them again, and closed
 * at the next purge, when no frame in flight can still name them.
 * <p>
 * <strong>The live pack's own pipelines are carried over a purge.</strong> A resource reload empties
 * the whole cache, and a pack's programs come from its own archive, which no resource reload
 * touches: rebuilding them would hold the world back for nothing.
 */
@Mixin(targets = "com.metallum.render.MetalDevice", remap = false)
public abstract class MetalDevicePipelinesMixin implements StalePipelines {

	@Shadow
	@Final
	private Map<RenderPipeline, AutoCloseable> compiledPipelines;

	@Unique
	private final List<AutoCloseable> leadlight$setAside = new ArrayList<>();

	@Unique
	private final Map<RenderPipeline, AutoCloseable> leadlight$carriedOver = new LinkedHashMap<>();

	@Unique
	private boolean leadlight$closing;

	@Override
	public List<RenderPipeline> leadlight$dropEntityPipelines() {
		List<RenderPipeline> dropped = new ArrayList<>();
		Iterator<Map.Entry<RenderPipeline, AutoCloseable>> held = this.compiledPipelines.entrySet().iterator();
		while (held.hasNext()) {
			Map.Entry<RenderPipeline, AutoCloseable> entry = held.next();
			if (!declaresGameEntity(entry.getKey())) {
				continue;
			}

			this.leadlight$setAside.add(entry.getValue());
			dropped.add(entry.getKey());
			held.remove();
		}

		return dropped;
	}

	@Unique
	private static boolean declaresGameEntity(RenderPipeline pipeline) {
		if (EntityMesh.movingBlock(pipeline)) {
			return true;
		}

		@Nullable VertexFormat[] declared = ((RenderPipelineAccessor) pipeline).leadlight$declaredFormats();
		for (VertexFormat format : declared) {
			if (format == DefaultVertexFormat.ENTITY) {
				return true;
			}
		}

		return false;
	}

	/** Ahead-of-time Vulkan pipelines are never built on this backend. */
	@Override
	public boolean leadlight$adopt(RenderPipeline pipeline, VulkanRenderPipeline compiled) {
		return false;
	}

	@Inject(method = "clearPipelineCache", at = @At("HEAD"), require = 1)
	private void leadlight$carryTheLivePackOver(CallbackInfo callback) {
		this.leadlight$setAside.addAll(this.leadlight$carriedOver.values());
		this.leadlight$carriedOver.clear();
		if (this.leadlight$closing) {
			return;
		}

		int load = PackChain.liveLoad();
		String live = load == 0 ? null : "pipeline/pack/" + load + "/";
		Iterator<Map.Entry<RenderPipeline, AutoCloseable>> held = this.compiledPipelines.entrySet().iterator();
		int carried = 0;
		while (held.hasNext()) {
			Map.Entry<RenderPipeline, AutoCloseable> entry = held.next();
			Identifier location = entry.getKey().getLocation();
			if (live != null && location.getNamespace().equals(Leadlight.MOD_ID)
					&& location.getPath().startsWith(live)) {
				this.leadlight$carriedOver.put(entry.getKey(), entry.getValue());
				held.remove();
				carried++;
			}
		}

		if (carried > 0) {
			Leadlight.logger().info("Device purge: {} pipelines of the live pack carried over it", carried);
		}
	}

	@Inject(method = "clearPipelineCache", at = @At("TAIL"), require = 1)
	private void leadlight$closeThePurge(CallbackInfo callback) {
		for (AutoCloseable stale : this.leadlight$setAside) {
			try {
				stale.close();
			} catch (Exception e) {
				Leadlight.logger().warn("Closing a stale Metal pipeline failed: {}", e.toString());
			}
		}
		this.leadlight$setAside.clear();
		this.compiledPipelines.putAll(this.leadlight$carriedOver);
		this.leadlight$carriedOver.clear();
	}

	@Inject(method = "close", at = @At("HEAD"), require = 1)
	private void leadlight$closing(CallbackInfo callback) {
		this.leadlight$closing = true;
	}
}
