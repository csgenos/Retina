# Changelog

## Unreleased

Fixes from the 0.1.0 audit in `docs/AUDIT_2026-08-14.md`.

- **Uniforms are read from the offsets they are written to.** The std140 layout gave `vec3` a
  size of 16 bytes rather than 12, so 42 of the 60 scene uniforms were uploaded 4–8 bytes away
  from where the shader read them and each received its neighbour's value. `fogStart`, `fogEnd`,
  `near`, `far`, `sunAngle`, `frameTimeCounter` and `rainStrength` were all affected, including
  in the bundled reference pack. The layout now lives in `dev.retina.core.uniform.Std140Layout`
  and is asserted against the offsets a real compiler assigns.
- **Packs that declare their own vertex attributes compile.** `attribute vec4 mc_Entity;` and
  the GLSL 330 `in` form now receive explicit locations above the reserved legacy slots. They
  currently read zero; see the boundary note in `README.md`.
- **Packs that declare their own fragment outputs compile.** The injected alpha test binds to
  the pack's own output at location 0 instead of an output Retina did not emit.
- **Array uniforms keep their length** through the generated block and the union layout, in
  both the `vec3 lights[4]` and `vec3[4] lights` spellings.
- **Multi-declarator varyings each get a location.** `varying vec2 a, b;` no longer overlaps
  both names onto location 0.
- **A block comment inside a relocated declaration no longer shifts line numbers**, so
  diagnostics after it point at the right source line.
- Writing past the declared draw buffers, and declaring a struct uniform, are now refused with
  a message naming the cause instead of failing later as an undeclared identifier.
- `gradlew` is committed executable, so `./gradlew build` works and CI runs. The workflow gains
  a `retina-core` job that does not need the Fabric or Mojang repositories.
- Removed the checked-in copies of Minecraft and Blaze3D source and Minecraft's shader assets,
  which `NOTICE` already said were not redistributed.
- Removed settings that did nothing: the debug-overlay and parallel-compilation toggles, and the
  unread `validationLayers`, `adapterOverride` and `vramBudgetMegabytes` config fields. The
  config file is written with an atomic move.
- Dropped the unused `fabric-screen-api-v1` and `fabric-key-mapping-api-v1` hard dependencies.
- Per-draw hooks short-circuit on one static field when no pack captures draw state, and scene
  pipeline substitution is memoised per pipeline instead of re-comparing resource paths on every
  bind. Pack compilation probes for files with an index lookup rather than decompressing each
  candidate entry.
- The icon on Retina's page inside Sodium's settings menu now actually renders. It was wired to
  `retina:icon`, a texture path that never existed, so the page has never shown one.
- Added `normals` and `specular` PBR samplers to every gbuffer, shadow, and applicable
  post-processing pipeline. A pack that declares them now compiles, links, and renders instead of
  either failing outright or leaving an unbound descriptor; they read a flat "no data" default
  until real per-block `_n`/`_s` texture sourcing from the active resource pack lands (tracked
  separately). This is the fix for packs, including Sildur's, that previously would not load at
  all once they referenced either sampler -- an unrelated compile-time allowlist in
  `TerrainPackCompiler` that predated this feature was still refusing both names outright and
  needed updating too; the 0.2.0 release still has this gap.
- Audited every name `BindingLayout` recognises as a scene sampler against what actually gets
  bound at runtime and what both of `TerrainPackCompiler`'s compile-time allowlists permit
  (`rejectUnboundResources` for terrain/entity/particle/shadow, `postSamplers` for
  prepare/deferred/composite/final/shadowcomp), rather than continuing to patch one refused name
  at a time as packs hit them. Found and fixed two more real gaps of the same shape as
  normals/specular: `noisetex` now binds a flat fallback everywhere `normals`/`specular` do
  (this was the exact refusal Sildur's hit right after the normals/specular fix), and
  `depthtex0` now binds the real main scene depth, but only in post-processing pipelines, where
  it is a complete finished snapshot by the time any program reads it. `depthtex1`/`depthtex2`
  and `colortex*` as a gbuffer-stage input remain refused; see the README boundary notes.
- Investigated the next live refusal (`gbuffers_textured` declaring `shadowtex0`, again against
  Sildur's): unlike the previous three, this one is not a missing allowlist entry. Retina
  generates the shadow map by replaying the main scene's already-visible terrain from the light's
  view *after* the whole main scene renders (`LevelRendererMixin` calls `renderShadowPass()` at
  `LevelRenderer.render`'s `RETURN`, not its `HEAD`), so `shadowtex0`/`shadowtex1`/`shadow`/
  `shadowcolor0`/`shadowcolor1` genuinely have no data yet at any gbuffer stage; the refusal was
  already correct. Sampling the shadow map straight from a gbuffer program is a common pack
  technique that needs the shadow pass reordered ahead of the main scene, which needs independent
  shadow-frustum culling Retina does not have -- real follow-up work, tracked in #22, not a patch.
  The refusal message now explains this (and the equivalent reasons for `depthtex1`/`depthtex2`
  and `colortex*`) instead of repeating the same generic "cannot bind yet" text for every name.
- Pushing a bare version tag (e.g. `0.2.0`) now publishes a GitHub release automatically, with the
  built jar attached and a `CHANGELOG.md` section as the release body. Nothing needs pre-editing:
  the tag supplies the version and the Minecraft component always comes from the committed
  `minecraft.version`, never the tag. See `docs/PUBLISHING.md`.

## 0.1.0+mc26.2 — prerelease

Initial public preview for Minecraft 26.2, Fabric, Sodium 0.9.x, Java 25, and Minecraft's Vulkan
backend.

- Legacy GLSL terrain translation with solid, cutout, and translucent passes.
- Colortex targets, prepare/deferred/composite/final chains, terrain shadows, and shadowcomp.
- Standard entity, particle, and weather shader paths with distance fog.
- Bundled `retina_lighting_reference` diagnostic shader pack.

This is an early preview, not Iris compatibility. Use the included reference pack for validation;
third-party shader packs may fail to load or render incorrectly.
