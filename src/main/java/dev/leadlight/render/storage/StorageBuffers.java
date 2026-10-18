package dev.leadlight.render.storage;

import dev.leadlight.mixin.access.CommandEncoderAccessor;
import dev.leadlight.mixin.access.GpuDeviceAccessor;
import dev.leadlight.pack.model.BufferObject;
import dev.leadlight.pack.texture.CustomStorage;
import dev.leadlight.Leadlight;
import dev.leadlight.metal.MetalEncoderAccess;
import dev.leadlight.metal.MetalHost;
import dev.leadlight.metal.Objc;
import com.metallum.render.LeadlightMetalBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shader storage buffers a pack declared with {@code bufferObject.N}, allocated on the Vulkan
 * device.
 * <p>
 * The Java buffer facade has no storage bit, so these go through VMA the way the custom images do:
 * a dummy {@code USAGE_UNIFORM} slice satisfies {@code setUniform}, and mixins swap the real
 * {@code VkBuffer} and {@code VK_DESCRIPTOR_TYPE_STORAGE_BUFFER} while pushing descriptors.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris ShaderStorageBuffer, LGPL-3.0</a>
 */
public final class StorageBuffers implements AutoCloseable {

	private static volatile StorageBuffers current = none();

	private final BufferObject.Reading declared;
	private final List<Allocated> allocated = new ArrayList<>();

	/** Each allocation under the index the pack declared it at, rebuilt with {@link #allocated}. */
	private Map<Integer, Bound> bindings = Map.of();
	private GpuBuffer dummy;
	private int lastWidth;
	private int lastHeight;

	public StorageBuffers(BufferObject.Reading declared) {
		this.declared = declared;
	}

	static StorageBuffers none() {
		return new StorageBuffers(BufferObject.Reading.empty());
	}

	/** Whether this name is a storage buffer this engine serves, allocated or about to be. */
	public static boolean named(String name) {
		return CustomStorage.named(name);
	}

	/**
	 * The VMA buffer currently allocated for this GLSL name. Mixins look it up while pushing
	 * descriptors.
	 */
	public static Bound bound(String name) {
		return current.lookup(name);
	}

	private Bound lookup(String name) {
		if (this.bindings.isEmpty()) {
			return null;
		}

		int index = CustomStorage.indexOf(name);
		return index < 0 ? null : this.bindings.get(index);
	}

	private void rebind() {
		Map<Integer, Bound> bound = new HashMap<>();
		for (Allocated buffer : this.allocated) {
			bound.putIfAbsent(buffer.declared.index(), new Bound(buffer.buffer, buffer.bytes));
		}

		this.bindings = Map.copyOf(bound);
	}

	/**
	 * One bound buffer. {@code range} is the whole allocation: Complementary indexes
	 * {@code blockDataSSBO.data[face]} across hundreds of megabytes, and a 16-byte dummy would OOB.
	 */
	public record Bound(long buffer, long range) {
	}

	void install() {
		current = this;
	}

	/**
	 * Allocates every absolute buffer once, and rebuilds the relative ones when the screen moves.
	 * A failure is thrown rather than skipped: a tiny stand-in would let the pack compile and then
	 * hang the GPU on the first out-of-range write.
	 */
	public void ensure(int screenWidth, int screenHeight) {
		install();
		if (this.declared.buffers().isEmpty()) {
			return;
		}

		boolean first = this.allocated.isEmpty();
		boolean resized = screenWidth != this.lastWidth || screenHeight != this.lastHeight;
		if (!first && !resized) {
			return;
		}

		MemorySegment metal = metal();
		GpuDevice device = RenderSystem.tryGetDevice();
		if (metal == null || device == null) {
			return;
		}

		ensurePlaceholder(device);
		// The map follows the list whatever happens below: an allocation that throws halfway
		// through a resize has already destroyed the relative buffers and dropped them from the
		// list, and a map left standing would hand a destroyed handle to the next descriptor push.
		try {
			allocate(metal, first, resized, screenWidth, screenHeight);
		} finally {
			rebind();
		}

		this.lastWidth = screenWidth;
		this.lastHeight = screenHeight;
		zero(device);
	}

