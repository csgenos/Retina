# Retina handoff

Current as of 2026-08-13. Retina is an independently implemented, Vulkan-native renderer whose
long-term target is Iris-comparable feature breadth, quality, and stability—not an Iris clone
or a promise of drop-in Iris-pack compatibility. Its Minecraft 26.2/Fabric/Sodium terrain MRT,
shadow, composite/final, scene-routing, and first `gbuffers_entities` path are implemented,
built, and exercised in a live Creative world. `README.md` defines the public feature and
compatibility surface; `docs/LIVE_RENDER_VALIDATION.md` records the live tests.

## Handoff rule

Every completed renderer feature must update both `README.md` and this file in the same change:

- `README.md`: user-facing feature status, compatibility boundaries, and roadmap.
- `HANDOFF.md`: implementation detail, proven validation, known limits, and the next concrete
  engineering slice.

Do not describe scene routing as dedicated program support, or terrain shadows as entity shadow
support. Unsupported stages must remain explicit rather than silently falling back.

## Architecture

Minecraft 26.2 owns the Vulkan instance, device, surface, swapchain, and command stream.
Retina deliberately uses that native Blaze3D backend; it does not create a second Vulkan
device or copy Sodium terrain buffers to one. Sodium 0.9.1 continues to own chunk building,
culling, batching, and draw submission.

The live bridge works as follows:

1. `TerrainPackCompiler` resolves solid, cutout, and translucent program fallbacks, applies
   pack options/includes, translates legacy GLSL, and adapts it to Sodium's terrain ABI.
2. `ShaderRuntime` validates a candidate off-thread, then precompiles every terrain, shadow,
   composite, and final GPU pipeline on the render thread. The active pack swaps only after
   every pipeline and framebuffer resource succeeds.
3. Sodium mixins select the active pipeline, bind Retina's std140 terrain UBO, and extend the
   compact vertex format with a packed face normal for legacy `gl_Normal`.
4. `ColortexFramebuffer` owns two textures per referenced target, clear/mipmap/resize
   lifecycle, and Iris-style per-pass ping-pong state. Numbered composite passes render a
   procedural fullscreen triangle and final presents into Minecraft's main target.
5. `ShadowFramebuffer` owns a D32 map and up to two shadowcolor targets. At the end of world
   recording, Retina replays Sodium's visible terrain layers through `shadow.vsh/fsh` from a
   light-space orthographic camera, then replays captured compatible standard-entity indexed
   draws with their original vertex/index buffers, dynamic-transform UBO, and atlas binding.
   `shadowtex0` uses a Vulkan comparison sampler and `shadowtex1` exposes raw depth to post
   programs.
6. `VulkanDeviceMixin` reacquires the active in-memory shader source if Minecraft clears its
   pipeline cache during a resource reload.
7. Old uniform and framebuffer storage remains alive for four frames after a successful swap.
   A failed compile leaves the previous pack active and exposes the diagnostic in the
   shader-pack screen.

The extended 24-byte terrain vertex format is always used, including with shaders off. This
makes pack toggles safe without rebuilding chunk meshes; Sodium ignores the extra four bytes
on its normal pipeline.

The generic core translator preserves legacy `shadow2D*` lookups as `vec4`-returning wrappers,
including `shadow2DLod` and projected LOD variants. Before a terrain shader reaches the live
Sodium path, `TerrainShaderAdapter` replaces Retina's generic per-draw block with Sodium's exact
20-byte `u_RegionOffset`/time/region-id push-constant ABI. Do not reintroduce Retina draw members
there: Sodium owns that range for terrain submission.

## Proven state

Run from the repository root with JDK 25:

```powershell
.\gradlew.bat build --rerun-tasks --no-daemon
```

The current build passes 77 tests with zero failures: 74 backend-neutral pack, preprocessing,
translation, SPIR-V, render-graph, material, option, and safety tests plus three Fabric tests
that compile real GLSL 120 terrain and MRT/composite/final packs through shaderc and enforce
the live attachment limit.

Live validation used Minecraft 26.2, Fabric Loader 0.19.3, Sodium 0.9.1, and an AMD Radeon RX
6800 XT on Blaze3D Vulkan 1.4.315. A temporary GLSL 120 pack rendered terrain into RGBA8 and
RGBA16F attachments, sampled both in a mipmapped composite pass, ping-ponged colortex0, and
presented it through final. A second temporary pack rendered a 512px D32 terrain shadow map,
then sampled both raw and comparison depth in final. No Retina, pipeline, Mixin, or Vulkan
error remained after joining. The MRT chain also remained valid after resizing from 856x512 to
2560x1440. A separate OpenGL run with shaders off rendered the same world correctly through
Sodium's ordinary pipeline.

