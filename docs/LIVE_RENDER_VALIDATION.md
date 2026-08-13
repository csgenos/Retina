# Live renderer validation

Validated on 2026-08-13 with Minecraft 26.2, Fabric Loader 0.19.3, Fabric API
0.157.0+26.2, Sodium 0.9.1+mc26.2, Java 25, LWJGL 3.4.1, and an AMD Radeon RX 6800 XT.

## What was exercised

1. The full Gradle build completed, including all 76 tests, access
   widener validation, sources jar, and mod jar.
2. The Fabric development client reached the main menu with every Retina mixin enabled.
3. Minecraft selected its native Vulkan backend. `DeviceInfo` reported Vulkan 1.4.315,
   AMD's 26.7.1 proprietary driver, multi-draw indirect support, and persistent mapping.
4. Retina discovered a temporary GLSL 120 pack, translated its terrain MRT, composite, and
   final programs, shaderc-validated every adapted stage, precompiled five live pipelines,
   allocated RGBA8/RGBA16F colortex pairs, and activated the candidate transactionally.
5. A temporary singleplayer world loaded. Terrain wrote colortex0 and colortex1, composite
   sampled both with colortex0 mipmaps enabled, the target ping-ponged, and final presented the
   result. A solid-magenta final probe separately proved final-target ownership.
6. The window was maximized from 856x512 to 2560x1440 while the world was running. Colortex
   resources reallocated and rendering continued without Retina or Vulkan errors.
7. The client was restarted on OpenGL with shaders off, then loaded the same world. Sodium's
   ordinary terrain pipeline rendered correctly with Retina's extended 24-byte vertex stride,
   proving the default/off path does not require Vulkan and ignores the added normal field.
8. A separate Vulkan pack with `shadow.vsh/fsh` activated transactionally. Retina allocated a
   512px D32 shadow map, replayed two visible Sodium terrain layers from the light view, and
   exposed both raw `shadowtex1` and comparison `shadowtex0` to final. The deliberately green
   final output confirmed both depth forms reached the presentation pass without Vulkan errors.
9. The same Vulkan client was restarted after main-scene routing landed, then joined the
   fullscreen `shaderer test` Creative world. The log recorded `Routing vanilla world features
   into colortex0`; the rendered frame visibly retained the blue sky, clouds, terrain, water,
   foliage, HUD, and player hand through Retina's active shadow/composite/final path. No Retina
   or Vulkan error appeared after joining.
10. The client was restarted after adding `gbuffers_entities`. The acceptance pack compiled a
    GLSL 120 entity program, the log recorded `Routing minecraft:pipeline/entity_cutout through
    gbuffers_entities`, and a newly spawned pig rendered with the pack's intentional warm-tint
    probe. The Creative world stayed stable with the entity visible, confirming the standard
    entity ABI, sampler binding, scene attachment, and pipeline replacement path.
11. Gate 0 added a tracked copy of the terrain-shadow/entity probe and verified that
    `:retina-fabric:syncAcceptancePacks` materializes all eight shader stages into the ignored
    Fabric development-client shader-pack directory. The targeted Fabric test suite remained
    green. `docs/BENCHMARKS.md` now defines the controlled Creative-world setup and metrics;
    no performance sample has been recorded yet.
12. Gate 1 added and activated `retina_lighting_reference` in the fullscreen `shaderer test`
    Creative world. It compiled with zero diagnostics, sampled the terrain and standard-entity
    lightmaps, applied distance fog and day-cycle tint, recorded the existing 1024px terrain
    shadow map, and presented the scene through `colortex0`/final. The live noon shoreline
    retained normal daylight, fogged distance, terrain detail, entities, and the player hand
    after the fullscreen resize. No Retina, pipeline, Mixin, or Vulkan error appeared; offline
    development-account authentication errors were unrelated.
13. Gate 2 restarted the development client, precompiled the dedicated standard-entity shadow
    pipeline on the AMD Vulkan device, and rejoined `shaderer test`. The first live frame routed
    the entity family through `gbuffers_entities`, recorded the terrain shadow map, and logged
    `Recorded 1 standard entity/block-entity shadow draws`. No Retina, Mixin, pipeline, or
    Vulkan error followed. A nearby chest was placed as a future block-entity coverage probe;
    the one verified replay is intentionally recorded as standard-entity ABI coverage only.

## Bugs found by the world tests

The first attempt precompiled correctly at startup but failed at the first world draw.
Minecraft had cleared its Vulkan pipeline cache during resource reload, then tried to find
Retina's synthetic shader identifier in the normal resource manager. The pipeline was
therefore invalid.

`VulkanDeviceMixin` now intercepts only active Retina pipeline cache misses and calls the
backend's public `precompilePipeline` method with the already validated in-memory
`ShaderSource`. The next run loaded and rendered the world successfully. This preserves both
properties Retina needs: GPU compilation is an activation gate, and later cache eviction is
recoverable without putting generated GLSL on disk.

The MRT acceptance run also caught three live-only integration mistakes: custom render-pass
descriptors require an explicit render area; a procedural fullscreen vertex must not apply
the world camera transform; and Blaze3D's draw signature starts with vertex count rather than
first vertex. The runtime now supplies bounded render areas, replaces post `ftransform()`
with clip-space passthrough, and submits `draw(3, 1, 0, 0)`.

The first shadow-world attempt caught an allocation contract error: Blaze3D requires
`USAGE_COPY_DST` for a depth texture cleared through its command encoder. `ShadowFramebuffer`
now declares that usage, and the subsequent run rendered and sampled the map successfully.

Authentication/Realms 401 messages in the development client were expected for Loom's
offline development account and were unrelated to rendering.
