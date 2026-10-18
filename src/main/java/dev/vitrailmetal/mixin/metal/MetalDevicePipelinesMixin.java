package dev.vitrailmetal.mixin.metal;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import dev.vitrailmetal.Vitrail;
import dev.vitrailmetal.mixin.access.RenderPipelineAccessor;
import dev.vitrailmetal.render.EntityMesh;
import dev.vitrailmetal.render.PackChain;
import dev.vitrailmetal.render.StalePipelines;
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
	private final List<AutoCloseable> vitrail$setAside = new ArrayList<>();

	@Unique
	private final Map<RenderPipeline, AutoCloseable> vitrail$carriedOver = new LinkedHashMap<>();

	@Unique
	private boolean vitrail$closing;

	@Override
	public List<RenderPipeline> vitrail$dropEntityPipelines() {
		List<RenderPipeline> dropped = new ArrayList<>();
		Iterator<Map.Entry<RenderPipeline, AutoCloseable>> held = this.compiledPipelines.entrySet().iterator();
		while (held.hasNext()) {
			Map.Entry<RenderPipeline, AutoCloseable> entry = held.next();
			if (!declaresGameEntity(entry.getKey())) {
				continue;
			}

			this.vitrail$setAside.add(entry.getValue());
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

		@Nullable VertexFormat[] declared = ((RenderPipelineAccessor) pipeline).vitrail$declaredFormats();
		for (VertexFormat format : declared) {
			if (format == DefaultVertexFormat.ENTITY) {
				return true;
			}
		}

		return false;
	}

	/** Ahead-of-time Vulkan pipelines are never built on this backend. */
	@Override
	public boolean vitrail$adopt(RenderPipeline pipeline, VulkanRenderPipeline compiled) {
		return false;
	}

	@Inject(method = "clearPipelineCache", at = @At("HEAD"), require = 1)
	private void vitrail$carryTheLivePackOver(CallbackInfo callback) {
		this.vitrail$setAside.addAll(this.vitrail$carriedOver.values());
		this.vitrail$carriedOver.clear();
		if (this.vitrail$closing) {
			return;
		}

		int load = PackChain.liveLoad();
		String live = load == 0 ? null : "pipeline/pack/" + load + "/";
		Iterator<Map.Entry<RenderPipeline, AutoCloseable>> held = this.compiledPipelines.entrySet().iterator();
		int carried = 0;
		while (held.hasNext()) {
			Map.Entry<RenderPipeline, AutoCloseable> entry = held.next();
			Identifier location = entry.getKey().getLocation();
			if (live != null && location.getNamespace().equals(Vitrail.MOD_ID)
					&& location.getPath().startsWith(live)) {
				this.vitrail$carriedOver.put(entry.getKey(), entry.getValue());
				held.remove();
				carried++;
			}
		}

		if (carried > 0) {
			Vitrail.logger().info("Device purge: {} pipelines of the live pack carried over it", carried);
		}
	}

	@Inject(method = "clearPipelineCache", at = @At("TAIL"), require = 1)
	private void vitrail$closeThePurge(CallbackInfo callback) {
		for (AutoCloseable stale : this.vitrail$setAside) {
			try {
				stale.close();
			} catch (Exception e) {
				Vitrail.logger().warn("Closing a stale Metal pipeline failed: {}", e.toString());
			}
		}
		this.vitrail$setAside.clear();
		this.compiledPipelines.putAll(this.vitrail$carriedOver);
		this.vitrail$carriedOver.clear();
	}

	@Inject(method = "close", at = @At("HEAD"), require = 1)
	private void vitrail$closing(CallbackInfo callback) {
		this.vitrail$closing = true;
	}
}
