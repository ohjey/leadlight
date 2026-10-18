package dev.leadlight.dev;

import dev.leadlight.Leadlight;

/** Development aid: logs the passes and clears of a few frames when the debug script asks. */
public final class FrameTrace {

	private static volatile int frames;
	private static int frame;

	private FrameTrace() {
	}

	public static void start(int count) {
		frames = count;
		frame = 0;
	}

	public static boolean on() {
		return frames > 0;
	}

	public static void line(String text) {
		if (frames > 0) {
			Leadlight.logger().info("FRAME{} {}", frame, text);
		}
	}

	/** Called at every submit, which is the end of a frame. */
	public static void endFrame() {
		if (frames > 0) {
			frames--;
			frame++;
		}
	}
}
