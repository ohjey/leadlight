# Leadlight

**Shader packs on your Mac.** Leadlight runs OptiFine-format shader packs, unmodified, on
[Metallum](https://modrinth.com/mod/metallum-mc)'s Metal renderer for Apple silicon. Photon,
Complementary, BSL, Sildur's, Solas, Noble, Bliss, MakeUp and more draw the way they do under Iris,
including features Iris can't run on macOS's OpenGL 4.1, such as compute shaders (Photon's SH
skylight and coloured lights).

### Requirements
- Minecraft 26.2, Fabric Loader 0.19.3+, Fabric API
- [Metallum](https://modrinth.com/mod/metallum-mc) **0.0.24** (exactly)
- [Sodium](https://modrinth.com/mod/sodium) 0.9.x
- An Apple silicon Mac (M1 or later)

### How to use
1. Install Leadlight with Metallum, Sodium and Fabric API. Leave Video Settings > Graphics API on
   **Prefer Metal**.
2. Put shader packs in `shaderpacks/`, then pick one on the **Leadlight** page of the video settings
   or press `I`. `K` toggles shaders, `R` reloads the pack.

Don't install Iris or Vitrail in the same instance. Pack settings are shared with Iris
(`shaderpacks/<pack>.zip.txt`), so your settings carry over.

### Works with
Distant Horizons, Continuity (with Patrix and other connected-texture/PBR packs), Entity Texture
Features and Entity Model Features with Fresh Animations.

### Not supported
Geometry shaders (Metal has no geometry stage). Packs that only pass vertices through their geometry
stage still work.

### Credits
Leadlight is built on [Vitrail](https://github.com/avpbynf/Vitrail-Shaders) by avpbynf, and is
LGPL-3.0, like Vitrail. It is an independent project, not affiliated with Vitrail, Metallum, Iris
or OptiFine.
