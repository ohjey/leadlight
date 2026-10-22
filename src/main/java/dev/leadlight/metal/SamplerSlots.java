package dev.leadlight.metal;

import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslConstexprSampler;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import dev.leadlight.Leadlight;
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
		byte @Nullable [] leadlight$samplerSlots();

		/** Bits of the resources the pipeline reads through a shadow comparison. */
		long leadlight$comparedMask();
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
		remap(compiler, null);
	}

	/**
	 * The same, for a Metallum that places each binding at a Metal index of its own rather than at
	 * its SPIR-V binding: {@code textureIndex[binding]} is where Metallum put that binding's texture,
	 * which the slot table then keeps it at while the sampler moves to its slot. Called after
	 * Metallum's own placement, since the last placement of a binding is the one SPIRV-Cross keeps.
	 */
	public static void remap(long compiler, int @Nullable [] textureIndex) {
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
			String[] names = new String[sampled];
			boolean[] integer = new boolean[sampled];
			SpvcReflectedResource.Buffer reflected = sampled == 0 ? null
					: SpvcReflectedResource.create(list.get(0), sampled);
			for (int i = 0; i < sampled; i++) {
				ids[i] = reflected.get(i).id();
				bindings[i] = Spvc.spvc_compiler_get_decoration(compiler, ids[i], Spv.SpvDecorationBinding);
				sets[i] = Spvc.spvc_compiler_get_decoration(compiler, ids[i],
						Spv.SpvDecorationDescriptorSet);
				names[i] = reflected.get(i).nameString();
				long type = Spvc.spvc_compiler_get_type_handle(compiler, reflected.get(i).base_type_id());
				int sampledType = Spvc.spvc_type_get_basetype(Spvc.spvc_compiler_get_type_handle(compiler,
						Spvc.spvc_type_get_image_sampled_type(type)));
				integer[i] = sampledType == Spvc.SPVC_BASETYPE_INT32 || sampledType == Spvc.SPVC_BASETYPE_UINT32;
			}

			boolean[] baked = overflow(names, bindings, sampled);
			int[] order = Arrays.stream(bindings).filter(b -> !bakedBinding(b, bindings, baked)).distinct()
					.sorted().toArray();
			byte[][] building = BUILDING.get();
			if (building == null) {
				building = new byte[2][];
				BUILDING.set(building);
			}
			int top = Arrays.stream(bindings).max().orElse(-1);
			byte[] table = new byte[top + 1];
			Arrays.fill(table, (byte) -1);
			for (int i = 0; i < sampled; i++) {
				if (baked[i]) {
					SpvcMslConstexprSampler fixed = SpvcMslConstexprSampler.calloc(stack);
					Spvc.spvc_msl_constexpr_sampler_init(fixed);
					int filter = integer[i] ? Spvc.SPVC_MSL_SAMPLER_FILTER_NEAREST
							: Spvc.SPVC_MSL_SAMPLER_FILTER_LINEAR;
					int address = names[i].equals("noisetex") ? Spvc.SPVC_MSL_SAMPLER_ADDRESS_REPEAT
							: Spvc.SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE;
					fixed.coord(Spvc.SPVC_MSL_SAMPLER_COORD_NORMALIZED)
							.min_filter(filter).mag_filter(filter)
							.mip_filter(integer[i] ? Spvc.SPVC_MSL_SAMPLER_MIP_FILTER_NEAREST
									: Spvc.SPVC_MSL_SAMPLER_MIP_FILTER_LINEAR)
							.s_address(address).t_address(address).r_address(address);
					Spvc.spvc_compiler_msl_remap_constexpr_sampler_by_binding(compiler, sets[i], bindings[i],
							fixed);
					continue;
				}

				int slot = Arrays.binarySearch(order, bindings[i]);
				table[bindings[i]] = (byte) Math.min(slot, 127);

				SpvcMslResourceBinding binding = SpvcMslResourceBinding.malloc(stack);
				Spvc.spvc_msl_resource_binding_init(binding);
				binding.stage(model)
						.desc_set(sets[i])
						.binding(bindings[i])
						.msl_buffer(textureAt(textureIndex, bindings[i]))
						.msl_texture(textureAt(textureIndex, bindings[i]))
						.msl_sampler(slot);
				Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding);
			}
			if (Arrays.stream(order).count() > SLOTS) {
				Leadlight.logger().warn("Metal: a stage reads {} textures whose samplers cannot be baked "
						+ "in, past Metal's {} sampler slots", order.length, SLOTS);
			}
			building[stage] = table;
		}
	}

	/** Metal's sampler slots in one stage. */
	private static int textureAt(int @Nullable [] textureIndex, int binding) {
		return textureIndex == null || binding < 0 || binding >= textureIndex.length ? binding
				: textureIndex[binding];
	}

	private static final int SLOTS = 16;

	private static final java.util.regex.Pattern COLOUR = java.util.regex.Pattern.compile("colortex\\d+");

	/**
	 * Which textures of a stage get their sampler baked into the shader, so the rest fit in
	 * Metal's sixteen slots. None while they fit. Past that, every colour target: what Vitrail binds for a colour target is a clamped sampler, linear unless the
	 * format is integer, and a mip filter that only matters where the read asks for a level (the
	 * translation pins every read of a target without a chain to level nought), so a baked sampler
	 * reads it as the bound one would. Depth, shadow, noise and custom images keep real slots, their
	 * samplers being the ones that differ from pass to pass.
	 */
	private static boolean[] overflow(String[] names, int[] bindings, int count) {
		boolean[] baked = new boolean[count];
		if (count <= SLOTS) {
			return baked;
		}

		// Metal counts a baked sampler against the same sixteen, but merges identical ones, and the
		// colour targets' all come to one or two: so past the limit every colour target is baked.
		for (int i = 0; i < count; i++) {
			baked[i] = COLOUR.matcher(names[i]).matches();
		}
		return baked;
	}

	private static boolean bakedBinding(int binding, int[] bindings, boolean[] baked) {
		for (int i = 0; i < bindings.length; i++) {
			if (bindings[i] == binding && baked[i]) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The table of the pipeline just compiled on this thread: for binding {@code b}, entry
	 * {@code 2b} is its vertex sampler slot and {@code 2b + 1} its fragment one, minus one where that
	 * stage does not read it. Null where no stage needed a slot other than its binding.
	 */
	/** Slot tables handed from a compile to a pipeline built later, possibly on another thread. */
	private static final java.util.Map<Object, byte[]> ATTACHED =
			java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	/** Keeps {@code slots} with {@code compiled} until the pipeline built from it {@link #claim}s them. */
	public static void attach(Object compiled, byte @Nullable [] slots) {
		if (slots != null) {
			ATTACHED.put(compiled, slots);
		}
	}

	public static byte @Nullable [] claim(Object compiled) {
		return ATTACHED.remove(compiled);
	}

	public static byte @Nullable [] take() {
		return take(false);
	}

	/**
	 * @param always keep the table even where every sampler stayed at its binding, for a Metallum
	 *               that binds samplers at an index of its own rather than at the binding
	 */
	public static byte @Nullable [] take(boolean always) {
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

		return moved || always ? table : null;
	}

	/** The slot binding {@code binding} takes in {@code stage}, by a table {@link #take} made. */
	public static int slot(byte @Nullable [] table, int binding, int stage) {
		if (table == null || binding * 2 + stage >= table.length) {
			return binding;
		}

		return table[binding * 2 + stage];
	}
}
