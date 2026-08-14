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

## 0.1.0+mc26.2 — prerelease

Initial public preview for Minecraft 26.2, Fabric, Sodium 0.9.x, Java 25, and Minecraft's Vulkan
backend.

- Legacy GLSL terrain translation with solid, cutout, and translucent passes.
- Colortex targets, prepare/deferred/composite/final chains, terrain shadows, and shadowcomp.
- Standard entity, particle, and weather shader paths with distance fog.
- Bundled `retina_lighting_reference` diagnostic shader pack.

This is an early preview, not Iris compatibility. Use the included reference pack for validation;
third-party shader packs may fail to load or render incorrectly.
