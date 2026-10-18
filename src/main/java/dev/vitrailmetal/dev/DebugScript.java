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
 * quit               closes the game
 * </pre>
 */
public final class DebugScript {

	private static final @Nullable Path FILE = file();

	private static @Nullable ArrayDeque<String> steps;

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
			case "quit" -> minecraft.stop();
			default -> Vitrail.logger().warn("debug script: unknown step {}", step);
		}
	}

	private static @Nullable Path file() {
		String file = System.getProperty("vitrailmetal.debugScript");
		return file == null || file.isBlank() ? null : Path.of(file);
	}
}
