# Vitrail for Metallum

OptiFine-format shader packs (Photon, Complementary, BSL, Sildur's, Solas, ...) on
[Metallum](https://modrinth.com/mod/metallum-mc), the Metal renderer for Minecraft on macOS.
Unmodified packs, picked and configured the way they are under Vitrail.

This is a fork of [Vitrail Shaders](https://github.com/avpbynf/Vitrail-Shaders) by avpbynf, which
does the same thing on Minecraft's own Vulkan renderer. Vitrail's pack loader, GLSL translator,
uniform catalogue, frame schedule and settings screen are used unchanged; what this fork changes is
the layer underneath, where Vitrail talks to Vulkan, so that it talks to Metallum's Metal backend
instead.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 or later, with Fabric API |
| Metallum | 0.0.24 (exactly: this mod hooks Metallum's internals) |
| Sodium | 0.9.x |
| Mac | Apple silicon (M1 or later) |

## Install

1. Put `vitrail-metallum-<version>.jar` in `mods/` next to Metallum, Sodium and Fabric API.
2. Leave **Video Settings > Graphics API** on its default, which Metallum labels **Prefer Metal**.
3. Put shader packs in `shaderpacks/` as usual, and pick one on the **Vitrail** page of the video
   settings, or with the `I` key. `K` turns shaders off and on, `R` reloads the pack from disk.

Remove Iris, and the original Vitrail, from the same instance: Iris draws on OpenGL only, and
Vitrail on Vulkan only. The mod refuses to load next to the original Vitrail.

Pack settings are stored next to the pack (`shaderpacks/<pack>.zip.txt`), in the file Iris uses
for the same pack. The mod's own files are in `vitrail/` in the instance folder, shared with
Vitrail.

## What changed from Vitrail

Everything the engine did through Vulkan is done through Metal here:

- **Backend.** Draws when the game runs on Metallum's Metal backend, instead of Vulkan, and sets the
  graphics API back to Metal (not Vulkan) after a startup that crashed.
- **Compute shaders.** A pack's compute programs (Photon's sky lighting and light propagation,
  Complementary's floodfill) are cross compiled to MSL kernels and dispatched on Metal compute
  encoders, in Metallum's own encoder order.
- **Custom images and storage buffers** (`image.*` and `bufferObject.*` directives) are Metal
  textures (1D, 2D, 3D, viewable in other formats) and Metal buffers, bound by name in render
  passes and computes, cleared and moved with Metal blit commands.
- **Colour targets a compute writes** (`colorimgN`) are created shader-writable.
- **Mip chains** are filled with Metal's blit `generateMipmaps`.
- **Hardware shadow comparison** uses a Metal comparison sampler.
- **Distant Horizons** is told Metal is not OpenGL. It names only Vulkan and OpenGL, and on Metal
  took the OpenGL road, whose lightmap hook casts the game's texture to an OpenGL one and stopped the
  game at the loading screen; it now draws through Blaze3D as it does under Vitrail on Vulkan, and
  its far terrain reaches the pack.
- **Entity pipelines** are evicted from Metallum's cache when the pack's vertex layout changes, and
  the live pack's pipelines are kept across resource reloads, as Vitrail does with Vulkan's.

And Metallum itself is extended where a shader pack needs more than the game does:

- up to **8 colour targets** per pass (Metallum 0.0.24 attaches one), with pipeline variants for
  passes that attach a different set;
- **sampler slots numbered per stage**, and samplers baked into the shader past Metal's sixteen;
- fragment outputs padded to the target's components, which Metal requires;
- **1D and 3D sampled textures**;
- shaders compiled as **MSL 3.1** whatever Java runs the game (Metal otherwise picks the version
  from the SDK the `java` binary was built with, MSL 3.0 for the launchers' Zulu runtimes);
- GLSL comments stripped with a real scanner instead of two regular expressions, which cut pack
  code in half wherever a line comment held a `/*`.

## Fixes over Vitrail

These were bugs in Vitrail on Vulkan as well, fixed here:

- **Flickering, yellow emissive layers with Entity Texture Features** (Fresh Animations' glowing
  spider eyes, any `_e` texture). ETF draws its emissive layer immediately, while the game is drawing
  solid entities, with a translucent render type. Vitrail only took translucent entity draws in
  its translucent window, so it handed these to the game's own shader, which drew them unjittered
  and already tone mapped into the game's picture; that showed through the pack's TAA-jittered body
  on some frames and came out yellow. Such draws now go through the pack's translucent entity
  program at the moment they arrive, as Iris does.
- **Glowing eyes (`gbuffers_spidereyes`)** now read the same per-draw model-view matrix as the body
  they are drawn over, and the shaders are compiled with Metal's `preserveInvariance`, so the overlay
  and the body land on the same depth.

- **Invisible water with Noble.** Noble's water stage hands `gl_FragDepth` to its shadow lookup
  without ever writing it. OpenGL leaves the depth alone in that case, but once compiled to SPIR-V
  the builtin is an output and the unwritten value failed the depth test, so no water was drawn. A
  stage that reads `gl_FragDepth` and never writes it now reads `gl_FragCoord.z` instead.

- **Stuck on the Mojang screen with Continuity and a connected-textures pack** (Patrix). The normal
  and specular atlases were uploaded one sprite and one mip level at a time; Metallum gives each
  upload its own staging copy and blit encoder, and with Continuity's sprites that was tens of
  thousands in one command buffer, which the GPU failed as out of memory, dropping every frame
  after it. Each level is now laid out on the CPU and uploaded once. Failed Metal command buffers
  are now logged, and so is a failure in the startup step Minecraft runs without reporting one.

## Not supported (yet)

- **Geometry shaders.** Metal has no geometry stage. As on a Vulkan device without one, a geometry
  stage that only passes vertices through is folded away and any other program is refused.
- **Background shader compilation.** Vitrail compiles the families a pack draws later (entities,
  particles, ...) ahead of time on Vulkan only; here they compile on their first draw, which can
  cost a short hitch the first time each appears.
- **Pass timings** (`-Dvitrail.passTimings`): Metallum has no GPU timestamps.

## Tested

On an M4 Max, macOS 27, Metallum 0.0.24, Sodium 0.9.2: Photon v1.3b (default settings and with
coloured lights), Complementary Reimagined r5.9.3 (default and with coloured lighting), BSL 10.1.8,
Sildur's Vibrant 2.02 Extreme, Solas 3.7b, Noble 1.9.9, Bliss 2.1.2 and MakeUp UltraFast 9.5g, in
the Overworld, Nether and End, day and night, on a recent JDK and on the Modrinth launcher's Zulu 25;
Photon and BSL with Entity Texture Features 7.2.6, Entity Model Features 3.3.12 and Fresh Animations
1.10.5 with its Extensions and Player packs; Photon with Distant Horizons 3.3.3; Photon with Continuity 3.0.1 and Patrix 32x.

## Building

Needs JDK 25.

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runClient` starts a development client with
Metallum, Sodium and Fabric API. For unattended checks, `-Dvitrailmetal.debugScript=<file>` runs a
list of steps once a world is on screen (see `dev/DebugScript.java`).

## Licence

LGPL-3.0-only, as Vitrail. See [LICENSE](LICENSE), [GPL-3.0.txt](GPL-3.0.txt) and [NOTICE](NOTICE).
Vitrail's own documentation is kept in `docs-upstream/`; it describes the Vulkan engine this was
forked from, and most of it applies unchanged.
