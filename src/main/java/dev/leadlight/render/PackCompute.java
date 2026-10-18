package dev.leadlight.render;

import dev.leadlight.cache.ModuleCache;
import dev.leadlight.glsl.PackProgram;
import dev.leadlight.glsl.SharedMemory;
import dev.leadlight.glsl.TranslatedUnit;
import dev.leadlight.mixin.access.CommandEncoderAccessor;
import dev.leadlight.mixin.access.GpuDeviceAccessor;
import dev.leadlight.pack.model.ProgramNames;
import dev.leadlight.pack.model.ProgramStage;
import dev.leadlight.pack.model.RenderStage;
import dev.leadlight.pack.model.TargetName;
import dev.leadlight.pack.model.TextureStage;
import dev.leadlight.pack.source.OpenedPack;
import dev.leadlight.pack.target.SamplerPlan;
import dev.leadlight.pack.target.TargetSchedule;
import dev.leadlight.pack.texture.CustomImages;
import dev.leadlight.render.storage.GpuRecording;
import dev.leadlight.render.storage.StorageBuffers;
import dev.leadlight.render.storage.StorageImages;
import dev.leadlight.uniform.ClipSpace;
import dev.leadlight.uniform.UniformCatalog;
import dev.leadlight.Leadlight;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.metallum.mtl.MTLDevice;
import com.metallum.render.LeadlightMetalBridge;
import dev.leadlight.metal.MetalCompute;
import dev.leadlight.metal.MetalHost;
import dev.leadlight.metal.MetalKernel;
import org.jspecify.annotations.Nullable;
import java.lang.foreign.MemorySegment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pack's compute passes: the shadow computes, dispatched at the head of the frame, and the
 * computes hanging off a full screen pass, dispatched right before that pass.
 * <p>
 * The shadow computes run in the parity of the gbuffers that read what they propagate; the
 * volumes they read are the previous frame's shadow-geometry writes, one frame late like the
 * shadow map itself. Complementary's floodfill lives in {@code shadowcomp.csh}. Iris runs it
 * inside its shadow render ({@code ShadowRenderer.java:632-633}, the debug group and the
 * {@code compositeRenderer.renderAll()} under it), before its gbuffers in the SAME frame. Under
 * this engine's deferred shadow stage, the head of the frame is that moment's translation.
 * <p>
 * The chained computes, {@code deferred4_a.csh} for {@code deferred4}, run where Iris runs them:
 * in a loop right before their pass, with a memory barrier after ({@code CompositeRenderer.java:289-300}),
 * reading and storing the colour targets on the halves that pass reads. Photon builds its sky
 * lighting in one, and without it everything in shadow was black.
 * <p>
 * The Java facade has no compute, so the pipeline is built the way the storage probe was: shaderc
 * kind 2, push descriptors, and a storage image that is either the pack's own through VMA or a
 * colour target created with the usage for it.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris ComputeProgram, LGPL-3.0</a>
 */
final class PackCompute implements AutoCloseable {

	private static final int SHADERC_VULKAN_1_2 = 4202496;
	private static final int SHADERC_COMPUTE = 2;

	/**
	 * Stage token hashed into {@link ModuleCache}'s key for this road, and nowhere else. The
	 * game's compiler never sees a compute: shaderc kind, optimisation and target live only in
	 * {@link Pass#compileSpirv}. Naming those here keeps a later {@code ShaderType.COMPUTE}
	 * through {@code GlslCompiler} from serving this blob, or the other way around.
	 */
	private static final String MODULE_CACHE_STAGE = "COMPUTE/shaderc-opt2-vulkan1.2";

	/**
	 * A common ceiling on a pushed descriptor set, the same one the graphics side carries in
	 * {@link PackPass}. Nothing in the game asks the device for its own, so a dispatch past this
	 * is named and still made: the failure, if it comes, is a driver error that the line makes
	 * readable.
	 */
	private static final int PUSH_DESCRIPTORS = 32;

	/** The pattern of a colour target written as an image, {@code colorimg4} for {@code colortex4}. */
	private static final Pattern COLOUR_IMAGE = Pattern.compile("\\bcolorimg(\\d+)\\b");

	/** The shadow computes, dispatched at the head of the frame. */
	private final List<Pass> passes;

	/**
	 * The computes hanging off a full screen pass, by that pass, each list in letter order. Iris
	 * dispatches them right before the pass ({@code CompositeRenderer.java:289-300}, the loop over
	 * {@code compositePass.computes} with a memory barrier after), and so does the chain here.
	 */
	private final Map<String, List<Pass>> chained;

	/**
	 * The same, by the program they hang off, for the programs this place draws no pass for: the
	 * pack ships less than both halves of the program, or it switched the program off itself.
	 * Nothing draws them, so there is no pass to run these before and they are dispatched at that
	 * program's own moment in the frame, which is where Iris runs them: a stage entry with computes
	 * and no valid source becomes a pass of computes alone, at its own index of the stage
	 * ({@code CompositeRenderer.java:137-145}), dispatched and barriered like any other and then
	 * skipped before the draw ({@code :304-307}).
	 */
	private final Map<String, List<Pass>> alone;

	/** The targets some compute writes as {@code colorimgN}, which are created writable for it. */
	private final Set<Integer> storageTargets;

	private boolean announced;

	/** The passes whose computes have been announced once, which is once per pass and not per frame. */
	private final Set<String> announcedChains = new LinkedHashSet<>();

	private PackCompute(List<Pass> passes, Map<String, List<Pass>> chained,
			Map<String, List<Pass>> alone, Set<Integer> storageTargets) {
		this.passes = List.copyOf(passes);
		this.chained = Map.copyOf(chained);
		this.alone = Map.copyOf(alone);
		this.storageTargets = Set.copyOf(storageTargets);
	}

	static PackCompute none() {
		return new PackCompute(List.of(), Map.of(), Map.of(), Set.of());
	}

