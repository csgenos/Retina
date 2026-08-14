# Retina

Retina is an independent Vulkan shader-pack loader for Minecraft 26.2, Fabric, and Sodium
0.9.1. It translates legacy shader-pack GLSL and renders through Minecraft's native Blaze3D
Vulkan backend while retaining Sodium's chunk building, culling, buffers, and draw submission.

## Installation

Retina is a client-only Fabric mod. Install the matching Fabric Loader, Fabric API, Sodium 0.9.x,
and Retina into the instance's `mods` folder; then select Minecraft's Vulkan graphics backend.
It intentionally conflicts with Iris, Sulkan, VulkanMod, Canvas, and Embeddium because only one
renderer/shader pipeline can own the client. See [PUBLISHING.md](docs/PUBLISHING.md) for the
release artifact and platform-upload checklist.

### Shader settings UI

With Sodium 0.9.1, Retina registers through Sodium's public configuration API. Open Sodium's
Video Settings screen and select the **Retina** section in the left navigation; **Shader Packs**
opens Retina's pack browser, while **Renderer** exposes Retina's profile. This also works with clients that replace vanilla Video Settings,
including Lunar's Sodium screen. The ordinary Minecraft Video Settings button remains as a
fallback when Sodium's screen is not in use.

## Continuous builds

GitHub Actions validates every pull request and every push to `main`, then keeps
the built Fabric mod JAR as a downloadable workflow artifact for 30 days. This
is a CI build for testing; it does not automatically create a GitHub release or
publish to Modrinth or CurseForge.

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
- Post processing: numbered `prepare`, `deferred`, and `composite` passes, `final`, internal
  pass-through final, and transactional pack activation (a failed CPU/GPU compile leaves the old
  pack running). Prepare, deferred, composite, and final execute at a render-pass-safe boundary
  after Retina's assembled world scene; deferred precedes composite/final. Neither yet exposes
  Iris's per-stage particle or weather ordering controls.
- Shadow composition: numbered `shadowcomp` passes run after terrain/entity shadow replay and
  before deferred. They can sample `shadowtex0`, `shadowtex1`, `shadow`, and `shadowcolor*`, then
  write through normal colortex targets. Native shadowcolor-attachment output remains later work.
- Underwater medium: while Minecraft reports a submerged water camera, Retina applies a
  depth-aware full-scene attenuation pass before deferred/composite/final. It preserves nearby
  detail while suppressing distant above-water scenery; pack-specific water stylisation remains
  later work.
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
  `Sampler2` ABI. Legacy single-range fog is clamped to the nearer of Minecraft's environmental
  and render-distance ranges, so distant entities do not escape the terrain fade. Unsupported
  entity pipeline families fall back safely to vanilla.
- Particle shaders: optional `gbuffers_particles.vsh/fsh` runs for Minecraft's
  `pipeline/opaque_particle` and `pipeline/translucent_particle` quad stages. It keeps
  vanilla's opaque-versus-alpha-blended split and supplies the matching transform, combined
  environment/render-distance fog,
  particle texture, and lightmap ABI. Weather is deliberately not routed through this stage.
- Weather shaders: optional `gbuffers_weather.vsh/fsh` runs for Minecraft's weather depth-write
  and no-depth-write quad pipelines, preserving their translucent blend/depth behavior and the
  particle-format transform, fog, texture, and lightmap ABI.
- PBR samplers: `normals` and `specular` are real bound samplers on every terrain, entity,
  particle, weather, shadow, and applicable post-processing pipeline, so a pack that declares
  them compiles and renders instead of failing to load. See Known boundaries for what they are
  bound to.
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
- Underwater attenuation is a fixed Retina medium pass, not a replacement for a pack's water
  program. Water-surface compositing, caustics, refraction, and pack-controlled underwater
  colour/fog behavior remain future work.
- `setup`, compute/SSBO, geometry/tessellation, custom pack
  textures/images, dimensions, Distant Horizons, and most of the Iris `shaders.properties`
  expression ecosystem are not live yet.
- Packs requiring a non-full-resolution/non-RGBA8 `colortex0` use a safe terrain-only fallback
  rather than bind an incompatible main scene target.
- Pack-declared vertex attributes such as `mc_Entity`, `mc_midTexCoord`, `at_tangent`, and
  `at_midBlock` are accepted, given locations, and compile, but none of the vertex formats
  Retina draws through supplies them yet, so they read zero. An effect keyed on one of them
  behaves as though every block were untagged; sourcing the real data is separate work.
- `normals` and `specular` are real, always-bound samplers on every gbuffer, shadow, and
  applicable post-processing pipeline, so packs that declare them compile and render rather than
  failing to load. They are bound to a flat "no data" default (a neutral tangent-space normal;
  zero smoothness/metalness/porosity/emission) rather than a resource pack's own `_n`/`_s`
  textures, which are not sourced yet. A pack renders as though every block were untagged for
  PBR, the same safe fallback OptiFine and Iris use for a block with no `_n`/`_s` texture.
- A debug overlay and a parallel shader-compilation pool are both wanted and neither exists.
  Sodium's **Renderer** page therefore lists only the profile selector.

## Roadmap

1. Add dedicated block-entity and player/hand paths, then safely broaden shadow casters beyond
   the standard entity quad ABI.
2. Expand dedicated render-stage programs for weather, clouds, sky, and effects, then add
   their ordering and cross-stage contracts around the completed particle baseline.
3. Implement setup and native shadowcolor processing, then broaden colortex behavior.
4. Source real per-block `_n`/`_s` textures from the active resource pack into the `normals`/
   `specular` samplers, matching the vanilla block atlas's own sprite layout, then add custom
   pack-declared textures and images.
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

The build runs 88 backend-neutral tests plus three Fabric compiler/bridge tests. See
`docs/BUILDING.md`, `docs/ARCHITECTURE_AUDIT.md`, `docs/LIVE_RENDER_VALIDATION.md`, and
`docs/BENCHMARKS.md` for engineering detail, live validation, and performance protocol.
