package dev.leadlight.metal;

import com.metallum.mtl.MTLDevice;
import dev.leadlight.Leadlight;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * A pack's compute stage built for Metal: its SPIR-V cross compiled to an MSL kernel by the same
 * SPIRV-Cross the game ships, and that kernel built into a compute pipeline state.
 * <p>
 * Each resource of the module keeps the binding the engine gave it (its index in the program's
 * entry list) and is given a Metal slot of its kind: buffers, textures and samplers are three
 * separate tables in Metal, each numbered from nought.
 */
public final class MetalKernel {

	private static final Pattern ENTRY = Pattern.compile("\\bkernel\\s+void\\s+(\\w+)\\s*\\(");

	/** What sits at one binding of the module, and where Metal reads it. */
	public record Slot(int binding, Kind kind, int index, int sampler) {
	}

	public enum Kind { UNIFORM_BUFFER, STORAGE_BUFFER, SAMPLED_IMAGE, STORAGE_IMAGE }

	private final MemorySegment pipeline;
	private final int localX;
	private final int localY;
	private final int localZ;
	private final List<Slot> slots;

	private MetalKernel(MemorySegment pipeline, int localX, int localY, int localZ, List<Slot> slots) {
		this.pipeline = pipeline;
		this.localX = localX;
		this.localY = localY;
		this.localZ = localZ;
		this.slots = slots;
	}

	public MemorySegment pipeline() {
		return this.pipeline;
	}

	public int localX() {
		return this.localX;
	}

	public int localY() {
		return this.localY;
	}

	public int localZ() {
		return this.localZ;
	}

	public List<Slot> slots() {
		return this.slots;
	}

	public void release() {
		Objc.release(this.pipeline);
	}

	/**
	 * MSL 3.1, in SPIRV-Cross's decimal encoding ({@code major * 10000 + minor * 100}). A hexadecimal
	 * {@code 0x030000} reads as 19.66 there, past 3.2, and brings in 3.2's {@code coherent} buffers and
	 * scoped {@code atomic_thread_fence}, which the device compiler refuses.
	 */
	private static final int MSL_VERSION = 3 * 10000 + 1 * 100;

	/**
	 * Builds the kernel, or answers null with the reason logged.
	 *
	 * @param device the Metal device
	 * @param spirv  the module, its bindings already set to their place in the entry list
	 * @param label  what the log calls it
	 */
	public static @Nullable MetalKernel build(MTLDevice device, ByteBuffer spirv, String label) {
		String msl;
		List<Slot> slots = new ArrayList<>();
		int[] local = {1, 1, 1};
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer pointer = stack.mallocPointer(1);
			check(Spvc.spvc_context_create(pointer), "context");
			long context = pointer.get(0);
			try {
				check(Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(), spirv.remaining() / 4,
						pointer), "parse");
				long ir = pointer.get(0);
				check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir,
						Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer), "compiler");
				long compiler = pointer.get(0);

				check(Spvc.spvc_compiler_create_compiler_options(compiler, pointer), "options");
				long options = pointer.get(0);
				Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM,
						Spvc.SPVC_MSL_PLATFORM_MACOS);
				Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION,
						MSL_VERSION);
				check(Spvc.spvc_compiler_install_compiler_options(compiler, options), "install");

				int model = Spvc.spvc_compiler_get_execution_model(compiler);
				for (int axis = 0; axis < 3; axis++) {
					local[axis] = Math.max(1, Spvc.spvc_compiler_get_execution_mode_argument_by_index(
							compiler, Spv.SpvExecutionModeLocalSize, axis));
				}

				check(Spvc.spvc_compiler_create_shader_resources(compiler, pointer), "resources");
				long resources = pointer.get(0);
				int[] counters = new int[3];
				map(stack, compiler, resources, model, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
						Kind.UNIFORM_BUFFER, counters, slots);
				map(stack, compiler, resources, model, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER,
						Kind.STORAGE_BUFFER, counters, slots);
				map(stack, compiler, resources, model, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
						Kind.SAMPLED_IMAGE, counters, slots);
				map(stack, compiler, resources, model, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE,
						Kind.STORAGE_IMAGE, counters, slots);

				check(Spvc.spvc_compiler_compile(compiler, pointer), "compile");
				msl = MemoryUtil.memUTF8(pointer.get(0));
			} finally {
				Spvc.spvc_context_destroy(context);
			}
		} catch (IllegalStateException e) {
			Leadlight.logger().warn("Metal compute {}: SPIRV-Cross refused it: {}", label, e.getMessage());
			return null;
		}

		Matcher entry = ENTRY.matcher(msl);
		String name = entry.find() ? entry.group(1) : "main0";
		MemorySegment function = device.newFunction(msl, name);
		if (Objc.nil(function)) {
			Leadlight.logger().warn("Metal compute {}: the MSL did not compile (Metallum logged why)", label);
			return null;
		}

		MemorySegment pipeline;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment errorOut = arena.allocate(ADDRESS);
			errorOut.set(ADDRESS, 0, MemorySegment.NULL);
			pipeline = Objc.ptr(device.handle(), "newComputePipelineStateWithFunction:error:",
					function, errorOut);
			if (Objc.nil(pipeline)) {
				Leadlight.logger().warn("Metal compute {}: no pipeline: {}", label, Objc.error(errorOut));
				return null;
			}
		}

		return new MetalKernel(pipeline, local[0], local[1], local[2], List.copyOf(slots));
	}

	private static void map(MemoryStack stack, long compiler, long resources, int model, int type,
			Kind kind, int[] counters, List<Slot> slots) {
		PointerBuffer list = stack.mallocPointer(1);
		PointerBuffer count = stack.mallocPointer(1);
		check(Spvc.spvc_resources_get_resource_list_for_type(resources, type, list, count), "list");
		int found = (int) count.get(0);
		if (found == 0) {
			return;
		}

		SpvcReflectedResource.Buffer reflected = SpvcReflectedResource.create(list.get(0), found);
		for (int i = 0; i < found; i++) {
			int id = reflected.get(i).id();
			int binding = Spvc.spvc_compiler_get_decoration(compiler, id, Spv.SpvDecorationBinding);
			int set = Spvc.spvc_compiler_get_decoration(compiler, id, Spv.SpvDecorationDescriptorSet);
			boolean buffer = kind == Kind.UNIFORM_BUFFER || kind == Kind.STORAGE_BUFFER;
			int index = buffer ? counters[0]++ : counters[1]++;
			int sampler = kind == Kind.SAMPLED_IMAGE ? counters[2]++ : -1;

			SpvcMslResourceBinding mapping = SpvcMslResourceBinding.malloc(stack);
			Spvc.spvc_msl_resource_binding_init(mapping);
			mapping.stage(model).desc_set(set).binding(binding)
					.msl_buffer(buffer ? index : 0)
					.msl_texture(buffer ? 0 : index)
					.msl_sampler(Math.max(sampler, 0));
			check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, mapping), "binding");
			slots.add(new Slot(binding, kind, index, sampler));
		}
	}

	private static void check(int result, String step) {
		if (result != Spvc.SPVC_SUCCESS) {
			throw new IllegalStateException(step + " (" + result + ")");
		}
	}
}
