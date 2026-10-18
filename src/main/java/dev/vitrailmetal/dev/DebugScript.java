package dev.vitrailmetal.dev;

import dev.vitrailmetal.Vitrail;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Development aid, off unless the game is started with {@code -Dvitrailmetal.debugScript=<file>}:
 * runs a list of steps once a world is on screen, so a picture can be checked without anyone at the
 * keyboard. One step per line:
 * <pre>
 * wait 500           milliseconds to let pass
 * cmd time set 6000  a command, run by the integrated server
 * shot noon          saves the main render target as noon.png next to the script
 * pos                logs where the player is
 * view back          third person from behind (front, or first to go back)
 * hud off            hides the HUD (hud on shows it again)
 * fps                logs the frame rate the game counted over the last second
 * screen packs       opens the pack screen, as the I key does (screen close closes it)
 * turn 3             turns the camera by this many degrees every tick (turn 0 stops)
 * quit               closes the game
 * </pre>
 */
public final class DebugScript {

	private static final @Nullable Path FILE = file();

	private static @Nullable ArrayDeque<String> steps;

	/** Degrees the camera turns each tick, set by the turn step. */
	private static float turning;

	/** When the screen is next logged while no world is open. */
	private static long screenLogAt;

	/** The next step runs once the clock passes this. */
	private static long resumeAt;

	private DebugScript() {
	}

	public static void tick() {
		Path file = FILE;
		if (file == null) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			long now = System.currentTimeMillis();
			if (now >= screenLogAt) {
				screenLogAt = now + 5000;
				Object screen = minecraft.gui.screen();
				Object overlay = minecraft.gui.overlay();
				Vitrail.logger().info("debug script: no world yet, screen {}, overlay {}",
						screen == null ? "none" : screen.getClass().getName(),
						overlay == null ? "none" : overlay.getClass().getName());
			}

			return;
		}

		if (steps == null) {
			if (minecraft.gui.screen() != null) {
				return;
			}

			try {
				steps = new ArrayDeque<>(Files.readAllLines(file));
				pause(1500);
			} catch (IOException e) {
				Vitrail.logger().error("debug script: could not read {}", file, e);
				steps = new ArrayDeque<>(List.of());
			}
		}

		ArrayDeque<String> pending = steps;
		while (System.nanoTime() >= resumeAt && !pending.isEmpty()) {
			run(minecraft, file, pending.poll().trim());
		}
	}

	private static void pause(long milliseconds) {
		resumeAt = System.nanoTime() + milliseconds * 1_000_000L;
	}

	private static void run(Minecraft minecraft, Path file, String step) {
		if (step.isEmpty() || step.startsWith("#")) {
			return;
		}

		int space = step.indexOf(' ');
		String verb = space < 0 ? step : step.substring(0, space);
		String rest = space < 0 ? "" : step.substring(space + 1).trim();
		Vitrail.logger().info("debug script: {}", step);
		switch (verb) {
			case "wait" -> pause(Long.parseLong(rest));
			case "cmd" -> {
				IntegratedServer server = minecraft.getSingleplayerServer();
				if (server != null) {
					server.execute(() -> server.getCommands().performPrefixedCommand(
							server.createCommandSourceStack().withSuppressedOutput(), rest));
				}
				pause(300);
			}
			case "shot" -> {
				Path target = file.resolveSibling(rest + ".png");
				Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(),
						image -> Util.ioPool().execute(() -> {
							try (image) {
								image.writeToFile(target);
								Vitrail.logger().info("debug script: wrote {}", target);
							} catch (IOException e) {
								Vitrail.logger().error("debug script: could not write {}", target, e);
							}
						}));
				pause(100);
			}
			case "pos" -> Vitrail.logger().info("debug script: player at {} looking {} / {}",
					minecraft.player.position(), minecraft.player.getYRot(), minecraft.player.getXRot());
			case "view" -> minecraft.options.setCameraType(rest.equals("back")
					? CameraType.THIRD_PERSON_BACK
					: rest.equals("front") ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON);
			case "hud" -> {
				if (minecraft.gui.hud.isHidden() != rest.equals("off")) {
					minecraft.gui.hud.toggle();
				}
			}
			case "screen" -> minecraft.gui.setScreen(rest.equals("packs")
					? dev.vitrailmetal.screen.PackScreens.open(minecraft.gui.screen())
					: null);
			case "trace" -> FrameTrace.start(Integer.parseInt(rest));
			case "fps" -> Vitrail.logger().info("debug script: {} fps", minecraft.getFps());
			case "turn" -> turning = Float.parseFloat(rest);
			case "pacing" -> {
				java.util.List<Long> sorted = new java.util.ArrayList<>(intervals);
				java.util.Collections.sort(sorted);
				double mean = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
				double sd = Math.sqrt(sorted.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0));
				Vitrail.logger().info("debug script: pacing {} frames mean {} us sd {} us p50 {} p95 {} p99 {} max {}",
						sorted.size(), Math.round(mean), Math.round(sd),
						sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2),
						sorted.isEmpty() ? 0 : sorted.get(sorted.size() * 95 / 100),
						sorted.isEmpty() ? 0 : sorted.get(sorted.size() * 99 / 100),
						sorted.isEmpty() ? 0 : sorted.getLast());
				long median = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
				StringBuilder spikes = new StringBuilder();
				long at = 0;
				long last = -1;
				for (long interval : intervals) {
					at += interval;
					if (interval > 2 * median) {
						spikes.append(last < 0 ? "" : " +" + (at - last) / 1000).append("(").append(interval / 1000).append(")");
						last = at;
					}
				}
				Vitrail.logger().info("debug script: spikes ms-apart(ms-long):{}", spikes);
				intervals.clear();
			}
			case "quit" -> minecraft.stop();
			default -> Vitrail.logger().warn("debug script: unknown step {}", step);
		}
	}

	private static long lastFrame;

	/** Frame intervals in microseconds while turning, for the pacing step. */
	private static final java.util.List<Long> intervals = new java.util.ArrayList<>();

	/**
	 * Called at the head of every rendered frame: turns the camera by the turn step's rate, scaled to
	 * the time since the last frame, so it moves on every frame as the mouse does.
	 */
	public static void frame() {
		long now = System.nanoTime();
		Minecraft minecraft = Minecraft.getInstance();
		if (turning != 0.0F && minecraft.player != null && lastFrame != 0L) {
			float ticks = (now - lastFrame) / 50_000_000.0F;
			intervals.add((now - lastFrame) / 1000L);
			minecraft.player.setYRot(minecraft.player.getYRot() + turning * ticks);
		}
		lastFrame = now;
	}

	private static @Nullable Path file() {
		String file = System.getProperty("vitrailmetal.debugScript");
		return file == null || file.isBlank() ? null : Path.of(file);
	}
}
