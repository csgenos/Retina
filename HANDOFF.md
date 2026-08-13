# Retina — handoff

Work was stopped partway through by request. This document states exactly what exists, what
is proven, what is unproven, and what the next person should do first.

Read `docs/ARCHITECTURE_AUDIT.md` before anything else. It contains the finding that
reshaped the design and the measured environment limits.

---

## 1. The one thing to know before touching the design

**Minecraft 26.2 already ships a native Vulkan Blaze3D backend, and Sodium 0.9.1 already
uses it.** This was verified by disassembling the supplied Sodium jar:

- `com.mojang.blaze3d.vulkan.{VulkanDevice, VulkanRenderPass, VulkanRenderPipeline}` exist.
- Sodium's `VulkanRenderPassAccessor` pulls an `org.lwjgl.vulkan.VkCommandBuffer` out of a
  render pass.
- Sodium's `VulkanPipelineMixin` adds a 20-byte push-constant range to its own pipelines.
- Sodium picks between `OPENGL`, `VK_MULTIDRAW` and `VK_INDIRECT` at runtime from
  `GpuDevice.getDeviceInfo().features()`.

The original brief assumed Minecraft is OpenGL-only and asked for Retina to create its own
Vulkan instance, device, surface and swapchain. **That would be wrong for 26.2** — two Vulkan
devices cannot present to one GLFW window, and Sodium's terrain buffers live on Minecraft's
device, so a second device could not draw them without a per-frame copy.

Retina therefore attaches to Minecraft's Vulkan device instead of creating one. This is still
a genuine native Vulkan path (no OpenGL, no Zink/ANGLE/DXVK) but the ownership is Minecraft's.
Do not "fix" this back to a private device without re-reading the audit.

---

## 2. What is real and proven

`retina-core` — a Gradle module with **no Minecraft dependency at all**. It builds and its
tests run in this environment.

```
gradle build -Pretina.coreOnly=true      # 72 tests, 0 failures
```

The tests are not string-comparison tests. `TranslationCompilesTest` takes real GLSL 120,
330-compatibility and 430 pack sources, translates them, compiles the result through **real
shaderc** to SPIR-V, and asserts on the **reflected SPIR-V module** — vertex input locations,
descriptor set/binding assignments, fragment output locations, push-constant presence.

Proven working:

| Area | Package | Notes |
| --- | --- | --- |
| Pack I/O | `core.pack` | Directory, ZIP and in-memory sources. Traversal, symlink escape, decompression bombs and NUL injection are all refused, with tests |
| Preprocessor | `core.preprocess` | Include expansion with exact per-line provenance; cycles, bombs and missing includes reported against the including file and line |
| Options | `core.option` | Discovery from annotated `#define`/`const`; applied by rewriting the declaration site, not by injecting macros (which does not work for `const` and is overridden for `#define`) |
| Translation | `core.translate` | Token-accurate lexer; legacy builtins, varying location allocation, descriptor sets, `DRAWBUFFERS`/`RENDERTARGETS`, alpha-test-as-discard |
| SPIR-V | `core.spirv` | shaderc compile + direct constant-pool reflection |
| Render graph | `core.graph` | Refuses read-write hazards and uninitialised reads at build time; derives barriers once; aliases transient memory |
| Properties | `core.props` | `shaders.properties` typed, with unknown directives **reported** rather than ignored |
| Material maps | `core.material` | `block/item/entity.properties` with pack-assigned ids and state predicates |
| Cache keys | `core.cache` | Refuse to build unless every invalidating input was supplied |

Three real bugs were found and fixed by these tests, which is the reason to trust them:
wrong `shaderc_shader_kind` constants (compute was compiling as vertex), dropped
`layout(rgba16f)` qualifiers on storage images, and a `Map.copyOf` that randomised the
uniform block's std140 member order per JVM run.

---

## 3. What is written but never compiled

`retina-fabric` — **has never been compiled and cannot be here.** `maven.fabricmc.net`,
`libraries.minecraft.net` and `piston-meta.mojang.com` are all blocked by the proxy (403),
so Loom cannot resolve Minecraft, mappings, or Fabric Loader.

Its Blaze3D and Sodium call sites were written against symbols read out of the supplied jars'
constant pools, which is authoritative for what Sodium calls and silent about everything else.
Expect compile errors. Files present:

```
RetinaPreLaunch          compatibility gate (throws -> Fabric error screen)
RetinaClient             lazy init; no GPU work at startup
RetinaVersion
compat/ModConflicts      Iris/OptiFabric/Canvas/VulkanMod/Sulkan/Embeddium detection
compat/SodiumCompatibility   fail-closed allowlist on 0.9.1+mc26.2
compat/CompatibilityReport
vk/VulkanBackend         identifies the Blaze3D backend; refuses OpenGL with a fixable message
mixin/blaze3d/VulkanRenderPassAccessor
pipeline/PackManager     discovery, inspection, per-pack option persistence
gui/ShaderPackScreen     pack list, Shaders: Off default, explicit Apply
gui/ShaderOptionScreen   per-pack options honouring the pack's own screen layout
config/RetinaConfig      renderer profiles that never alter pack semantics
resources/               fabric.mod.json, mixins, accesswidener, en_us.json
```

