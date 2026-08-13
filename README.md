# Retina

Retina is an independent Vulkan shader-pack loader for Minecraft 26.2, Fabric, and
Sodium 0.9.1. It translates legacy shader-pack GLSL to Vulkan GLSL and runs terrain through
Minecraft's native Blaze3D Vulkan backend while preserving Sodium's chunk building,
culling, buffer management, and multi-draw submission.

## Current renderer

The live renderer supports solid, cutout, and translucent Sodium terrain, terrain shadow maps,
and a terrain MRT -> numbered composite -> final post chain. A pack program is
include-expanded, option-rewritten, translated, adapted to Sodium's compact vertex ABI or
Retina's procedural fullscreen ABI, compiled through shaderc, and only then committed on the
render thread. Pack switches are transactional: a CPU or GPU compile failure leaves the
previous working pack active.

Retina adds a packed face normal to Sodium's compact terrain format so legacy `gl_Normal`
programs receive real mesh data. The first 20 bytes remain Sodium-compatible, and the
extended 24-byte stride is used even while shaders are off, so toggling a pack never requires
terrain buffers to be rebuilt.

Minecraft can clear its Vulkan pipeline cache during resource reload. Retina retains the
validated in-memory source for each active pipeline and recompiles from that source on a
later cache miss; it never depends on a fake classpath shader.

### Deliberate compatibility boundary

The live Sodium terrain bridge binds `gtexture`/`texture`, `lightmap`, the terrain
matrices/state uniform block, and Sodium's 20-byte per-region push-constant ABI. Terrain can
write up to eight simultaneous attachments selected from `colortex0..15`. Retina allocates
live double-buffered colortex textures, honors supported format/clear/clear-color/mipmap,
`flip.<program>.<buffer>`, `size.buffer.*`, and `scale.*` directives, runs `composite` through
`composite99`, then presents `final` (or an internal pass-through final). Targets are rebuilt
on window resize and retired safely across in-flight frames.

When a pack supplies `shadow.vsh/fsh` and leaves `shadow.enabled`/`shadowTerrain` enabled,
Retina replays Sodium's visible terrain from an orthographic light view into a D32 shadow map.
It supports power-of-two `shadowMapResolution` values from 128 to 4096, `shadowDistance` from
16 to 2048, up to two `shadowcolor` outputs, raw `shadowtex1` sampling, and comparison
`shadowtex0`/`shadow` sampling in composite/final shaders. Shadow matrices are supplied in the
shared UBO.

Entity, block-entity, player, and distant-horizon shadow casters; shadow comp; deferred,
prepare, and setup stages; custom pack textures; and non-colortex/non-shadow post resources
are still rejected with an explicit diagnostic instead of rendering incorrectly.

## Requirements

- Minecraft 26.2
- Java 25
- Fabric Loader 0.19.3+
- Fabric API 0.157.0+26.2
- Sodium 0.9.1+mc26.2
- Minecraft's graphics backend set to Vulkan

Retina does not create a second Vulkan instance, logical device, surface, or swapchain. It
attaches to the device Minecraft already owns so it can draw Sodium's existing GPU buffers.

## Use

Open **Options → Video Settings → Shader Packs…**. Packs are discovered under
`shaderpacks/`; directories and ZIP files are supported. Shaders are off by default. Select
a pack and choose Apply. Choosing the active pack again reloads it, and closing its settings
screen reloads changed pack options.

## Build and test

```text
./gradlew build
```

The build runs 73 backend-neutral tests plus three Fabric compiler/bridge tests. The Fabric
tests compile real GLSL 120 terrain fallbacks and an MRT/composite/final pack through shaderc,
and reject attachment layouts Blaze3D cannot bind.

See `docs/BUILDING.md`, `docs/ARCHITECTURE_AUDIT.md`, and
`docs/LIVE_RENDER_VALIDATION.md` for details.
