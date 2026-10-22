package dev.leadlight.render.storage;

import com.metallum.render.LeadlightMetalBridge;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.leadlight.Leadlight;
import dev.leadlight.metal.MetalEncoderAccess;
import dev.leadlight.metal.MetalHost;
import dev.leadlight.metal.Objc;
import dev.leadlight.mixin.access.CommandEncoderAccessor;
import dev.leadlight.mixin.access.GpuDeviceAccessor;
import dev.leadlight.pack.model.ImageInformation;
import dev.leadlight.pack.model.PackTexture;
import dev.leadlight.pack.model.TargetFormat;
import dev.leadlight.pack.texture.CustomImageView;
import dev.leadlight.render.GpuFormats;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The storage images a pack declared with {@code image.NAME}, allocated as Metal textures.
 * <p>
 * Vitrail allocates these through Vulkan's allocator because the game's texture facade has no
 * storage usage and no three-dimensional texture; Metallum's facade has neither either, so here they
 * are made through Metal directly, readable, writable and viewable in another format of the same
 * width. The render pass and the compute dispatch put the texture in by name where the program
 * declared the image or the sampler hanging off the same directive.
 * <p>
 * Emptying and moving them is done with Metal blit commands in Metallum's own encoder sequence, so
 * the frame's fence orders them against the passes around them.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris GlImage, LGPL-3.0</a>
 */
public final class StorageImages implements AutoCloseable {

	private static volatile StorageImages current = none();

	/** {@code MTLTextureType}. */
	private static final long TYPE_1D = 0L;
	private static final long TYPE_2D = 2L;
	private static final long TYPE_3D = 7L;

	/** {@code MTLTextureUsage}: read, write, pixel format view, and atomics where asked. */
	private static final long USAGE = 1L | 2L | 16L;
	private static final long USAGE_ATOMIC = 32L;

	/** {@code MTLStorageModePrivate}. */
	private static final long PRIVATE = 2L;