**Not implemented at all:** the actual render pipeline. Nothing binds a translated pack
program to a live Vulkan pipeline, nothing hooks `ShaderChunkRenderer.compileProgram`,
nothing writes the uniform ring buffer, nothing executes the render graph. The core produces
validated SPIR-V and a validated graph; the bridge that consumes them does not exist.

---

## 4. Recommended order of work

1. **Get `retina-fabric` to compile.** On a machine with network access:
   `gradle build`. Fix what the compiler finds. Verify `fabric-loom 1.14-SNAPSHOT` is right
   for 26.2 — the version in `gradle.properties` is a guess.
2. **Verify the `VulkanRenderPass` field names.** `VulkanRenderPassAccessor` uses
   `@Accessor("commandBuffer")` and `@Accessor("pipeline")`. Sodium's equivalent accessor
   proves the *methods* exist; the underlying *field names* were inferred. Check against the
   real class and correct them.
3. **Gate 3 before gate 5.** Get a vanilla-equivalent frame drawing through the attached
   device with `Shaders: Off` before wiring any pack program. A pack pipeline built on an
   unproven frame path is untestable.
4. **Wire `ShaderChunkRenderer.compileProgram`** — that is the single Sodium integration
   point that matters, and it is narrow.
5. **Only then** run the compatibility matrix against real packs.

---

## 5. Things that would be easy to get wrong

- `gl_NormalMatrix` is `mat3`, not `mat4`. Emitting it as a `mat4` corrupts every normal a
  pack transforms and produces plausible-looking wrong lighting.
- `gl_MultiTexCoord1` and `gl_MultiTexCoord2` are the *same* lightmap coordinate. They share
  one attribute location; separate locations leave one unfed.
- Never emit `#define texture gtexture` for the legacy sampler alias. `texture` is also a
  GLSL builtin function, and the macro rewrites every `texture(sampler, uv)` call into a call
  on a variable. Rename at token level instead (already done — do not undo it).
- `shadow2D`/`shadow2DProj` must be *wrapped*, not renamed to `texture`: the return type
  changes from `vec4` to `float` and packs index the result.
- Do not emit `#line` from the preprocessor. GLSL implementations disagree about whether
  `#line n` numbers the directive's line or the following one, which would make error
  provenance driver-dependent. Retina maps diagnostics through its own run list.
- The render graph refusing a "read before write" is usually a *real* pack bug or a missing
  history declaration. Do not relax it to make a pack load.

---

## 6. Honest status against the brief's acceptance criteria

| Criterion | Status |
| --- | --- |
| Fabric client mod jar with mod id `retina`, independent packages/resources/config | Structure done; **jar never built** |
| Prism starts MC 26.2 with Fabric API, Sodium and Retina | **Not tested** |
| Log proves presentation through Vulkan on the selected GPU | Banner code written; **never run** |
| No OpenGL path required while Retina is active | True by construction; **unverified** |
| Sodium CPU terrain pipeline genuinely integrated | **Not implemented** |
| `Shaders: Off` default, visually matching vanilla/Sodium | Default is correct; **rendering not implemented** |
| No cinematic pack bundled or silently enabled | **True** — verified, nothing ships |
| Packs discoverable, configurable, enable/reload/switch/disable through UI | Discovery, inspection and settings persistence work and are tested; **apply/reload not wired to a renderer** |
| A failed shader never strands the user on a black screen | Designed transactionally; **not exercised** |
| Core behaviours have tests | **True** — 72 tests |
| Unsupported OpenGL semantics reported honestly | **True** — `gl_TextureMatrix`, dual-source blend, illegal blend factors, non-storage-capable formats, unknown directives |
| Validation reports no Vulkan errors | **Not run** |
| Steady loop free of `vkDeviceWaitIdle`/readback/pipeline creation | Designed for; **no loop exists yet** |
| Performance claims backed by measurement | **No performance claims are made** |
| Shipped artefact is not Sulkan renamed and not Iris rebundled | **True** — Sulkan was never supplied; no Iris code copied |

No release artefacts were produced. `docs/BENCHMARKS.md` was not written because there are
no benchmarks; writing one would mean fabricating numbers.

---

## 7. Licensing

- Retina is **LGPL-3.0-only**, matching Iris, to remove any question about contract lineage.
  `LICENSE` and `NOTICE` still need to be added at the repository root — the build script
  already references them.
- **No Iris code was copied.** Iris was read to establish the pack format contract, which is
  an interface fact rather than copyrightable expression.
- **Sodium is Polyform Shield 1.0.0 — not open source.** It forbids use to compete with the
  licensor. Retina depends on it at runtime and compiles against it; it must never vendor,
  fork or redistribute Sodium source or jars.