	private void allocate(MemorySegment metal, boolean first, boolean resized, int screenWidth,
			int screenHeight) {
		if (first) {
			for (BufferObject buffer : this.declared.buffers()) {
				if (buffer.relative()) {
					continue;
				}

				this.allocated.add(Allocated.create(metal, buffer, aligned(buffer.size())));
				Leadlight.logger().info("storage buffer {}", buffer.describe());
			}
		}

		if (first || resized) {
			List<Allocated> kept = new ArrayList<>();
			for (Allocated buffer : this.allocated) {
				if (buffer.relative) {
					buffer.destroy();
				} else {
					kept.add(buffer);
				}
			}

			this.allocated.clear();
			this.allocated.addAll(kept);
			for (BufferObject buffer : this.declared.buffers()) {
				if (!buffer.relative()) {
					continue;
				}

				long bytes = aligned((long) (screenWidth * buffer.scaleX())
						* (long) (screenHeight * buffer.scaleY())
						* buffer.size());
				this.allocated.add(Allocated.create(metal, buffer, Math.max(bytes, 4L)));
				Leadlight.logger().info("storage buffer {} at {} bytes", buffer.describe(), bytes);
			}
		}
	}

	private void ensurePlaceholder(GpuDevice device) {
		if (this.dummy == null) {
			this.dummy = device.createBuffer(() -> "leadlight ssbo placeholder",
					GpuBuffer.USAGE_UNIFORM, 16);
		}
	}

	/**
	 * Binds a dummy uniform slice for every storage-buffer name this program's layout carries.
	 * Mixins replace the {@code VkBuffer} and the range while the descriptors are pushed.
	 */
	public static void bind(RenderPass pass, List<String> names) {
		if (names.isEmpty()) {
			return;
		}

		GpuBufferSlice slice = current.placeholder();
		if (slice == null) {
			return;
		}

		for (String name : names) {
			pass.setUniform(name, slice);
		}
	}

	private GpuBufferSlice placeholder() {
		return this.dummy == null ? null : this.dummy.slice(0, 16);
	}

