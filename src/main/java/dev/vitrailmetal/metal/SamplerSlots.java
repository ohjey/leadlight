package dev.vitrailmetal.metal;

import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.util.Arrays;

/**
 * Gives each stage's samplers Metal slots of their own, numbered from nought.
 * <p>
 * Metallum numbers a pipeline's uniform blocks and textures in one sequence, the order of the bind
 * group layout, and hands each texture's sampler the texture's own number. Metal has sixteen sampler
 * slots a stage, so a pack program reading fifteen textures beside a uniform block is already past
 * them, and the pipeline is refused with "'sampler' attribute parameter is out of bounds". Textures
 * have far more room, so they keep their numbers; only the samplers are renumbered, in each stage
 * apart, by their rank among the textures that stage really reads.
 * <p>
 * The two halves have to agree: {@link #remap} writes the slots into the MSL as each stage is cross
 * compiled and records them, and the pipeline built straight after takes the record
 * ({@link #take}), which the pass then reads as it binds ({@link #slot}). A program whose stage
 * reads more than sixteen textures is still past Metal's limit, and that is said by Metal itself.
 */
public final class SamplerSlots {

	/** Index of the vertex half of an entry of a table; the fragment half follows it. */
	public static final int VERTEX = 0;
	public static final int FRAGMENT = 1;

	/** What the stages cross compiled on this thread so far for the pipeline being built. */
	private static final ThreadLocal<byte @Nullable [][]> BUILDING = new ThreadLocal<>();

	/** Added to Metallum's compiled pipeline. */
	public interface Holder {
		byte @Nullable [] vitrail$samplerSlots();
	}

	private SamplerSlots() {
	}

	/** Called as a pipeline starts compiling, so a failure halfway leaves nothing for the next. */
	public static void begin() {
		BUILDING.remove();
	}

	/**
	 * Writes compact sampler slots for every sampled image the stage reaches into the cross
	 * compiler, and keeps them for the pipeline. Textures and buffers keep the number their binding
	 * decoration gives them, which is what Metallum binds them at.
	 */
	public static void remap(long compiler) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int model = Spvc.spvc_compiler_get_execution_model(compiler);
			int stage = model == Spv.SpvExecutionModelVertex ? VERTEX
					: model == Spv.SpvExecutionModelFragment ? FRAGMENT : -1;
			if (stage < 0) {
				return;
			}

			PointerBuffer pointer = stack.mallocPointer(1);
			if (Spvc.spvc_compiler_get_active_interface_variables(compiler, pointer) != Spvc.SPVC_SUCCESS) {
				return;
			}
			long active = pointer.get(0);
			if (Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, pointer, active)
					!= Spvc.SPVC_SUCCESS) {
				return;
			}
			long resources = pointer.get(0);

			PointerBuffer list = stack.mallocPointer(1);
			PointerBuffer count = stack.mallocPointer(1);
			if (Spvc.spvc_resources_get_resource_list_for_type(resources,
					Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, list, count) != Spvc.SPVC_SUCCESS) {
				return;
			}

			int sampled = (int) count.get(0);
			int[] ids = new int[sampled];
			int[] bindings = new int[sampled];
			int[] sets = new int[sampled];
			SpvcReflectedResource.Buffer reflected = sampled == 0 ? null
					: SpvcReflectedResource.create(list.get(0), sampled);
			for (int i = 0; i < sampled; i++) {
				ids[i] = reflected.get(i).id();
				bindings[i] = Spvc.spvc_compiler_get_decoration(compiler, ids[i], Spv.SpvDecorationBinding);
				sets[i] = Spvc.spvc_compiler_get_decoration(compiler, ids[i],
						Spv.SpvDecorationDescriptorSet);
			}

			int[] order = sortedBindings(bindings);
			byte[][] building = BUILDING.get();
			if (building == null) {
				building = new byte[2][];
				BUILDING.set(building);
			}
			int top = Arrays.stream(bindings).max().orElse(-1);
			byte[] table = new byte[top + 1];
			Arrays.fill(table, (byte) -1);
			for (int i = 0; i < sampled; i++) {
				int slot = Arrays.binarySearch(order, bindings[i]);
				table[bindings[i]] = (byte) Math.min(slot, 127);

				SpvcMslResourceBinding binding = SpvcMslResourceBinding.malloc(stack);
				Spvc.spvc_msl_resource_binding_init(binding);
				binding.stage(model)
						.desc_set(sets[i])
						.binding(bindings[i])
						.msl_buffer(bindings[i])
						.msl_texture(bindings[i])
						.msl_sampler(slot);
				Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding);
			}
			building[stage] = table;
		}
	}

	private static int[] sortedBindings(int[] bindings) {
		return Arrays.stream(bindings).distinct().sorted().toArray();
	}

	/**
	 * The table of the pipeline just compiled on this thread: for binding {@code b}, entry
	 * {@code 2b} is its vertex sampler slot and {@code 2b + 1} its fragment one, minus one where that
	 * stage does not read it. Null where no stage needed a slot other than its binding.
	 */
	public static byte @Nullable [] take() {
		byte[][] building = BUILDING.get();
		BUILDING.remove();
		if (building == null) {
			return null;
		}

		int size = 0;
		for (byte[] stage : building) {
			size = Math.max(size, stage == null ? 0 : stage.length);
		}
		byte[] table = new byte[size * 2];
		Arrays.fill(table, (byte) -1);
		boolean moved = false;
		for (int stage = 0; stage < 2; stage++) {
			byte[] slots = building[stage];
			if (slots == null) {
				continue;
			}
			for (int binding = 0; binding < slots.length; binding++) {
				table[binding * 2 + stage] = slots[binding];
				moved |= slots[binding] >= 0 && slots[binding] != binding;
			}
		}

		return moved ? table : null;
	}

	/** The slot binding {@code binding} takes in {@code stage}, by a table {@link #take} made. */
	public static int slot(byte @Nullable [] table, int binding, int stage) {
		if (table == null || binding * 2 + stage >= table.length) {
			return binding;
		}

		int slot = table[binding * 2 + stage];
		return slot < 0 ? binding : slot;
	}
}