	private static final MethodHandle COPY_BUFFER_TO_TEXTURE = Objc.handle(null, ADDRESS, JAVA_LONG,
			JAVA_LONG, JAVA_LONG, Objc.MTL_SIZE, ADDRESS, JAVA_LONG, JAVA_LONG, Objc.MTL_SIZE);
	private static final MethodHandle COPY_TEXTURE_TO_TEXTURE = Objc.handle(null, ADDRESS, JAVA_LONG,
			JAVA_LONG, Objc.MTL_SIZE, Objc.MTL_SIZE, ADDRESS, JAVA_LONG, JAVA_LONG, Objc.MTL_SIZE);
	private static final MethodHandle FILL_BUFFER = Objc.handle(null, ADDRESS,
			java.lang.foreign.MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG), JAVA_BYTE);

	/** A buffer of zeroes, one slice of the largest image emptied so far, for the clears. */
	private static MemorySegment zeroes = MemorySegment.NULL;
	private static long zeroBytes;

	private final ImageInformation.Reading declared;
	private final List<Allocated> allocated = new ArrayList<>();
	private volatile Map<String, Bound> bindings = Map.of();
	private volatile Map<String, Allocated> imagesByName = Map.of();
	private final Map<String, Bound> typedBindings = new ConcurrentHashMap<>();
	private int lastWidth;
	private int lastHeight;
	private boolean refusedForGood;

	public StorageImages(ImageInformation.Reading declared) {
		this.declared = declared;
	}

	static StorageImages none() {
		return new StorageImages(ImageInformation.Reading.empty());
	}

	/**
	 * The image currently allocated for this name, whether the pack wrote it as the image uniform
	 * or as the sampler on the same {@code image.} line.
	 */
	public static @Nullable Bound bound(String name) {
		return current.lookup(name);
	}

	public static boolean storageBinding(String name) {
		Bound bound = bound(name);
		return bound != null && bound.storage();
	}

	/** Every format view Apple GPUs make of a custom image is one they sample and store. */
	public static List<String> unusableViews(Collection<String> names) {
		return new ArrayList<>();
	}

	void install() {
		current = this;
	}

	private @Nullable Bound lookup(String name) {
		Bound ordinary = this.bindings.get(name);
		if (ordinary != null) {
			return ordinary;
		}
		Bound cached = this.typedBindings.get(name);
		if (cached != null) {
			return cached;
		}
		var request = CustomImageView.parse(name);
		if (request.isEmpty()) {
			return null;
		}
		Allocated image = this.imagesByName.get(request.get().original());
		if (image == null) {
			return null;
		}
		return this.typedBindings.computeIfAbsent(name, _ -> new Bound(
				image.typedView(request.get().format()),
				image.declared.name().equals(request.get().original()), request.get().format().integer()));
	}

	private void rebind() {
		Map<String, Bound> bound = new HashMap<>();
		Map<String, Allocated> named = new HashMap<>();
		for (Allocated image : this.allocated) {
			boolean integer = image.declared.internalFormat().used().integer();
			bound.putIfAbsent(image.declared.name(), new Bound(image.texture.address(), true, integer));
			image.declared.sampler().ifPresent(sampler ->
					bound.putIfAbsent(sampler, new Bound(image.texture.address(), false, integer)));
			named.putIfAbsent(image.declared.name(), image);
			image.declared.sampler().ifPresent(sampler -> named.putIfAbsent(sampler, image));
		}

		this.bindings = Map.copyOf(bound);
		this.imagesByName = Map.copyOf(named);
		this.typedBindings.clear();
	}

	/**
	 * One bound image: {@code view} is the address of its Metal texture. {@code storage} is the
	 * image uniform ({@code voxel_img}); a sampler name hanging off the same directive is sampled.
	 */
	public record Bound(long view, boolean storage, boolean integer) {

		public MemorySegment texture() {
			return MemorySegment.ofAddress(this.view);
		}
	}

	public boolean refusedForGood() {
		return this.refusedForGood;
	}

	/** Allocates every absolute image once, and rebuilds the relative ones when the screen moves. */
	public void ensure(int screenWidth, int screenHeight) {
		install();
		if (this.declared.images().isEmpty()) {
			return;
		}

		boolean first = this.allocated.isEmpty();
		boolean resized = screenWidth != this.lastWidth || screenHeight != this.lastHeight;
		if (!first && !resized) {
			return;
		}

		MemorySegment device = device();
		if (device == null) {
			return;
		}

		List<Allocated> born = new ArrayList<>();
		try {
			allocate(device, first, resized, screenWidth, screenHeight, born);
		} catch (RuntimeException e) {
			this.allocated.forEach(Allocated::destroy);
			this.allocated.clear();
			rebind();
			throw e;
		}

		this.lastWidth = screenWidth;
		this.lastHeight = screenHeight;
		rebind();
		// A volume is created holding whatever the memory held, and one a pack carries forward
		// (Photon's light_img_a and light_img_b, read and written in turn) would carry that.
		GpuDevice game = RenderSystem.tryGetDevice();
		if (game != null && !born.isEmpty()) {
			emptyAll(game.createCommandEncoder(), born);
		}
	}

	private void allocate(MemorySegment device, boolean first, boolean resized, int screenWidth,
			int screenHeight, List<Allocated> born) {
		if (first) {
			for (ImageInformation image : this.declared.images()) {
				if (image.relative()) {
					continue;
				}

				boolean movable = movable(image);
				try {
					Allocated made = Allocated.create(device, image, image.width(), image.height(),
							Math.max(image.depth(), 1), movable);
					this.allocated.add(made);
					born.add(made);
					Leadlight.logger().info("storage image {} as a Metal texture{}", image.describe(),
							movable ? ", moved onto each frame's camera block" : "");
				} catch (RuntimeException e) {
					this.refusedForGood = true;
					throw refused(image, e);
				}
			}
		}

		if (first || resized) {
			List<Allocated> kept = new ArrayList<>();
			for (Allocated image : this.allocated) {
				if (image.relative) {
					image.destroy();
				} else {
					kept.add(image);
				}
			}

			this.allocated.clear();
			this.allocated.addAll(kept);
			for (ImageInformation image : this.declared.images()) {
				if (!image.relative()) {
					continue;
				}

				int width = Math.max(1, (int) (screenWidth * image.relativeWidth()));
				int height = Math.max(1, (int) (screenHeight * image.relativeHeight()));
				try {
					Allocated made = Allocated.create(device, image, width, height, 1, false);
					this.allocated.add(made);
					born.add(made);
					Leadlight.logger().info("storage image {} at {}x{}", image.describe(), width, height);
				} catch (RuntimeException e) {
					throw refused(image, e);
				}
			}
		}
	}

	private static IllegalStateException refused(ImageInformation image, RuntimeException cause) {
		return new IllegalStateException("storage image " + image.describe()
				+ " could not be allocated, and a program declaring it cannot be drawn without it",
				cause);
	}

	/**
	 * Empties every image the pack asked to clear, at the top of the shadow stage, before geometry
	 * writes, matching Iris clearing custom images before the shadow map is drawn.
	 */
	public void clearMarked(CommandEncoder encoder) {
		List<Allocated> marked = new ArrayList<>();
		for (Allocated image : this.allocated) {
			if (image.declared.clear()) {
				marked.add(image);
			}
		}
		emptyAll(encoder, marked);
	}

	private static void emptyAll(CommandEncoder encoder, List<Allocated> images) {
		if (images.isEmpty()) {
			return;
		}

		GpuRecording.endPass(encoder);
		Object backend = ((CommandEncoderAccessor) encoder).leadlight$backend();
		if (!(backend instanceof MetalEncoderAccess access)) {
			return;
		}

		MemorySegment blit = access.leadlight$blit();
		try {
			for (Allocated image : images) {
				empty(blit, image);
			}
		} finally {
			access.leadlight$endEncoder();
		}
	}

	/** Zeroes one image, a slice at a time out of one buffer of zeroes. */
	private static void empty(MemorySegment blit, Allocated image) {
		long row = (long) image.width * image.bytesPerPixel;
		long slice = row * image.height;
		MemorySegment source = zeroes(slice);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment size = size(arena, image.width, image.height, 1);
			for (int z = 0; z < image.depth; z++) {
				MemorySegment origin = size(arena, 0, 0, z);
				COPY_BUFFER_TO_TEXTURE.invokeExact(blit, Objc.sel(
						"copyFromBuffer:sourceOffset:sourceBytesPerRow:sourceBytesPerImage:sourceSize:"
								+ "toTexture:destinationSlice:destinationLevel:destinationOrigin:"),
						source, 0L, row, slice, size, image.texture, 0L, 0L, origin);
			}
		} catch (Throwable e) {
			throw new IllegalStateException("emptying storage image " + image.declared.name(), e);
		}
	}

	/** The buffer of zeroes, grown to at least this size. */
	private static MemorySegment zeroes(long bytes) {
		if (bytes <= zeroBytes && !Objc.nil(zeroes)) {
			return zeroes;
		}

		MemorySegment device = device();
		long size = Math.max(bytes, 1L << 20);
		MemorySegment fresh = Objc.ptr(device, "newBufferWithLength:options:", MemorySegment.ofAddress(size),
				MemorySegment.ofAddress(0L));
		// A shared buffer starts zeroed by the allocator; filled anyway so no reading depends on it.
		MemorySegment contents = Objc.ptr(fresh, "contents");
		contents.reinterpret(size).fill((byte) 0);
		if (!Objc.nil(zeroes)) {
			MemorySegment old = zeroes;
			GpuDevice game = RenderSystem.tryGetDevice();
			if (game != null) {
				LeadlightMetalBridge.releaseLater(((GpuDeviceAccessor) game).leadlight$backend(), old);
			}
		}
		zeroes = fresh;
		zeroBytes = size;
		return fresh;
	}

	private static MemorySegment size(Arena arena, long x, long y, long z) {
		MemorySegment value = arena.allocate(Objc.MTL_SIZE);
		value.set(JAVA_LONG, 0, x);
		value.set(JAVA_LONG, 8, y);
		value.set(JAVA_LONG, 16, z);
		return value;
	}

	/**
	 * Whether a volume may be moved by a count of blocks: one the pack clears whose three extents
	 * match one it does not, the identity half of a floodfill grid. See Vitrail's own version of this
	 * class for the whole argument.
	 */
	private boolean movable(ImageInformation image) {
		if (!image.clear() || image.relative() || image.depth() <= 1) {
			return false;
		}

		for (ImageInformation other : this.declared.images()) {
			if (!other.clear() && !other.relative() && other.width() == image.width()
					&& other.height() == image.height() && other.depth() == image.depth()) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Moves every movable volume onto the anchor of the frame about to read it, by the whole blocks
	 * the camera crossed since it was filled, through its scratch, since a copy onto itself would
	 * overlap.
	 */
	public void reanchor(CommandEncoder encoder, int dx, int dy, int dz) {
		if (dx == 0 && dy == 0 && dz == 0) {
			return;
		}

		GpuRecording.endPass(encoder);
		Object backend = ((CommandEncoderAccessor) encoder).leadlight$backend();
		if (!(backend instanceof MetalEncoderAccess access)) {
			return;
		}

		MemorySegment blit = access.leadlight$blit();
		try {
			for (Allocated image : this.allocated) {
				if (Objc.nil(image.scratch)) {
					continue;
				}

				if (Math.abs(dx) >= image.width || Math.abs(dy) >= image.height
						|| Math.abs(dz) >= image.depth) {
					empty(blit, image);
					continue;
				}

				move(blit, image, dx, dy, dz);
			}
		} finally {
			access.leadlight$endEncoder();
		}
	}

	private static void move(MemorySegment blit, Allocated image, int dx, int dy, int dz) {
		int fromX = Math.max(dx, 0);
		int fromY = Math.max(dy, 0);
		int fromZ = Math.max(dz, 0);
		int spanX = image.width - Math.abs(dx);
		int spanY = image.height - Math.abs(dy);
		int spanZ = image.depth - Math.abs(dz);
		MemorySegment selector = Objc.sel("copyFromTexture:sourceSlice:sourceLevel:sourceOrigin:"
				+ "sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:");
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment from = size(arena, fromX, fromY, fromZ);
			MemorySegment span = size(arena, spanX, spanY, spanZ);
			MemorySegment to = size(arena, Math.max(-dx, 0), Math.max(-dy, 0), Math.max(-dz, 0));
			COPY_TEXTURE_TO_TEXTURE.invokeExact(blit, selector, image.texture, 0L, 0L, from, span,
					image.scratch, 0L, 0L, from);
			COPY_TEXTURE_TO_TEXTURE.invokeExact(blit, selector, image.scratch, 0L, 0L, from, span,
					image.texture, 0L, 0L, to);
		} catch (Throwable e) {
			throw new IllegalStateException("moving storage image " + image.declared.name(), e);
		}
	}

	int count() {
		return this.allocated.size();
	}

	@Override
	public void close() {
		if (current == this) {
			current = none();
		}

		this.allocated.forEach(Allocated::destroy);
		this.allocated.clear();
		this.bindings = Map.of();
		this.imagesByName = Map.of();
		this.typedBindings.clear();
		this.lastWidth = 0;
		this.lastHeight = 0;
	}

	private static @Nullable MemorySegment device() {
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null || !MetalHost.isMetal(device)) {
			return null;
		}

		return LeadlightMetalBridge.deviceHandle(((GpuDeviceAccessor) device).leadlight$backend());
	}

	private static long pixelFormat(TargetFormat format) {
		return LeadlightMetalBridge.pixelFormat(GpuFormats.of(format));
	}

	private static final class Allocated {

		private final ImageInformation declared;
		private final boolean relative;
		private final int width;
		private final int height;
		private final int depth;
		private final int bytesPerPixel;
		private MemorySegment texture;
		private MemorySegment scratch = MemorySegment.NULL;
		private final Map<TargetFormat, MemorySegment> views = new HashMap<>();

		private Allocated(ImageInformation declared, int width, int height, int depth,
				MemorySegment texture) {
			this.declared = declared;
			this.relative = declared.relative();
			this.width = width;
			this.height = height;
			this.depth = depth;
			this.bytesPerPixel = declared.internalFormat().used().bytesPerPixel();
			this.texture = texture;
		}

		private static Allocated create(MemorySegment device, ImageInformation declared, int width,
				int height, int depth, boolean movable) {
			int w = Math.max(width, 1);
			int h = Math.max(height, 1);
			int d = Math.max(depth, 1);
			MemorySegment texture = texture(device, declared, w, h, d);
			Allocated made = new Allocated(declared, w, h, d, texture);
			if (movable) {
				try {
					made.scratch = texture(device, declared, w, h, d);
				} catch (RuntimeException e) {
					Leadlight.logger().warn("storage image {} keeps a frame of lag, its scratch could "
							+ "not be allocated: {}", declared.name(), e.toString());
				}
			}
			return made;
		}

		private static MemorySegment texture(MemorySegment device, ImageInformation declared, int w,
				int h, int d) {
			TargetFormat format = declared.internalFormat().used();
			long type = switch (declared.shape()) {
				case TEXTURE_1D -> TYPE_1D;
				case TEXTURE_3D -> TYPE_3D;
				case TEXTURE_2D, TEXTURE_RECTANGLE -> TYPE_2D;
			};
			MemorySegment descriptor = Objc.ptr(Objc.ptr(Objc.cls("MTLTextureDescriptor"), "alloc"), "init");
			try {
				Objc.send(descriptor, "setTextureType:", type);
				Objc.send(descriptor, "setPixelFormat:", pixelFormat(format));
				Objc.send(descriptor, "setWidth:", (long) w);
				Objc.send(descriptor, "setHeight:", type == TYPE_1D ? 1L : (long) h);
				Objc.send(descriptor, "setDepth:", type == TYPE_3D ? (long) d : 1L);
				Objc.send(descriptor, "setMipmapLevelCount:", 1L);
				boolean atomic = format == TargetFormat.R32_UINT || format == TargetFormat.R32_SINT;
				Objc.send(descriptor, "setUsage:", USAGE | (atomic ? USAGE_ATOMIC : 0L));
				Objc.send(descriptor, "setStorageMode:", PRIVATE);
				MemorySegment texture = Objc.ptr(device, "newTextureWithDescriptor:", descriptor);
				if (Objc.nil(texture)) {
					throw new IllegalStateException("Metal gave no texture for " + declared.describe());
				}
				return texture;
			} finally {
				Objc.release(descriptor);
			}
		}

		private synchronized long typedView(TargetFormat format) {
			if (format == this.declared.internalFormat().used()) {
				return this.texture.address();
			}

			MemorySegment view = this.views.computeIfAbsent(format, wanted -> {
				MemorySegment made = Objc.ptr(this.texture, "newTextureViewWithPixelFormat:",
						MemorySegment.ofAddress(pixelFormat(wanted)));
				Leadlight.logger().info("Custom image {} uses a {} view of {} memory", this.declared.name(),
						wanted, this.declared.internalFormat().used());
				return made;
			});
			return Objc.nil(view) ? this.texture.address() : view.address();
		}

		private synchronized void destroy() {
			List<MemorySegment> gone = new ArrayList<>(this.views.values());
			gone.add(this.texture);
			gone.add(this.scratch);
			this.views.clear();
			this.texture = MemorySegment.NULL;
			this.scratch = MemorySegment.NULL;
			GpuDevice device = RenderSystem.tryGetDevice();
			if (device == null) {
				return;
			}
			Object backend = ((GpuDeviceAccessor) device).leadlight$backend();
			for (MemorySegment handle : gone) {
				if (!Objc.nil(handle)) {
					LeadlightMetalBridge.releaseLater(backend, handle);
				}
			}
		}
	}
}
