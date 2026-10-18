# Changelog

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
