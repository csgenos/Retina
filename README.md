# Retina

Retina is an independent Vulkan shader-pack loader for Minecraft 26.2, Fabric, and Sodium
0.9.1. It translates legacy shader-pack GLSL and renders through Minecraft's native Blaze3D
Vulkan backend while retaining Sodium's chunk building, culling, buffers, and draw submission.

## Current status

Retina is an early, working Vulkan-native shader renderer—not yet an Iris-comparable release.
The goal is independently implemented feature breadth, visual quality, and stability comparable
to Iris, while using Minecraft's native Vulkan backend rather than copying Iris or promising a
drop-in implementation. Its terrain, target, post, terrain-shadow, scene-routing, and first
entity-program paths have been compiled and exercised in a live Vulkan world. The bundled
acceptance pack is intentionally diagnostic and does not provide normal Minecraft-quality
lighting; it proves renderer stages rather than visual polish.

### Live features

- Sodium terrain: solid, cutout, translucent, historical fallback resolution, real packed
  face normals, `gtexture`/`texture`, `lightmap`, terrain matrices/state, and Sodium's region
  push-constant ABI. Legacy `shadow2D*` compatibility lookups, including explicit-LOD forms,
  retain their legacy `vec4` result contract during translation.
- Render targets: up to eight simultaneous outputs selected from `colortex0..15`, double
  buffering, clear/color/format directives, mipmaps, flips, custom target size, pass scale,
  resize-safe retirement, and standard RGBA8/RGBA16F live targets.
- Post processing: numbered `composite` passes, `final`, internal pass-through final, and
  transactional pack activation (a failed CPU/GPU compile leaves the old pack running).
- Terrain shadows: `shadow.vsh/fsh`, 128–4096 power-of-two maps, `shadowDistance`, D32 depth,
  two `shadowcolor` outputs, raw `shadowtex1`, and comparison `shadowtex0`/`shadow` sampling.
  Visible Sodium terrain is replayed from a light-space camera into the map.
- Playable lighting reference: the versioned `retina_lighting_reference` pack combines terrain
  and entity lightmaps, distance fog, day-cycle tint, and terrain-shadow composition. It is the
  supported visual baseline while water, AO, clouds, bloom, exposure, and anti-aliasing remain
  later milestones.
- Scene routing: with full-resolution `RGBA8_UNORM colortex0`, the main world target is routed
  through the Retina post chain. Sky, clouds, weather, particles, entities, and block entities
  therefore survive final/composite processing; the player hand remains vanilla after final.
- Basic entity shaders: `gbuffers_entities.vsh/fsh` runs for the normal Minecraft
  `pipeline/entity_*` quad family, with the vanilla transform, fog, lighting, `Sampler0`, and
  `Sampler2` ABI. Unsupported entity pipeline families fall back safely to vanilla.
- Standard entity-format shadow casters: Retina records the original indexed buffers, dynamic
  transform UBO, and atlas binding, then replays compatible `pipeline/entity_*` draws into the
  terrain shadow map with alpha testing. This also covers a block entity when its renderer uses
  that same standard ABI; it does not claim support for custom block-entity formats.
- The lighting reference entity program applies the same distance fog as terrain. Vanilla's
  projected entity-shadow decal stays on its own translucent pipeline, avoiding an opaque black
  disk beneath entities.
- Release harness: checked-in diagnostic acceptance packs, an explicit development-client sync
  task, and a fixed-scene benchmark protocol. These measure regressions; they do not make a
  performance claim against other renderers.

### Known boundaries

- `retina_shadow_probe` is intentionally diagnostic and can look fullbright; use
  `retina_lighting_reference` for the supported visual baseline. Neither pack claims to be a
  polished replacement for a full visual shader pack.
- Entity, block-entity, player/hand, and mod-defined pipelines are not all specialized. Shadow
  casting is intentionally limited to the standard entity quad ABI; armor, eyes, items, player
  hand, custom block entities, and mod-defined formats remain excluded.
- Underwater medium effects are not implemented yet. Terrain culling still uses Minecraft/Sodium
  fog distances, but Retina does not yet add Iris-style underwater scene attenuation or water
  surface compositing.
- Deferred, `prepare`, `setup`, `shadowcomp`, compute/SSBO, geometry/tessellation, custom pack
  textures/images, resource-pack texture access, dimensions, Distant Horizons, and most of the
  Iris `shaders.properties` expression ecosystem are not live yet.
- Packs requiring a non-full-resolution/non-RGBA8 `colortex0` use a safe terrain-only fallback
  rather than bind an incompatible main scene target.

## Roadmap

1. Add dedicated block-entity and player/hand paths, then safely broaden shadow casters beyond
   the standard entity quad ABI.
2. Expand dedicated render-stage programs for particles, weather, clouds, sky,
   and effects.
3. Implement deferred/prepare/setup/shadowcomp stages and broaden colortex behavior.
4. Add custom textures/images/samplers and resource-pack texture integration.
5. Reach Iris-comparable breadth: expand the useful legacy shader-pack surface where it suits
   Retina, then add uniforms, expressions, material/entity maps, dimensions, mod support,
   advanced GPU stages, and Distant Horizons through Retina's own contracts.

## Requirements

- Minecraft 26.2
- Java 25
- Fabric Loader 0.19.3+
- Fabric API 0.157.0+26.2
- Sodium 0.9.1+mc26.2
- Minecraft's graphics backend set to Vulkan

Retina does not create a second Vulkan instance, logical device, surface, or swapchain. It uses
the device Minecraft already owns so it can draw Sodium's existing GPU buffers.

## Use

Open **Options → Video Settings → Shader Packs…**. Packs are discovered under `shaderpacks/`;
directories and ZIP files are supported. Shaders are off by default. Select a pack and choose
Apply. Selecting the active pack again reloads it; closing pack settings reloads changed options.

## Build and test

```text
./gradlew build
```

The build runs 74 backend-neutral tests plus three Fabric compiler/bridge tests. See
`docs/BUILDING.md`, `docs/ARCHITECTURE_AUDIT.md`, `docs/LIVE_RENDER_VALIDATION.md`, and
`docs/BENCHMARKS.md` for engineering detail, live validation, and performance protocol.