The distributable artifact is:

```text
retina-fabric/build/libs/retina-0.1.0+mc26.2.jar
```

## Supported live pack surface

The current bridge supports Sodium terrain programs and their historical fallback chain,
`gtexture`/`texture`, `lightmap`, up to eight simultaneous outputs selected from
`colortex0..15`, terrain matrices/state uniforms, and Sodium's 20-byte per-region
push-constant ABI. Live target management includes double buffering, clear and supported
format directives, mipmaps, flips, custom post-target sizes, pass scaling, numbered composite
passes, final/internal-final presentation, and resize-safe resource retirement.

Terrain shadows are supported through `shadow.vsh/fsh`, a 128..4096 power-of-two
`shadowMapResolution`, `shadowDistance`, D32 `shadowtex0/1`, and up to two `shadowcolor`
attachments. The visible terrain layers are re-submitted from the light view; shadows may be
sampled as raw depth (`shadowtex1`) or with hardware comparison (`shadowtex0`/`shadow`) in
composite/final programs.

`gbuffers_entities.vsh/fsh` is also supported for the standard Minecraft entity quad
pipeline. Retina translates legacy inputs onto `DefaultVertexFormat.ENTITY`, supplies the
matching vanilla transform, fog, lighting, `Sampler0`, and `Sampler2` ABI, and routes only the
`pipeline/entity_*` family while `colortex0` owns the scene. This keeps unsupported armor, eyes,
item, custom block-entity, and mod-defined formats on their vanilla pipelines. During a shadow
frame, compatible indexed draws from this standard family are recorded and replayed into the D32
map with their original `DynamicTransforms` slice and `Sampler0` atlas. The replay shader uses
the pack's shadow matrices and alpha-discard; a block entity is therefore covered only when it
uses this independently validated standard ABI.

`gbuffers_particles.vsh/fsh` is supported for precisely `pipeline/opaque_particle` and
`pipeline/translucent_particle`. `ParticleShaderAdapter` maps legacy attributes onto
`DefaultVertexFormat.PARTICLE` (`Position`, `UV0`, `Color`, `UV2`) and retains Minecraft's
native dynamic-transform, projection, fog, particle-texture (`Sampler0`), and lightmap
(`Sampler2`) bindings. The runtime precompiles distinct opaque and translucent pipelines so
the latter preserves vanilla alpha blending. Weather uses a related but different pipeline
contract and remains safely vanilla until it has its own stage.

When `colortex0` is a full-resolution `RGBA8_UNORM` target (the default), Retina temporarily
routes Minecraft's main world target there for the LevelRenderer frame. Sky, clouds, weather,
particles, entities, and block entities consequently survive through normal/composite/final
processing without forcing those renderers through Sodium's terrain vertex ABI. The player's
hand still renders after final presentation, as vanilla expects. Packs whose `colortex0` uses a
different size or format retain the prior terrain-only route and log one explicit fallback
warning rather than binding a mismatched Vulkan attachment.

It intentionally rejects custom terrain resources, non-standard entity and block-entity shader
programs, non-standard entity shadow casters, shadow comp, non-color/non-shadow post resources, and
deferred/prepare/setup passes. The backend-neutral core models more of that contract, but those
dedicated programs are not yet connected to live Minecraft render stages. Add new stages
transactionally and keep unsupported features as explicit diagnostics rather than silently
changing pack semantics.

## Next roadmap

Gate 0 is complete: `acceptance-packs/retina_shadow_probe` is a tracked source fixture,
`:retina-fabric:syncAcceptancePacks` copies it to the ignored development client directory, and
`docs/BENCHMARKS.md` defines the fixed-world commands and required metrics. No benchmark values
exist yet; the initial row explicitly records that fact. The client world was left untouched
after manual camera input, so the documented setup commands are the authority for controlled
future samples.

Gate 1 is complete: `retina_lighting_reference` is a tracked, versioned baseline pack. It uses
the existing terrain lightmap UV/sampler, fog color/range, and day-cycle `sunAngle` uniform—no
new renderer ABI was necessary. Its terrain shader writes color and shadow clip data to the
existing two-target MRT; final applies terrain shadows. Its standard entity shader uses the
vanilla lightmap sampler. The live fullscreen Creative-world run activated it transactionally,
routed the normal scene through `colortex0`, recorded the 1024px terrain shadow map, and logged
the standard entity path with no Retina, pipeline, Mixin, or Vulkan error. The only client log
errors were expected offline development-account authentication failures.

