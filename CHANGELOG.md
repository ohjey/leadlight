# Changelog

## 0.1.1

- Fixed Photon 1.4 failing to load (`'ofTexture' : no matching overloaded function found`). A pack
  macro that stands for the `texture()` function, such as Photon 1.4's
  `#define BLOOM_UPSAMPLING_FILTER texture`, was renamed as if it were the old `texture` sampler.
  The name is now only renamed where the pack declares a variable by that name.
- The shader pack's shadow map extent is logged once per load.

## 0.1.0 (first release)

Leadlight runs OptiFine-format shader packs, unmodified, on Metallum's Metal renderer, for
Minecraft 26.2 on Apple silicon Macs. It is built on Vitrail 0.13.0-beta by avpbynf.

- Shader packs on Metal: render passes with up to 8 colour targets, compute shaders (Photon's sky
  lighting and coloured lights, Complementary's floodfill, Noble's compositing), custom images and
  storage buffers, mip chains and hardware shadow comparison.
- Shaders compile as MSL 3.1 whichever Java runs the game, including launchers' bundled runtimes.
- Distant Horizons draws on Metal, and its far terrain reaches the pack.
- Continuity with connected-texture and PBR resource packs such as Patrix.
- Fixed flickering, yellow emissive layers with Entity Texture Features and Fresh Animations.
- Fixed invisible water with Noble.

Requires Metallum 0.0.24, Sodium 0.9.x and Fabric API.