	/** The targets to create writable from a compute, read before the first allocation. */
	Set<Integer> storageTargets() {
		return this.storageTargets;
	}

	/** Whether any compute hangs off that full screen pass. */
	boolean hangsOff(String program) {
		return this.chained.containsKey(program);
	}

	/**
	 * Whether a compute hanging off a full screen pass samples {@code centerDepthSmooth}, keyed
	 * like the passes on the declaration surviving the translation. Such a compute is handed the
	 * texel the same way its pass is, so a chain whose only reader is one has to fold it the same
	 * way: Iris arms the fold from its compute builder as it does from a composite
	 * ({@code pipeline/CompositeRenderer.java:470}).
	 * <p>
	 * The shadow computes do not count. They run at the head of the frame, before the opaque
	 * world whose depth the fold takes exists, and Iris hands them no such sampler at all: its
	 * shadow builder ({@code pipeline/IrisRenderingPipeline.java:544},
	 * {@code createShadowComputes}) gives them the targets, the custom textures and images, the
	 * level samplers, the noise and the shadow set, and the centre depth is bound nowhere but in
	 * the composite and final builders. A shadow compute declaring the name reads here what the
	 * fold last left, or white where nothing arms it, which is nearer what it reads under Iris
	 * than a fold of its own would be.
	 */
	boolean readsCenterDepth() {
		return readsCenterDepth(this.chained) || readsCenterDepth(this.alone);
	}

