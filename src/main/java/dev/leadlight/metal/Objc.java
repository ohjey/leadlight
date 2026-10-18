package dev.leadlight.metal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The few Objective-C messages the engine sends to Metal itself, for what Metallum's wrappers do not
 * cover: compute pipelines and encoders, mip chains and comparison samplers. Each is a typed call
 * through {@code objc_msgSend}; nothing here keeps state but the selector and handle caches.
 */
public final class Objc {

	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup RUNTIME =
			SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
	private static final MemorySegment MSG_SEND = RUNTIME.find("objc_msgSend").orElseThrow();
	private static final MethodHandle SEL_REGISTER = LINKER.downcallHandle(
			RUNTIME.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS));
	private static final MethodHandle GET_CLASS = LINKER.downcallHandle(
			RUNTIME.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS));

	/** Metal's {@code MTLSize}, three {@code NSUInteger}s passed by value. */
	public static final StructLayout MTL_SIZE = MemoryLayout.structLayout(JAVA_LONG.withName("width"),
			JAVA_LONG.withName("height"), JAVA_LONG.withName("depth"));

	private static final Map<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();
	private static final Map<FunctionDescriptor, MethodHandle> HANDLES = new ConcurrentHashMap<>();

	private Objc() {
	}

	public static MemorySegment sel(String name) {
		return SELECTORS.computeIfAbsent(name, key -> {
			try (Arena arena = Arena.ofConfined()) {
				return (MemorySegment) SEL_REGISTER.invokeExact(arena.allocateFrom(key));
			} catch (Throwable e) {
				throw new IllegalStateException("sel_registerName " + key, e);
			}
		});
	}

	public static MemorySegment cls(String name) {
		try (Arena arena = Arena.ofConfined()) {
			return (MemorySegment) GET_CLASS.invokeExact(arena.allocateFrom(name));
		} catch (Throwable e) {
			throw new IllegalStateException("objc_getClass " + name, e);
		}
	}

	/** A handle for {@code objc_msgSend} with this signature, receiver and selector first. */
	public static MethodHandle handle(MemoryLayout result, MemoryLayout... args) {
		MemoryLayout[] full = new MemoryLayout[args.length + 2];
		full[0] = ADDRESS;
		full[1] = ADDRESS;
		System.arraycopy(args, 0, full, 2, args.length);
		FunctionDescriptor descriptor = result == null
				? FunctionDescriptor.ofVoid(full)
				: FunctionDescriptor.of(result, full);
		return HANDLES.computeIfAbsent(descriptor, d -> LINKER.downcallHandle(MSG_SEND, d));
	}

	public static MemorySegment ptr(MemorySegment self, String selector) {
		try {
			return (MemorySegment) handle(ADDRESS).invokeExact(self, sel(selector));
		} catch (Throwable e) {
			throw fail(selector, e);
		}
	}

	public static MemorySegment ptr(MemorySegment self, String selector, MemorySegment a) {
		try {
			return (MemorySegment) handle(ADDRESS, ADDRESS).invokeExact(self, sel(selector), a);
		} catch (Throwable e) {
			throw fail(selector, e);
		}
	}

	public static MemorySegment ptr(MemorySegment self, String selector, MemorySegment a,
			MemorySegment b) {
		try {
			return (MemorySegment) handle(ADDRESS, ADDRESS, ADDRESS).invokeExact(self, sel(selector), a, b);
		} catch (Throwable e) {
			throw fail(selector, e);
		}
	}

	public static void send(MemorySegment self, String selector) {
		try {
			handle(null).invokeExact(self, sel(selector));
		} catch (Throwable e) {
			throw fail(selector, e);
		}
	}

	public static void send(MemorySegment self, String selector, MemorySegment a) {
		try {
			handle(null, ADDRESS).invokeExact(self, sel(selector), a);
		} catch (Throwable e) {
			throw fail(selector, e);
		}
	}

	public static void send(MemorySegment self, String selector, long a) {
		try {
			handle(null, JAVA_LONG).invokeExact(self, sel(selector), a);
		} catch (Throwable e) {
			throw fail(selector, e);
		}
	}

	public static void send(MemorySegment self, String selector, MemorySegment a, long b) {
		try {
			handle(null, ADDRESS, JAVA_LONG).invokeExact(self, sel(selector), a, b);
		} catch (Throwable e) {
			throw fail(selector, e);
		}
	}

	public static void send(MemorySegment self, String selector, MemorySegment a, long b, long c) {
		try {
			handle(null, ADDRESS, JAVA_LONG, JAVA_LONG).invokeExact(self, sel(selector), a, b, c);
		} catch (Throwable e) {
			throw fail(selector, e);
		}
	}

	public static void send(MemorySegment self, String selector, boolean a) {
		try {
			handle(null, ValueLayout.JAVA_BOOLEAN).invokeExact(self, sel(selector), a);
		} catch (Throwable e) {
			throw fail(selector, e);
		}
	}

	/** {@code dispatchThreadgroups:threadsPerThreadgroup:}. */
	public static void dispatch(MemorySegment encoder, long gx, long gy, long gz, long tx, long ty,
			long tz) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment groups = arena.allocate(MTL_SIZE);
			groups.set(JAVA_LONG, 0, gx);
			groups.set(JAVA_LONG, 8, gy);
			groups.set(JAVA_LONG, 16, gz);
			MemorySegment threads = arena.allocate(MTL_SIZE);
			threads.set(JAVA_LONG, 0, tx);
			threads.set(JAVA_LONG, 8, ty);
			threads.set(JAVA_LONG, 16, tz);
			handle(null, MTL_SIZE, MTL_SIZE).invokeExact(encoder,
					sel("dispatchThreadgroups:threadsPerThreadgroup:"), groups, threads);
		} catch (Throwable e) {
			throw fail("dispatchThreadgroups", e);
		}
	}

	public static boolean nil(MemorySegment handle) {
		return handle == null || handle.address() == 0L;
	}

	public static void release(MemorySegment handle) {
		if (!nil(handle)) {
			send(handle, "release");
		}
	}

	/** The UTF-8 text of an {@code NSString}, or empty for nil. */
	public static String string(MemorySegment nsString) {
		if (nil(nsString)) {
			return "";
		}
		MemorySegment utf8 = ptr(nsString, "UTF8String");
		return nil(utf8) ? "" : utf8.reinterpret(Long.MAX_VALUE).getString(0);
	}

	/** The description of the {@code NSError} an out-pointer holds, or empty. */
	public static String error(MemorySegment errorOut) {
		MemorySegment error = errorOut.get(ADDRESS, 0);
		return nil(error) ? "" : string(ptr(error, "localizedDescription"));
	}

	private static RuntimeException fail(String selector, Throwable e) {
		return e instanceof RuntimeException runtime ? runtime
				: new IllegalStateException("objc_msgSend " + selector, e);
	}
}