	/**
	 * Zeros every buffer {@link #ensure} has just made, whole and in one fill.
	 * <p>
	 * <strong>A pack reads a cell it never wrote and expects zero.</strong> Complementary indexes
	 * {@code blockDataSSBO.data[]} by face rather than by voxel, and its reader tests the cell it
	 * gets rather than a written flag: {@code lib/materials/materialMethods/worldSpaceRef.glsl:69}
	 * drops the reflection when {@code faceData.textureBounds.z < 1e-6}, and that field is only ever
	 * zero because nothing wrote the cell. Its writer reads too, at
	 * {@code lib/voxelization/reflectionVoxelData.glsl:61}, before any store of the frame and at the
	 * previous camera anchor, then mixes what it read back in at 99 percent. Uninitialised memory
	 * passes the first test and is fed to the atlas as a texture coordinate, and the second carries
	 * it forward for a hundred frames. Nothing in the pack clears the buffer at Ultra either: its
	 * {@code clearSSBOs()} touches {@code playerVerticesSSBO} alone and sits under
	 * {@code WORLD_SPACE_PLAYER_REF == 1}, which ships at -1.
	 * <p>
	 * <strong>What Iris does.</strong> It clears the whole allocation the moment it makes it, both
	 * for an absolute buffer ({@code gl/buffer/ShaderStorageBuffer.java:79}) and on every rebuild of
	 * a relative one ({@code :64}), and never again. The same holds here: this runs at the tail of
	 * {@link #ensure}, which makes the absolute buffers once per pack load and the relative ones
	 * again on every resize, and the fill goes on the command buffer there, ahead of everything the
	 * frame that made them goes on to draw.
	 * <p>
	 * <strong>It is one fill and not a spread of them.</strong> The pack's shadow vertex stage
	 * stores into the buffer from the first frame the world is drawn
	 * ({@code lib/voxelization/reflectionVoxelData.glsl:80} and {@code :83}), and a fill carried
	 * over to a later frame would erase what those stores put there. Filling 773 MiB is one
	 * bandwidth-bound write with no per-cell work behind it, and it lands on the frame that
	 * allocates the buffer rather than on one drawing a world.
	 */
	private static final MethodHandle FILL_BUFFER = Objc.handle(null, ADDRESS,
			MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG), JAVA_BYTE);

	private void zero(GpuDevice device) {
		CommandEncoder encoder = device.createCommandEncoder();
		GpuRecording.endPass(encoder);
		Object backend = ((CommandEncoderAccessor) encoder).leadlight$backend();
		if (!(backend instanceof MetalEncoderAccess access)) {
			return;
		}

		MemorySegment blit = null;
		try (Arena arena = Arena.ofConfined()) {
			for (Allocated buffer : this.allocated) {
				if (buffer.zeroed) {
					continue;
				}
				if (blit == null) {
					blit = access.leadlight$blit().handle();
				}
				MemorySegment range = arena.allocate(MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG));
				range.set(JAVA_LONG, 0, 0L);
				range.set(JAVA_LONG, 8, buffer.bytes);
				FILL_BUFFER.invokeExact(blit, Objc.sel("fillBuffer:range:value:"), MemorySegment.ofAddress(buffer.buffer), range,
						(byte) 0);
				buffer.zeroed = true;
				Leadlight.logger().info("storage buffer {} zeroed whole at {} bytes",
						buffer.declared.describe(), buffer.bytes);
			}
		} catch (Throwable e) {
			throw new IllegalStateException("zeroing storage buffers", e);
		} finally {
			if (blit != null) {
				access.leadlight$endEncoder();
			}
		}
	}

	private static long aligned(long size) {
		return Math.max(4L, (size + 3L) & ~3L);
	}

	@Override
	public void close() {
		if (current == this) {
			current = none();
		}

		this.allocated.forEach(Allocated::destroy);

		this.allocated.clear();
		this.bindings = Map.of();
		if (this.dummy != null) {
			this.dummy.close();
			this.dummy = null;
		}

		this.lastWidth = 0;
		this.lastHeight = 0;
	}

	private static MemorySegment metal() {
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null || !MetalHost.isMetal(device)) {
			return null;
		}

		return LeadlightMetalBridge.device(((GpuDeviceAccessor) device).leadlight$backend()).handle();
	}

	private static final class Allocated {
		private final BufferObject declared;
		private final boolean relative;
		private final long bytes;
		/** The address of the Metal buffer, private to the GPU. */
		private long buffer;
		private boolean zeroed;

		private Allocated(BufferObject declared, boolean relative, long bytes, long buffer) {
			this.declared = declared;
			this.relative = relative;
			this.bytes = bytes;
			this.buffer = buffer;
		}

		/** {@code MTLResourceStorageModePrivate}. */
		private static final long PRIVATE = 2L << 4;

		private static Allocated create(MemorySegment metal, BufferObject declared, long bytes) {
			MemorySegment made = Objc.ptr(metal, "newBufferWithLength:options:",
					MemorySegment.ofAddress(bytes), MemorySegment.ofAddress(PRIVATE));
			if (Objc.nil(made)) {
				throw new IllegalStateException("Metal gave no storage buffer " + declared.index()
						+ " of " + bytes + " bytes");
			}
			return new Allocated(declared, declared.relative(), bytes, made.address());
		}

		private void destroy() {
			long buffer = this.buffer;
			this.buffer = 0L;
			if (buffer != 0L) {
				GpuRecording.destroyLater(() -> Objc.release(MemorySegment.ofAddress(buffer)));
			}
		}
	}
}