	private static boolean readsCenterDepth(Map<String, List<Pass>> computes) {
		for (List<Pass> passes : computes.values()) {
			for (Pass pass : passes) {
				for (TranslatedUnit.Uniform sampler : pass.compute.loaded().program().samplers()) {
					if (sampler.name().equals(SamplerPlan.centerDepth())) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/**
	 * Reads every compute the pack ships and the plan kept, out of the opening the load already
	 * holds: the shadow computes for the head of the frame, and the computes hanging off a pass of
	 * the chain for the moment before that pass.
	 * <p>
	 * The opening is handed in and not taken here, and this loop is why it matters: read a program
	 * at a time from a pack path, each turn mounted the archive again and walked every source file
	 * of it to rebuild the same index of the same settings, so a pack with four computes paid for
	 * four whole readings of itself to translate four files.
	 *
	 * @param running the programs the chain draws, which is where a chained compute can hang
	 * @param passing the programs this place draws no pass for, the pack having shipped less than
	 *                both halves of them or switched them off itself. They draw nothing and so have
	 *                no pass to hang a compute off; their computes are kept all the same and
	 *                dispatched at that program's own moment in the frame, as Iris runs them
	 *                ({@code CompositeRenderer.java:137-145}). Asked before {@code running}, since a
	 *                program shipped with one half only is in both. A program in neither set has its
	 *                computes left where they were, and the plan's notes carry the reason
	 */
	static PackCompute load(OpenedPack pack, String place, List<String> computes, int load,
			UniformCatalog shadowCatalog, UniformCatalog chainCatalog, Set<String> running,
			Set<String> passing) {
		List<Pass> passes = new ArrayList<>();
		Map<String, List<Pass>> chained = new LinkedHashMap<>();
		Map<String, List<Pass>> alone = new LinkedHashMap<>();
		Set<Integer> storageTargets = new LinkedHashSet<>();
		for (String name : computes) {
			boolean shadow = ProgramNames.shadowComposite(ProgramNames.familyOf(name));
			Optional<String> base = ProgramNames.computeBase(name);
			// Setup has no moment in this frame yet. The plan already names it.
			if (!shadow && base.isEmpty()) {
				continue;
			}

			// A program the place merely draws no pass for was never asked about: it is the
			// reference's own invalid source, and its computes run there, so they run here. What is
			// left over is a program taken out of the frame on purpose, by the pass filter at the
			// user's ask or by this engine refusing a sampler it cannot bind, and a final nothing
			// draws, whose computes the reference builds with the final and never without it. The
			// plan tells the three apart in its notes; here they take the same road.
			boolean standalone = !shadow && passing.contains(base.get());
			if (!shadow && !standalone && !running.contains(base.get())) {
				Leadlight.logger().warn("compute {} is not dispatched: nothing of this chain runs {}, "
						+ "and the plan's notes say what took it out", name, base.get());
				continue;
			}

			String path = place.isEmpty() ? name : place + "/" + name;
			try {
				Optional<PackProgram.Compute> compute = PackProgram.loadCompute(pack, path);
				if (compute.isEmpty()) {
					continue;
				}

				List<String> unusable = StorageImages.unusableViews(compute.get().loaded().program()
						.samplers().stream().map(TranslatedUnit.Uniform::name).toList());
				if (!unusable.isEmpty()) {
					Leadlight.logger().warn("compute {} is not dispatched: it reads {} through a view "
							+ "this device cannot sample and store in that format", path, unusable);
					continue;
				}

				// Ahead of the roads parting and once for the program: any of the three can be
				// missing a word, and a word means the same on all of them, that the directive it
				// belongs to is read as absent. What that absence costs is the size said below.
				// Said here rather than beside a size, where a word off a road the dispatch never
				// took would read as the reason for a size that was read correctly.
				List<String> unread = compute.get().unresolved();
				if (!unread.isEmpty()) {
					Leadlight.logger().info("compute {} reads no number for {}, so that directive "
							+ "counts as absent", path, String.join(", ", unread));
				}

				// Left undispatched rather than dispatched at a guessed size. A program on one of
				// the screen roads is sized by dividing the screen by its own local size, and a
				// local size this engine cannot read as a number would have to be invented: read
				// as one where the shader means sixteen, the guess asks for a group per pixel and
				// stalls the frame instead of drawing it wrong.
				if (!compute.get().sized()) {
					Leadlight.logger().warn("compute {} is not dispatched: it leaves its work "
							+ "group count to the engine and writes its local size as something "
							+ "other than a number, so how much work it asks for cannot be read",
							path);
					continue;
				}

				Pass pass = new Pass(compute.get(), shadow ? shadowCatalog : chainCatalog, load,
						path, shadow ? null : base.get());
				if (shadow) {
					passes.add(pass);
					Leadlight.logger().info("Loaded shadow compute {} ({})", path, sizing(compute.get()));
				} else if (standalone) {
					alone.computeIfAbsent(base.get(), _ -> new ArrayList<>()).add(pass);
					storageTargets.addAll(colourImagesOf(compute.get()));
					Leadlight.logger().info("Loaded compute {} at the moment of {}, which draws "
							+ "nothing here ({})", path, base.get(), sizing(compute.get()));
				} else {
					chained.computeIfAbsent(base.get(), _ -> new ArrayList<>()).add(pass);
					storageTargets.addAll(colourImagesOf(compute.get()));
					Leadlight.logger().info("Loaded compute {} before {} ({})", path, base.get(),
							sizing(compute.get()));
				}

				// The one binding the pack's own text cannot be read for, said here for a compute
				// as PackPass.describe says it for a pass. Past all three roads rather than on one
				// of them, and once per program: a compute of a full screen family takes the
				// default whether or not the program it hangs off draws anything, and Pegasus,
				// whose prepare stage is four computes and no pass, would never have said so.
				List<String> defaulted = compute.get().loaded().samplers().defaulted();
				if (!defaulted.isEmpty()) {
					Leadlight.logger().info("{} reads {} by default under {}", path,
							TargetName.canonical(SamplerPlan.DEFAULT_TARGET),
							String.join(", ", defaulted));
				}
			} catch (IOException | RuntimeException e) {
				Leadlight.logger().warn("compute {} could not be translated: {}", path, e.toString());
			}
		}

		chained.values().forEach(list -> list.sort(
				Comparator.comparing(pass -> ProgramNames.computeLetter(pass.name))));
		alone.values().forEach(list -> list.sort(
				Comparator.comparing(pass -> ProgramNames.computeLetter(pass.name))));

		return new PackCompute(passes, chained, alone, storageTargets);
	}

	/** The colour targets a compute names as an image, read off its translated text. */
	private static Set<Integer> colourImagesOf(PackProgram.Compute compute) {
		Set<Integer> indices = new LinkedHashSet<>();
		TranslatedUnit unit = compute.loaded().program().stages().get(ProgramStage.COMPUTE);
		if (unit == null) {
			return indices;
		}

		Matcher matcher = COLOUR_IMAGE.matcher(unit.text());
		while (matcher.find()) {
			indices.add(Integer.parseInt(matcher.group(1)));
		}

		return indices;
	}

	/**
	 * Dispatches the computes hanging off that full screen pass, right before it, as Iris does.
	 * Nothing happens for a pass with none, which is every pass of most packs.
	 *
	 * @param step    the halves that pass reads and writes, which is where its computes read a
	 *                colour target from and write one to: Iris binds both the sampler and the
	 *                image on the flipped state of that moment
	 *                ({@code IrisImages.addRenderTargetImages})
	 * @param depth   what that pass reads as {@code depthtex0}, already in the pack's own window,
	 *                and so what its computes read under the same name: Iris hands a compute the
	 *                three depth names of the pass it hangs off through the same
	 *                {@code IrisSamplers.addCompositeSamplers} call
	 *                ({@code pipeline/CompositeRenderer.java:462} against {@code :406}), and the
	 *                far terrain's through the same {@code addRenderTargetSamplers}
	 *                ({@code :454} against {@code :398})
	 * @param distant what that pass reads as {@code dhDepthTex0}, on the same split, or null for
	 *                the far plane on the frames the pack drew no far terrain
	 */
	void dispatchBefore(String program, CommandEncoder encoder, GpuDevice device, PackValues values,
			ColorTargets targets, TargetSchedule.Bound step, GpuTextureView depth,
			GpuTextureView distant, int width, int height) {
		dispatchChain(this.chained.get(program), program, encoder, device, values, targets, step,
				depth, distant, width, height);
	}

	/**
	 * Dispatches the computes hanging off a program this place draws no pass for, at the moment
	 * that program would have run, which is what Iris does with them. Nothing happens for a name
	 * with none, which is every name of nearly every pack.
	 * <p>
	 * The same road as a chained dispatch and deliberately so: Iris builds one kind of compute for
	 * both and runs them in the same loop, the only difference being that the pass around them
	 * draws nothing and is left where the others set their state up
	 * ({@code CompositeRenderer.java:304-307}). What is not the same is where the halves come from,
	 * and the caller answers that: {@link TargetSchedule#passing} rather than the step of a pass,
	 * since there is no pass.
	 */
	void dispatchAlone(String program, CommandEncoder encoder, GpuDevice device, PackValues values,
			ColorTargets targets, TargetSchedule.Bound step, GpuTextureView depth,
			GpuTextureView distant, int width, int height) {
		dispatchChain(this.alone.get(program), program, encoder, device, values, targets, step,
				depth, distant, width, height);
	}

	/** The programs {@link #dispatchAlone} answers for, which the chain has to find a moment for. */
	Set<String> standingAlone() {
		return this.alone.keySet();
	}

	private void dispatchChain(List<Pass> attached, String program, CommandEncoder encoder,
			GpuDevice device, PackValues values, ColorTargets targets, TargetSchedule.Bound step,
			GpuTextureView depth, GpuTextureView distant, int width, int height) {
		if (attached == null || attached.isEmpty()) {
			return;
		}

		// The hold first, as the head-of-frame dispatch does: the first pass of a chain half can
		// still have the geometry's render pass object open over it.
		GeometryHold.flush(() -> "the compute dispatch at " + program);
		GpuRecording.endPass(encoder);
		MTLDevice metal = metal(device);
		if (metal == null) {
			return;
		}

		for (Pass pass : attached) {
			try {
				pass.dispatch(metal, encoder, values, targets, width, height, step, depth, distant);
			} catch (GpuDeviceLossException e) {
				throw e;
			} catch (RuntimeException e) {
				if (pass.failed.add(e.toString())) {
					Leadlight.logger().warn("compute {} failed: {}", pass.path, e.toString());
				}
			}
		}

		if (this.announcedChains.add(program)) {
			Leadlight.logger().info("Dispatched {} compute pass(es) at {}", attached.size(), program);
		}
	}

	void dispatch(PackValues values, ColorTargets targets) {
		// A frame the targets refused is one no pass of the chain draws, and this dispatch runs
		// ahead of the call that would refuse it again, and of the first call of all after a
		// load: a compute pushed here would be handed names nothing stands behind, each thrown
		// and said once, over a frame nothing reads.
		if (this.passes.isEmpty() || !targets.usable()) {
			return;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return;
		}

		CommandEncoder encoder = device.createCommandEncoder();
		// The hold first, and through its own door: it keeps a RenderPass OBJECT open across
		// draws, so ending the pass underneath it would leave that object to close a pass the
		// encoder no longer has, which is a crash at the next flush and not here.
		GeometryHold.flush(() -> "the shadow compute dispatch");
		GpuRecording.endPass(encoder);
		MTLDevice metal = metal(device);
		if (metal == null) {
			return;
		}

		values.convention(ClipSpace.FORWARD);
		values.modelView(null, null);
		values.projection(null);
		values.passColour(null);
		values.renderStage(RenderStage.NONE);
		// Asked of the game and not of ColorTargets, which is the same number one frame late or
		// nought on the first. ColorTargets.screenWidth is only written by its ensure(), which
		// runs inside the world render, while this dispatch is placed at the head of the frame
		// before any of it: on the first frame after a pack loads the fields are still nought,
		// which is a dispatch of no groups at all, and every resize afterwards would size a frame
		// by the window it had before. Iris asks the same object at the same moment,
		// ShadowCompositeRenderer.java:213-214.
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();
		int width = main == null ? 0 : main.width;
		int height = main == null ? 0 : main.height;
		for (Pass pass : this.passes) {
			try {
				pass.dispatch(metal, encoder, values, targets, width, height, null, null, null);
			} catch (GpuDeviceLossException e) {
				throw e;
			} catch (RuntimeException e) {
				if (pass.failed.add(e.toString())) {
					Leadlight.logger().warn("shadow compute {} failed: {}", pass.path, e.toString());
				}
			}
		}

		if (!this.announced) {
			this.announced = true;
			Leadlight.logger().info("Dispatched {} shadow compute pass(es) at the head of the frame",
					this.passes.size());
		}
	}

	/**
	 * Frees every pass this load built: the shadow computes, the ones hanging off a full screen
	 * pass, and the ones dispatched at the moment of a program this place draws no pass for.
	 * <p>
	 * A pass holds a shader module, a descriptor set layout, a pipeline layout, a pipeline, a ring
	 * of uniform buffers, and on MoltenVK the buffer its shared variables were moved into when it
	 * has one. {@link Pass#destroy} queues the module, both layouts, the pipeline and that buffer
	 * on {@link GpuRecording#destroyLater}, and {@link Pass#close} queues the ring there
	 * as well rather than closing it where it stands, since this is not a quiet moment: every
	 * error path of a frame calls {@link PackChain#release} in the middle of one, after the chain
	 * has dispatched these computes, so a pass is closed while frames that still name its objects
	 * are in flight.
	 * <p>
	 * Every container of passes declared above has to be named here, and one added later has to be
	 * added here with it: {@link Pass} is private to this class, so a pass this method does not
	 * reach is a pass nothing can reach, and it lives until the process ends.
	 */
	@Override
	public void close() {
		this.passes.forEach(Pass::close);
		this.chained.values().forEach(list -> list.forEach(Pass::close));
		this.alone.values().forEach(list -> list.forEach(Pass::close));
	}

	/** The Metal device, or null where the game is not on Metallum. */
	private static @Nullable MTLDevice metal(GpuDevice device) {
		GpuDeviceBackend backend = ((GpuDeviceAccessor) device).leadlight$backend();
		return MetalHost.isMetal(device) ? LeadlightMetalBridge.device(backend) : null;
	}

	/**
	 * What sizes this program's dispatch, in the pack's own terms. Only the count a pack writes
	 * out for itself is a number before the frame runs: the two roads that go by the screen are
	 * settled at the size the frame is drawn at, so what is named here is the road and the tile of
	 * pixels one group of it covers.
	 */
	private static String sizing(PackProgram.Compute compute) {
		if (compute.fixed()) {
			return compute.groupsX() + "x" + compute.groupsY() + "x" + compute.groupsZ() + " groups";
		}

		String covered = compute.relative()
				? compute.renderX() + " by " + compute.renderY() + " of the screen"
				: "the whole screen";
		return covered + ", " + compute.localX() + "x" + compute.localY() + " pixels to a group";
	}

	/**
	 * The views the engine serves a pack program under these names, for a compute that samples
	 * them. White where a map is not there, for the same reason the passes answer white: it is
	 * the far plane in the pack's window, so a lookup that finds nothing reads "nothing between
	 * here and the light" rather than a world in its own shadow.
	 */
	private static GpuTextureView engineView(ColorTargets targets, String name) {
		ShadowTargets shadow = targets.shadow();
		return switch (name) {
			case "noisetex" -> targets.noise();
			// The HW spelling reads the same image as its plain twin, which is the whole of what
			// SEPARATE_HARDWARE_SAMPLERS asks for. A compute makes every comparison in arithmetic
			// whatever the pack declared, so nothing but the name reaches this line.
			case "shadowtex0", "shadowtex0HW" ->
					orWhite(targets, shadow == null ? null : shadow.depth());
			case "shadowtex1", "shadowtex1HW" ->
					orWhite(targets, shadow == null ? null : shadow.depthWithoutTranslucents());
			// Every name SamplerPlan reads as a shadow colour, the bare shadowcolor with them, and
			// the white stand-in for one no program of the place named. That is the rule a full
			// screen pass follows for the same names (PackPass.java:640), and the ceiling is not
			// consulted here because it does not have to be: a buffer the pack may not reach was
			// never allocated, so colour() answers null for it and white is what the compute reads,
			// exactly as it does for a buffer nothing has written.
			//
			// White covers one more case than it should, and that is a divergence rather than a
			// rule: which shadow buffers get allocated is read off the FRAGMENT stages of the place
			// alone (TargetPlan.read fills shadowNamed there and nowhere else), so a shadowcolor
			// that only a COMPUTE of the place names is never seen, and this hands the compute
			// white for a buffer the pack does mean to read. Iris opens it from the compute itself,
			// addShadowSamplers calling createIfEmpty for each shadowcolor the program declares, on
			// the compute path as on the composite one (CompositeRenderer.java:465,
			// samplers/IrisSamplers.java:156-164). Nothing of the corpus asks for it: every
			// shadowcolor a compute of it names is named by a fragment stage of the same place too.
			default -> SamplerPlan.isShadowColour(name)
					? orWhite(targets, shadow == null ? null : shadow.colour(SamplerPlan.shadowColour(name)))
					: null;
		};
	}

	private static GpuTextureView orWhite(ColorTargets targets, GpuTextureView view) {
		return view == null ? targets.white() : view;
	}

	/**
	 * The same sampler state the graphics passes bind for the name. The noise field repeats and
	 * is filtered, Iris's choice: a pack indexes it in texels well past one, and clamped it reads
	 * the same edge row for the whole volume. A shadow colour stays LINEAR, which is what the two
	 * graphics roads bind for it whatever the pack declares; a shadow depth is filtered the way the
	 * map itself says, and it is asked of the map for the reason a volume is asked of its format: a
	 * compute reads an image exactly as the composite after it will.
	 * <p>
	 * The kind comes from the plan and not from the spelling of the name. {@code shadow} and
	 * {@code watershadow} read a depth image without carrying the word, and which of the pair they
	 * read is the program's answer rather than the name's.
	 */
	private static GpuSampler samplerFor(ColorTargets targets, SamplerPlan samplers, String name) {
		boolean noise = "noisetex".equals(name);
		// Every other name keeps the answer this line gave before there was one place to ask:
		// customImageFilter is NEAREST for a name no image directive declared.
		FilterMode filter = noise ? FilterMode.LINEAR : switch (samplers.binding(name).kind()) {
			case SHADOW_COLOUR -> FilterMode.LINEAR;
			case SHADOW_DEPTH -> targets.shadow().depthFilter(samplers.withoutTranslucents(name));
			default -> PackPass.customImageFilter(name);
		};

		// The map's chain is the third road's half of one answer, asked of the map like the filter:
		// it is filled at the tail of the stage that drew it and stands for the whole frame, so a
		// compute reads it exactly as the pass it hangs off does. Nothing else a compute binds
		// carries a chain a dispatch could safely climb.
		boolean mipmaps = !noise && samplers.binding(name).kind() == SamplerPlan.Kind.SHADOW_DEPTH
				&& targets.shadow().depthMipmapped(samplers.withoutTranslucents(name));

		return PackPass.sampler(noise, filter, mipmaps);
	}

	/**
	 * Makes the shadow fragment {@code imageStore} into voxel volumes visible to
	 * {@code shadowcomp}'s {@code texelFetch} of the same images. The game's barrier is
	 * compute-to-compute storage only, which does not cover a sampled read of a volume the
	 * geometry just wrote.
	 */
	private static final class Pass {

		private final PackProgram.Compute compute;
		private final PackUniforms uniforms;
		private final String path;
		private final String name;

		/** The full screen pass this compute hangs off, or null for a shadow compute. */
		private final String program;

		/**
		 * Which stage a {@code texture.<stage>.<sampler>} line has to name for this compute to read
		 * the file behind it. A shadow compute is the {@code shadowcomp} stage by construction.
		 */
		private final TextureStage textureStage;

		private final String label;
		private final Set<String> failed = new LinkedHashSet<>();
		private MappableRingBuffer block;
		private @Nullable MetalKernel kernel;
		private List<ComputeShader.Binding> entries = List.of();
		private boolean compiled;

		/**
		 * The std430 size of the block {@link SharedMemory} moved this compute's shared variables
		 * into, or 0 where they keep their threadgroup memory.
		 */
		private long sharedBytes;
		private @Nullable GpuBuffer sharedBuffer;

		private Pass(PackProgram.Compute compute, UniformCatalog catalog, int load, String path,
				String program) {
			this.compute = compute;
			this.path = path;
			this.name = path.substring(path.lastIndexOf('/') + 1);
			this.program = program;
			this.textureStage = program == null
					? TextureStage.SHADOWCOMP
					: TextureStage.of(program).orElse(null);
			this.label = "pack/" + load + "/" + path + "/compute";
			this.uniforms = new PackUniforms(compute.loaded().program().uniforms(), catalog);
		}

		/** One resource resolved for a dispatch: a buffer, or a texture with or without a sampler. */
		private record Bound(@Nullable GpuBuffer buffer, long offset, @Nullable GpuTextureView view,
				@Nullable MemorySegment raw, @Nullable GpuSampler sampler) {

			static Bound buffer(GpuBuffer buffer, long offset) {
				return new Bound(buffer, offset, null, null, null);
			}

			static Bound texture(GpuTextureView view, @Nullable GpuSampler sampler) {
				return new Bound(null, 0L, view, null, sampler);
			}

			/** A buffer of the engine's own making, a pack's storage buffer. */
			static Bound rawBuffer(MemorySegment buffer) {
				return new Bound(null, 0L, null, buffer, null);
			}

			/** A texture of the engine's own making, a pack's custom image. */
			static Bound raw(MemorySegment texture, @Nullable GpuSampler sampler) {
				return new Bound(null, 0L, null, texture, sampler);
			}
		}

		/**
		 * @param step    the halves of the pass this compute hangs off, or null for a shadow
		 *                compute, which reads no colour target
		 * @param depth   the depth the pass this compute hangs off reads, or null for a shadow compute
		 * @param distant the far terrain's depth on the same terms
		 */
		private void dispatch(MTLDevice metal, CommandEncoder encoder, PackValues values,
				ColorTargets targets, int width, int height, TargetSchedule.Bound step,
				GpuTextureView depth, GpuTextureView distant) {
			if (!this.compiled) {
				compile(metal);
			}

			MetalKernel kernel = this.kernel;
			if (kernel == null) {
				return;
			}

			writeBlock(values);
			Bound[] bound = new Bound[this.entries.size()];
			List<GpuTexture> touched = new ArrayList<>();
			for (int i = 0; i < bound.length; i++) {
				bound[i] = resolve(this.entries.get(i), targets, step, depth, distant);
				if (bound[i].view() != null) {
					touched.add(bound[i].view().texture());
				}
			}

			MetalCompute pass = MetalCompute.open(encoder, touched);
			if (pass == null) {
				return;
			}

			try (pass) {
				pass.pipeline(kernel.pipeline());
				for (MetalKernel.Slot slot : kernel.slots()) {
					Bound one = slot.binding() < bound.length ? bound[slot.binding()] : null;
					if (one == null) {
						continue;
					}

					if (one.buffer() != null) {
						pass.buffer(LeadlightMetalBridge.nativeHandle(one.buffer()), one.offset(), slot.index());
						continue;
					}

					if (slot.kind() == MetalKernel.Kind.UNIFORM_BUFFER
							|| slot.kind() == MetalKernel.Kind.STORAGE_BUFFER) {
						pass.buffer(one.raw(), 0L, slot.index());
						continue;
					}

					pass.texture(one.raw() != null ? one.raw() : LeadlightMetalBridge.nativeHandle(one.view()),
							slot.index());
					if (slot.sampler() >= 0 && one.sampler() != null) {
						pass.sampler(LeadlightMetalBridge.nativeHandle(one.sampler()), slot.sampler());
					}
				}

				int[] groups = this.compute.groupsAt(width, height);
				pass.dispatch(groups[0], groups[1], groups[2], kernel.localX(), kernel.localY(),
						kernel.localZ());
			}

			for (Bound one : bound) {
				if (one.view() != null && one.sampler() == null) {
					LeadlightMetalBridge.markContentsDirty(one.view().texture());
				}
			}

			if (this.block != null) {
				this.block.rotate();
			}
		}

		private void compile(MTLDevice metal) {
			this.compiled = true;
			TranslatedUnit unit = this.compute.loaded().program().stages().get(ProgramStage.COMPUTE);
			if (unit == null) {
				return;
			}

			ComputeShader.MetalCompiled built;
			try {
				built = ComputeShader.buildMetal(metal, this.label, sharedMemory(unit.text()),
						MODULE_CACHE_STAGE, Pass::compileSpirv);
			} catch (GpuDeviceLossException e) {
				throw e;
			} catch (Exception e) {
				Leadlight.logger().warn("compute {} SPIR-V failed: {}", this.path, e.toString());
				return;
			}

			if (built == null) {
				return;
			}

			this.kernel = built.kernel();
			this.entries = built.entries();
			if (this.sharedBytes > 0L) {
				GpuDevice device = RenderSystem.getDevice();
				this.sharedBuffer = device.createBuffer(() -> "leadlight compute shared " + this.path,
						GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, this.sharedBytes);
			}

			Leadlight.logger().info("Compiled compute {} for Metal ({}, {}x{}x{} threads to a group)",
					this.path, sizing(this.compute), this.kernel.localX(), this.kernel.localY(),
					this.kernel.localZ());
		}

		/**
		 * The text to compile: the translation as it stands, or on MoltenVK the same with its shared
		 * variables moved into a storage buffer, where Metal would refuse the threadgroup memory they
		 * take. {@link SharedMemory} says why the buffer is the same memory and what the move does to
		 * the barriers.
		 * <p>
		 * Only for a pack count of one work group on every axis, which is what makes one buffer the
		 * memory every invocation of the dispatch shares: the count a pack writes is dispatched as
		 * written, and a count off the screen is as many groups as the window asks for. Anything else
		 * keeps its threadgroup memory, and the log says why Metal will refuse it. The translation
		 * itself is not touched, so its store holds one answer for every driver, and the module store
		 * is keyed on the text returned here, which differs wherever the move was made.
		 */
		private String sharedMemory(String translated) {
			if (!SharedMemory.moltenVk() || !SharedMemory.mentioned(translated)) {
				return translated;
			}

			String preprocessed = preprocess(translated);
			if (preprocessed == null) {
				return translated;
			}

			SharedMemory.Reading reading = SharedMemory.read(preprocessed);
			if (reading.unread() != null) {
				Leadlight.logger().warn("compute {} declares shared memory this engine cannot size, "
						+ "{}, so it keeps its threadgroup memory on Metal", this.path, reading.unread());
				return translated;
			}

			if (!reading.over()) {
				return translated;
			}

			if (this.compute.groupsX() != 1 || this.compute.groupsY() != 1
					|| this.compute.groupsZ() != 1) {
				Leadlight.logger().warn("compute {} asks Metal for {} bytes of threadgroup memory, past "
						+ "the {} it allows, and does not dispatch a single work group, where one buffer "
						+ "would not be the memory each group has of its own, so Metal refuses it",
						this.path, reading.threadgroupBytes(), SharedMemory.THREADGROUP_BYTES);
				return translated;
			}

			this.sharedBytes = reading.bufferBytes();
			Leadlight.logger().info("compute {} asks Metal for {} bytes of threadgroup memory, past the "
					+ "{} it allows, so its shared variables are served from a storage buffer of {} bytes",
					this.path, reading.threadgroupBytes(), SharedMemory.THREADGROUP_BYTES,
					this.sharedBytes);
			return reading.moved();
		}

		private static ByteBuffer compileSpirv(String source) {
			ByteBuffer optimised = compileSpirv(source, 2);
			if (optimised != null || !lastRefusedByOptimiser) {
				return optimised;
			}

			// shaderc compiled the module and only its optimiser refused it, which a pack passing a
			// formatted image to a parameter declared without the format does (Photon's floodfill):
			// SPIR-V calls those two types and the optimiser checks, where neither SPIRV-Cross nor
			// Metal tells them apart. The module as compiled goes through.
			Leadlight.logger().info("compute compiled without optimisation, which the optimiser refused");
			return compileSpirv(source, 0);
		}

		/** Whether the last refusal was the optimiser's alone, the compile having succeeded. */
		private static boolean lastRefusedByOptimiser;

		private static ByteBuffer compileSpirv(String source, int optimisation) {
			long compiler = Shaderc.shaderc_compiler_initialize();
			long options = compileOptions();
			Shaderc.shaderc_compile_options_set_optimization_level(options, optimisation);
			ByteBuffer sourceBuffer = MemoryUtil.memUTF8(source, false);
			ByteBuffer filename = MemoryUtil.memUTF8("shadowcomp.csh");
			ByteBuffer entry = MemoryUtil.memUTF8("main");
			long result = 0L;
			lastRefusedByOptimiser = false;
			try {
				result = Shaderc.shaderc_compile_into_spv(compiler, sourceBuffer, SHADERC_COMPUTE,
						filename, entry, options);
				int status = Shaderc.shaderc_result_get_compilation_status(result);
				if (status != 0) {
					String message = Shaderc.shaderc_result_get_error_message(result);
					lastRefusedByOptimiser = optimisation != 0 && message != null
							&& message.contains("failed to optimize");
					if (!lastRefusedByOptimiser) {
						Leadlight.logger().warn("compute shaderc: {}", message);
						dumpFailed(source);
					}
					return null;
				}

				ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memCalloc(spirv.remaining());
				MemoryUtil.memCopy(spirv, copy);
				return copy;
			} finally {
				if (result != 0L) {
					Shaderc.shaderc_result_release(result);
				}

				MemoryUtil.memFree(entry);
				MemoryUtil.memFree(filename);
				MemoryUtil.memFree(sourceBuffer);
				Shaderc.shaderc_compile_options_release(options);
				Shaderc.shaderc_compiler_release(compiler);
			}
		}

		/** Writes a compute shaderc refused under the game directory, when asked to for debugging. */
		private static void dumpFailed(String source) {
			if (!Boolean.getBoolean("leadlight.dumpFailed")) {
				return;
			}
			try {
				java.nio.file.Path dir = Leadlight.platform().gameDirectory().resolve(Leadlight.MOD_ID).resolve("failed");
				java.nio.file.Files.createDirectories(dir);
				java.nio.file.Path file = dir.resolve("compute-" + Integer.toHexString(source.hashCode()) + ".csh");
				java.nio.file.Files.writeString(file, source);
				Leadlight.logger().warn("compute source written to {}", file);
			} catch (IOException e) {
				Leadlight.logger().warn("could not write the failed compute: {}", e.toString());
			}
		}

		/** The options a compute is compiled and preprocessed at, released by the caller. */
		private static long compileOptions() {
			long options = Shaderc.shaderc_compile_options_initialize();
			Shaderc.shaderc_compile_options_set_target_env(options, 0, SHADERC_VULKAN_1_2);
			Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
			Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
			Shaderc.shaderc_compile_options_set_generate_debug_info(options);
			// Performance, and for the LAYOUT before speed: the pack's common include declares
			// samplers a compute never reads, an unoptimised module keeps them, and reflection then
			// demands a binding for every one. Optimised, the dead declarations fall out and the
			// entries are the names the shader touches.
			Shaderc.shaderc_compile_options_set_optimization_level(options, 2);
			return options;
		}

		/**
		 * The text through shaderc's preprocessor alone, at the options it is compiled at, or null
		 * where it does not preprocess, which the compile after it then says.
		 */
		private static String preprocess(String source) {
			long compiler = Shaderc.shaderc_compiler_initialize();
			long options = compileOptions();
			ByteBuffer sourceBuffer = MemoryUtil.memUTF8(source, false);
			ByteBuffer filename = MemoryUtil.memUTF8("shadowcomp.csh");
			ByteBuffer entry = MemoryUtil.memUTF8("main");
			long result = 0L;
			try {
				result = Shaderc.shaderc_compile_into_preprocessed_text(compiler, sourceBuffer,
						SHADERC_COMPUTE, filename, entry, options);
				if (result == 0L) {
					return null;
				}

				ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
				if (Shaderc.shaderc_result_get_compilation_status(result) != 0 || bytes == null) {
					return null;
				}

				return StandardCharsets.UTF_8.decode(bytes).toString();
			} finally {
				if (result != 0L) {
					Shaderc.shaderc_result_release(result);
				}

				MemoryUtil.memFree(entry);
				MemoryUtil.memFree(filename);
				MemoryUtil.memFree(sourceBuffer);
				Shaderc.shaderc_compile_options_release(options);
				Shaderc.shaderc_compiler_release(compiler);
			}
		}

		/** Whether that entry is the block the shared variables were moved into. */
		private boolean servesShared(String name) {
			return this.sharedBytes > 0L && SharedMemory.BLOCK.equals(name);
		}

		private void writeBlock(PackValues values) {
			int bytes = Math.max(16, this.uniforms.size());
			if (this.block == null) {
				this.block = new MappableRingBuffer(() -> "leadlight shadowcomp " + this.path,
						GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, bytes);
			}

			try (GpuBufferSlice.MappedView view = this.block.currentBuffer().map(false, true)) {
				ByteBuffer data = view.data();
				data.position(0);
				this.uniforms.write(Std140Builder.intoBuffer(data), values.world());
			}
		}

		/** What one entry of the module reads or writes for this dispatch. */
		private Bound resolve(ComputeShader.Binding entry, ColorTargets targets,
				TargetSchedule.Bound step, GpuTextureView depth, GpuTextureView distant) {
			SamplerPlan samplers = this.compute.loaded().samplers();
			if (entry.buffer()) {
				if (servesShared(entry.name()) && this.sharedBuffer != null) {
					return Bound.buffer(this.sharedBuffer, 0L);
				}

				if (StorageBuffers.named(entry.name())) {
					StorageBuffers.Bound storage = StorageBuffers.bound(entry.name());
					if (storage == null) {
						throw new IllegalStateException("Missing storage buffer " + entry.name());
					}
					return Bound.rawBuffer(MemorySegment.ofAddress(storage.buffer()));
				}

				GpuBufferSlice slice = this.block.currentBuffer().slice(0, Math.max(16, this.uniforms.size()));
				return Bound.buffer(slice.buffer(), slice.offset());
			}

			StorageImages.Bound image = StorageImages.bound(entry.name());
			if (image != null) {
				return Bound.raw(image.texture(), image.storage()
						? null : samplerFor(targets, samplers, entry.name()));
			}

			if (CustomImages.storage(entry.name())) {
				throw new IllegalStateException("Missing storage image " + entry.name());
			}

			ColorTargets.PackBinding supplied = targets.packTexture(this.textureStage, entry.name());
			if (supplied != null && supplied.view() != null) {
				return Bound.texture(supplied.view(),
						PackPass.sampler(supplied.repeat(), supplied.filter(), supplied.mipmaps()));
			}

			SamplerPlan.Binding taken = samplers.binding(entry.name());
			if (taken.kind() == SamplerPlan.Kind.PACK_TEXTURE && !taken.defaulted()
					&& targets.black() != null) {
				return Bound.texture(targets.black(), samplerFor(targets, samplers, entry.name()));
			}

			if (step != null) {
				Bound colour = colourTarget(entry.name(), targets, samplers, step, this.program);
				if (colour != null) {
					return colour;
				}
			}

			GpuTextureView read = switch (SamplerPlan.classify(entry.name())) {
				case DEPTH -> PackPass.depth(entry.name(), targets, depth);
				case DISTANT_DEPTH -> PackPass.distant(entry.name(), targets, distant);
				case CENTER_DEPTH -> PackPass.centerDepth(targets);
				default -> null;
			};
			if (read == null) {
				read = engineView(targets, entry.name());
			}
			if (read != null) {
				return Bound.texture(read, samplerFor(targets, samplers, entry.name()));
			}

			if (taken.defaulted()) {
				String screen = TargetName.canonical(SamplerPlan.DEFAULT_TARGET);
				if (taken.kind() == SamplerPlan.Kind.COLORTEX && step != null) {
					Bound colour = colourTarget(screen, targets, samplers, step, this.program);
					if (colour != null) {
						return colour;
					}
				}

				ColorTargets.PackBinding laid = targets.packTexture(this.textureStage, screen);
				if (laid != null && laid.view() != null) {
					return Bound.texture(laid.view(),
							PackPass.sampler(laid.repeat(), laid.filter(), laid.mipmaps()));
				}

				if (targets.black() != null) {
					return Bound.texture(targets.black(), samplerFor(targets, samplers, entry.name()));
				}
			}

			throw new IllegalStateException("Missing image " + entry.name());
		}

		/**
		 * A colour target, read as {@code colortexN} or written as {@code colorimgN}, on the half
		 * the pass this compute hangs off reads: Iris binds both off the same flipped state
		 * ({@code IrisSamplers.addRenderTargetSamplers}, {@code IrisImages.addRenderTargetImages}),
		 * so a compute stores into the very half the pass after it will sample.
		 *
		 * @return what the name binds, or null where it is not a colour target
		 */
		private static @Nullable Bound colourTarget(String name, ColorTargets targets,
				SamplerPlan samplers, TargetSchedule.Bound step, String program) {
			Matcher image = COLOUR_IMAGE.matcher(name);
			if (image.matches()) {
				int index = Integer.parseInt(image.group(1));
				TargetSurface surface = targets.surface(index, step.read(index));
				if (surface == null || !surface.storage() || surface.storageView() == null) {
					throw new IllegalStateException(name + " is not a target created writable from "
							+ "a compute");
				}

				return Bound.texture(surface.storageView(), null);
			}

			OptionalInt index = TargetName.index(name);
			if (index.isEmpty()) {
				return null;
			}

			TargetSurface surface = targets.surface(index.getAsInt(), step.read(index.getAsInt()));
			if (surface == null || surface.view() == null) {
				return targets.black() == null ? null
						: Bound.texture(targets.black(), samplerFor(targets, samplers, name));
			}

			boolean mipmaps = surface.chainWritten()
					&& targets.lodReads(program).contains(index.getAsInt());
			return Bound.texture(surface.view(),
					PackPass.sampler(false, targets.filter(index.getAsInt()), mipmaps));
		}

		private void close() {
			MetalKernel kernel = this.kernel;
			GpuBuffer shared = this.sharedBuffer;
			this.kernel = null;
			this.sharedBuffer = null;
			GpuRecording.destroyLater(() -> {
				if (kernel != null) {
					kernel.release();
				}
				if (shared != null) {
					shared.close();
				}
			});

			if (this.block != null) {
				GpuRecording.destroyLater(this.block::close);
				this.block = null;
			}
		}
	}
}