Gate 2 is complete: `RenderPassMixin` tracks the original resources and indexed draw arguments
for compatible `pipeline/entity_*` / `DefaultVertexFormat.ENTITY` passes. `ShaderRuntime` replays
their original buffers, dynamic transform UBOs, and atlas bindings after terrain into the live
shadow map using a dedicated alpha-tested entity-shadow pipeline. The development client
precompiled that pipeline on Vulkan, joined `shaderer test`, and logged `Recorded 1 standard
entity/block-entity shadow draws` with no Retina, Mixin, pipeline, or Vulkan error. This proves
the standard entity path; a chest probe remains a coverage candidate, not evidence that every
block-entity ABI is supported.

Post-Gate-2 validation found no draw-time Vulkan validation errors for the test scene. The visible
black circle under the pig was instead vanilla's `pipeline/entity_shadow` decal being incorrectly
routed through Retina's opaque entity pipeline. It is now excluded from both entity replacement
and caster capture, preserving vanilla's translucent decal. The lighting reference entity shader
also now computes eye-space fog distance and applies the terrain fog model, so distant entities no
longer punch through fog. The validation layer reports 17 leaked Vulkan objects on client shutdown;
that lifecycle issue remains recorded for a later stability pass. Underwater attenuation, water
surface compositing, and its desired chunk-loading presentation are later effects/stage work, not
Gate 0–2 terrain or standard-entity ABI work.

Gate 3 has started with the particle baseline. `ProgramId.GBUFFERS_PARTICLES`,
`TerrainPackCompiler`, `ParticleShaderAdapter`, and `ShaderRuntime` now form one transactional
optional path: absent particle programs retain vanilla rendering; a supplied GLSL 120 pair is
translated and shaderc-validated before separate opaque/translucent Vulkan pipelines are
precompiled. The acceptance lighting pack supplies this program and its development-client
activation logged `particles=true` with zero diagnostics on the AMD Vulkan backend. This is
activation evidence only; keep the next live test focused on visible opaque and translucent
particle probes before claiming in-world visual validation. `syncAcceptancePacks` is now an
explicit dependency of `runClient`, so the one-command development run cannot race its pack copy.

1. **Dedicated block entities and player/hand.** Add separately validated vertex/bind-group
   paths before claiming coverage for non-standard block entities, armor, items, eyes, or the
   player hand. Preserve the current safe exclusion for mod-defined formats.
2. **Dedicated scene stages.** Validate the particle baseline in-world, then add weather,
   clouds, sky, and effects with declared ordering and a separate compatibility boundary.
3. **Pipeline breadth.** Connect `prepare`, `deferred`, `setup`, and `shadowcomp`; expand
   ping-pong, target scaling/resizing, and attachment behavior only with live GPU tests.
4. **Resources and ecosystem.** Add pack textures/images/samplers, resource-pack texture
   access, Retina's own broad uniform/expression/material/entity contracts, dimensions, mod
   compatibility, and finally compute/SSBO/geometry/tessellation and Distant Horizons. Adopt
   useful legacy shader conventions selectively; do not frame this as an Iris clone or promise
   drop-in compatibility.

## Invariants to preserve

- Do not replace Blaze3D's Vulkan device with a private Retina device.
- Do not mutate the terrain vertex format only while a pack is active; that would make cached
  chunk meshes incompatible across toggles.
- Keep GPU pipeline creation on the render thread and compilation preparation off it.
- Never discard the active pack until every candidate pipeline and framebuffer has been
  created successfully.
- `gl_NormalMatrix` is a `mat3`.
- `gl_MultiTexCoord1` and `gl_MultiTexCoord2` share the lightmap input.
- Rename legacy sampler aliases token-wise; a `#define texture gtexture` also rewrites GLSL's
  `texture(...)` function and is invalid.
- Keep source provenance in Retina's line map rather than relying on driver-specific `#line`
  behavior.

## Licensing

Retina is LGPL-3.0-only. Root `LICENSE` and `NOTICE` files are included in the Fabric jar.
No Iris source is copied and Sodium is used only as a runtime/compile dependency; do not
vendor or redistribute Sodium source or jars.
