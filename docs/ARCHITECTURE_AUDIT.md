# Retina architecture audit

This document records what was actually found by inspecting the resolved dependencies, and
what those findings mean for Retina's design. Nothing here is recalled from memory; every
claim about an API is traceable to a symbol read out of a shipped jar, and where a claim
could not be verified, it says so.

**Method.** Three jars were supplied and extracted:

| Jar | Version | Licence |
| --- | --- | --- |
| `sodium-fabric-0.9.1+mc26.2.jar` | 0.9.1+mc26.2 | Polyform Shield 1.0.0 |
| `iris-fabric-1.11.2+mc26.2.jar` | 1.11.2+mc26.2 | LGPL-3.0-only |
| `fabric-api-0.157.0+26.2.jar` | 0.157.0+26.2 | Apache-2.0 |

Symbols were read by parsing class-file constant pools directly and by `javap` disassembly.
Minecraft 26.2 itself was **not** available (see "Environment limits" at the end), so every
statement about Blaze3D below is derived from what Sodium and Iris call, which is
authoritative for those call sites and silent about everything else.

---

## 1. The finding that reshaped the design

**Minecraft 26.2 already has a native Vulkan backend, and Sodium 0.9.1 already uses it.**

Sodium's mixin configuration (`sodium-common.mixins.json`) lists:

```
core.GpuDeviceAccessor
core.CommandEncoderAccessor
core.GlCommandEncoderAccessor
core.GlRenderPassAccessor
core.RenderPassAccessor
core.VulkanPipelineMixin
core.VulkanRenderPassAccessor
```

`VulkanRenderPassAccessor` is declared as:

```java
@Mixin(com.mojang.blaze3d.vulkan.VulkanRenderPass.class)
public interface VulkanRenderPassAccessor {
    com.mojang.blaze3d.vulkan.VulkanRenderPipeline sodium$getPipeline();
    org.lwjgl.vulkan.VkCommandBuffer sodium$getCommandBuffer();
}
```

and `VulkanPipelineMixin` wraps pipeline-layout creation to add a 20-byte push-constant range
for `sodium`-namespaced pipelines:

```java
VkPushConstantRange.calloc(1, stack).offset(0).size(20).stageFlags(0x7FFFFFFF)
```

Blaze3D in 26.2 is therefore backend-abstracted, with at least these two implementations:

| Abstraction | OpenGL implementation | Vulkan implementation |
| --- | --- | --- |
| `com.mojang.blaze3d.systems.GpuDevice` | `com.mojang.blaze3d.opengl.GlDevice` | `com.mojang.blaze3d.vulkan.VulkanDevice` |
| `com.mojang.blaze3d.systems.RenderPass` | `com.mojang.blaze3d.opengl.GlRenderPipeline` (pass side via `GlRenderPassAccessor`) | `com.mojang.blaze3d.vulkan.VulkanRenderPass` |
| pipeline | `com.mojang.blaze3d.opengl.GlProgram` | `com.mojang.blaze3d.vulkan.VulkanRenderPipeline` |

Sodium selects a draw path from device features at runtime:

```java
enum DrawBackend { OPENGL, VK_MULTIDRAW, VK_INDIRECT; ... }
// chooseBackend() reads GpuDevice.getDeviceInfo().features()
//   .multiDrawDirectInterleaved() -> VK_MULTIDRAW
//   .multiDrawIndirect()          -> VK_INDIRECT
//   otherwise                     -> OPENGL
```

The verified Blaze3D surface is small and precise:

```
GpuDevice.createBuffer(Supplier, int, long) -> GpuBuffer
GpuDevice.createCommandEncoder()            -> CommandEncoder
GpuDevice.getDeviceInfo()                   -> DeviceInfo
DeviceInfo.backendName()                    -> String
DeviceInfo.features()                       -> DeviceFeatures
DeviceFeatures.multiDrawDirectInterleaved() -> boolean
DeviceFeatures.multiDrawIndirect()          -> boolean
DeviceFeatures.persistentMapping()          -> boolean
```

### What this changes

The brief specified building a Vulkan instance, physical-device enumeration, logical device,
surface, and swapchain, on the assumption that Minecraft is OpenGL-only. **That assumption
does not hold for 26.2, and following it would produce a worse and unshippable mod.** Two
Vulkan devices cannot present to one GLFW window, and Sodium's terrain buffers are allocated
on Minecraft's device — a second device could not draw them without a copy per frame, which
defeats the entire point of integrating with Sodium.

Retina therefore **attaches to Minecraft's existing Vulkan device** and records into the
existing command buffers, exactly as Sodium does. This still satisfies the hard requirement:
there is no OpenGL in the path, no Zink, no ANGLE, no DXVK, and no translation layer. What
changes is ownership — the instance, device and swapchain are Minecraft's.

The consequence is stated plainly in the README: **Retina requires Minecraft to be running
its Vulkan backend.** If the game started on OpenGL, Retina reports that as a setting to
change rather than crashing. That check is `dev.retina.vk.VulkanBackend.attach()`.

The parts of the brief this displaces — swapchain recreation, present-mode selection,
device-lost handling, adapter selection — are Minecraft's responsibility in 26.2, not
Retina's. Retina's swapchain-related work reduces to invalidating its own render targets and
history resources on resize, which is a render-graph concern and is implemented there.

---

## 2. Sodium: what is reusable and what is backend-bound

### Backend-neutral (reusable as-is)

Read from `net.caffeinemc.mods.sodium.client.render.chunk.*`:

| System | Class | Why it is neutral |
| --- | --- | --- |
| Section management | `RenderSectionManager`, `RenderSection` | Pure CPU bookkeeping over `SectionPos` |
| Visibility / occlusion | `RenderSection.getVisibilityData()`, `intersectSlopes`, `occlusion/` | Bit-twiddling on a graph; no GPU calls |
| Chunk build scheduling | `compile/executor/ChunkJob`, `compile/ChunkBuildOutput` | Worker threads producing byte buffers |
| Vertex data generation | `render/chunk/vertex/` | Produces a packed format; format is neutral, upload is not |
| Translucent sorting | `render/chunk/translucent_sorting/` | CPU sort producing index data |
| Region grouping | `render/chunk/region/RenderRegion` | Spatial grouping for batching |

These are what "Sodium integration" has to mean. Retina does not reimplement any of them.

### Backend-bound (Sodium already abstracts these)

| System | Class | Note |
| --- | --- | --- |
| Draw submission | `DefaultChunkRenderer.render(...)`, `gpu/device/batch/MultiDrawBatch` | Chooses `DrawBackend` at runtime |
| Buffer arena / staging | `gpu/arena/`, `gpu/arena/staging/` | Built on `GpuBuffer`/`GpuBufferSlice` |
| Terrain pipelines | `ShaderChunkRenderer.compileProgram(TerrainRenderPass)` | Builds a `com.mojang.blaze3d.pipeline.RenderPipeline` |
| Per-frame uniforms | `UniformBufferManager` | Uses `net.minecraft.client.renderer.DynamicUniformStorage` |

The integration point for a shader loader is `ShaderChunkRenderer`:

```java
protected RenderPipeline compileProgram(TerrainRenderPass);
public static final BindGroupLayout BIND_GROUP;
protected RenderPipeline activeProgram;
```

A pack's terrain program has to substitute for the pipeline `compileProgram` returns while
leaving `DefaultChunkRenderer.render(...)` — which owns the multi-draw batching — untouched.
That is a narrow, version-gated hook, which is why `SodiumCompatibility` fails closed on any
version other than the tested one.

### Licensing constraint

**Sodium is Polyform Shield 1.0.0, which is not an open-source licence.** It permits use but
forbids use "to compete with the licensor". Retina therefore:

- does **not** vendor, fork, or copy any Sodium source;
- depends on Sodium at runtime as an ordinary mod, and compiles against it only;
- does **not** redistribute the Sodium jar.

This is the same relationship Iris has with Sodium.

---

## 3. Iris: what it establishes about the shader-pack contract

Iris 1.11.2 is LGPL-3.0-only. **No Iris code was copied into Retina.** It was read to
establish what the shader-pack contract actually is, because that contract is a
compatibility interface rather than an implementation — the fact that `DRAWBUFFERS:0231`
means "output 0 writes colortex0, output 1 writes colortex2" is a format fact, not
copyrightable expression.

Retina is licensed LGPL-3.0-only as well. That is deliberate: it removes any question about
lineage, keeps the licences compatible, and costs nothing given the project ships source.

### Established from Iris's binaries

- **Package layout** confirms the shape of the problem: `shaderpack/{discovery, error,
  include, loading, materialmap, option, parsing, preprocessor, programs, properties,
  texture, transform}`, plus `uniforms/`, `targets/`, `shadows/`, `pipeline/transform/`.
- **Both draw-buffer directive forms exist and both are parsed from comments.** Iris's own
  test strings include `/*DRAWBUFFERS:321*/`, `/* RENDERTARGETS:It works */`, and a case
  labelled *"OptiFine will detect this directive, but ShadersMod will not"*. Retina parses
  both, only inside comment tokens, and prefers `RENDERTARGETS` when both appear.
- **Legacy compatibility shims are real and required.** Iris injects literal GLSL 120:
  ```
  gl_Position = ftransform();
  irs_Color = gl_Color;
  irs_texCoords[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
  ```
  which confirms that `ftransform`, `gl_Color`, `gl_MultiTexCoord*` and `gl_TextureMatrix`
  all appear in real packs and must be handled.
- **`gl_MultiTexCoord1` and `gl_MultiTexCoord2` are the same lightmap coordinate.** Retina
  maps both to one attribute at location 4; giving them separate locations leaves one unfed.
- **The `const` directive set**: `shadowMapResolution`, `shadowDistance`,
  `shadowDistanceRenderMul`, `shadowIntervalSize`, `sunPathRotation`, `ambientOcclusionLevel`,
  `superSamplingLevel`, `noiseTextureResolution`, `centerDepthHalflife`, `wetnessHalflife`,
  `drynessHalflife`, `eyeBrightnessHalflife`, `shadowHardwareFiltering`,
  `generateShadowMipmap`, `generateShadowColorMipmap`, and the per-target
  `colortex<n>{Format,Clear,ClearColor,MipmapEnabled}` family.
- **The `shaders.properties` directive set** includes `blend.*`, `alphaTest.*`,
  `backFace.{solid,cutout,cutoutMipped,translucent}`, `scale.*`, `size.buffer.*`, `flip.*`,
  `program.*`, `image.*`, `customTexture.*`, `screen*`, `profile*`, `sliders`,
  `shadow.{enabled,culling}`, `particles.ordering`, `particles.before.deferred`, `clouds`,
  `sun`, `moon`, `vignette`, `underwaterOverlay`, `oldLighting`, `oldHandLight`,
  `dynamicHandLight`, `voxelizeLightBlocks`, `shadowTerrain`, `shadowEntities`,
  `shadowBlockEntities`, `shadowLightBlockEntities`, `shadowPlayer`, `endFlashShadows`,
  `dhShadow.enabled`, `dhClouds`, `rain.depth`, `beacon.beam.depth`, `occlusion.culling`,
  `allowConcurrentCompute`, `supportsColorCorrection`, `indirect.pointer`, `SSBO`/`buffer.*`.
- **Target format tokens** observed: `R8`, `RG16F`, `RGB8`, `RGB10_A2`, `RGB16F`, `RGBA8`,
  `RGBA16`, `RGBA16F`, `RGBA32F`, `RGBA32UI`, `R11F_G11F_B10F`.
- **Sampler names and their legacy aliases**: `colortex0..15` with `gcolor/gdepth/gnormal/
  composite/gaux1..4` aliasing `colortex0..7`; `depthtex0..2`; `shadowtex0/1` with
  `shadowtex0HW`, `shadowtex0DH` variants; `shadowcolor0/1`; `noisetex`; `normals`,
  `specular`.
- **Distant Horizons integration is a distinct, version-gated axis**: `dhTerrainFramebuffer`,
  `dhShadowFramebuffer`, `dhDepthTex0/1`, `dhModelViewMatrix`, `dhProjectionMatrix`,
  `dhNearPlane`, `dhFarPlane`, `dhRenderDistance`, and `dh_*` programs.
- **Iris is still built on OpenGL.** Its `net.irisshaders.iris.gl.*` tree references
  `org.lwjgl.opengl.GL33C.glDrawBuffers` and `ARBDrawBuffersBlend`. It injects into
  `com.mojang.blaze3d.systems.RenderPassBackend` and `RenderTarget`.

**This is the clearest statement of what Retina is for.** Iris owns the OpenGL shader path.
Retina is the Vulkan one. The two contend for the same pipeline, which is why they are
mutually exclusive and why `fabric.mod.json` declares `"breaks": {"iris": "*"}`.

---

## 4. Which OpenGL shader conventions survive translation

| Convention | Vulkan status | Retina's handling |
| --- | --- | --- |
| `#version 120` / `330 compatibility` | Not accepted | Rewritten to `#version 450`; verified by compiling real 120/330/430 sources |
| `attribute` / `varying` | Removed from GLSL 140+ | Rewritten to `in`/`out` with a stage-correct direction |
| Implicit interface matching by name | Vulkan matches by location | `VaryingLayout` assigns locations deterministically from the sorted union of a program's varyings |
| `texture2D`, `texture3D`, `shadow2D`, ... | Removed | Renamed at token granularity; `shadow2D`/`shadow2DProj` are *wrapped*, not renamed, because their return type changes from `vec4` to `float` |
| `gl_FragColor`, `gl_FragData[n]` | Removed | Become explicit `layout(location=n) out vec4`, mapped through `DRAWBUFFERS`/`RENDERTARGETS` |
| `gl_ModelViewMatrix` and friends | Removed | Members of Retina's std140 block at set 0 binding 0. `gl_NormalMatrix` is a `mat3` — emitting it as a `mat4` silently corrupts every transformed normal |
| `ftransform()` | Removed | Emitted as a real function over the generated uniform block |
| `gl_VertexID` / `gl_InstanceID` | Different base | Rewritten to `gl_VertexIndex`/`gl_InstanceIndex` |
| Default-block uniforms | Illegal in Vulkan | Moved into an unnamed std140 block, so references need no change |
| Fixed-function alpha test | Does not exist | Compiled into the fragment shader as a `discard` |
| Fixed-function fog | Does not exist | Exposed as `fogMode`/`fogStart`/`fogEnd`/`fogDensity` values; **not** emulated |
| Clip-space z ∈ [−1,1], y-up | Vulkan is [0,1], y-down | Compensated with a negative-height viewport so packs see the convention they were written for |
| `gl_TextureMatrix[n]` | No equivalent | **Reported as unsupported.** It is runtime-indexed; supplying only the entries Minecraft set would silently produce wrong results for any other index |
| Dual-source blend factors (`SRC1_*`) | Needs `dualSrcBlend` | Detected and reported as a capability requirement, not downgraded |
| `SRC_ALPHA_SATURATE` as a destination factor | Illegal | Refused with a message naming the factor |
| Three-component storage-image formats | Not storage-capable | `TargetFormat.supportsImageStore()` returns false; reported rather than substituted |

Every row marked "verified" is covered by a test in
`retina-core/src/test/java/dev/retina/core/translate/TranslationCompilesTest.java`, which
compiles the translated output through shaderc and asserts on the reflected SPIR-V rather
than on expected text.

---

## 5. What the prior Sulkan experiment contributes

The brief references a prior experiment at
`C:\Users\palmj\Documents\Codex\2026-08-12\do\work\sulkan-source` and an artefact
`sulkan-0.4.0-cinematic1-26.2.jar`. **Neither was supplied to this environment**, so no audit
of it was possible and nothing from it has been carried in. Retina's mod id, package root,
resource namespace, configuration directory, cache directory, UI, and every line of source
are its own. The name appears in exactly one place: `ModConflicts` lists `sulkan` as a fatal
conflict, because a second Vulkan renderer would contend for the same device.

The artefact name contains `cinematic1`, which suggests it bundled a visual shader pack.
Retina bundles none. `shaderpacks/` is empty on a fresh install, the default is
`Shaders: Off`, and the clean-room fixtures live under `src/test/resources` where they cannot
reach a user's pack list.

---

## 6. Layering

```
dev.retina.*                       (retina-fabric — needs Minecraft)
├── RetinaPreLaunch                compatibility gate, before any render class loads
├── RetinaClient                   lazy init; no GPU work at startup
├── compat/                        conflict detection, Sodium version gate
├── vk/VulkanBackend               attaches to Minecraft's Vulkan device
├── mixin/blaze3d/                 the one accessor Retina needs
├── pipeline/PackManager           discovery, inspection, transactional swap
├── gui/                           pack list, pack options
└── config/RetinaConfig            renderer profiles; never alters pack semantics

dev.retina.core.*                  (retina-core — no Minecraft dependency at all)
├── pack/         traversal-safe pack I/O (directory, zip, memory)
├── props/        ordered .properties reader, shaders.properties model
├── option/       option discovery, values, in-place application
├── preprocess/   include expansion with exact provenance
├── translate/    lexer, translator, binding and varying layout
├── spirv/        shaderc compilation, SPIR-V reflection
├── graph/        render graph, barriers, transient aliasing
├── target/       render target directives and formats
├── state/        blend, alpha test
├── material/     block/item/entity.properties
├── uniform/      the scene-value schema and its contract
└── cache/        cache keys that refuse to omit an invalidating input
```

The split is load-bearing, not cosmetic. Everything in `retina-core` is testable without
Minecraft, a window, or a GPU, which is why 72 tests run in this environment against real
shaderc. Mixins never appear below `dev.retina.mixin`; raw Vulkan never appears above it.

---

## 7. Environment limits, stated plainly

This audit and build were produced in a Linux container with no GPU and a restricted network.
Reachability was measured, not assumed:

| Host | Needed for | Status |
| --- | --- | --- |
| `repo1.maven.org` | LWJGL, JOML, fastutil, Gson, JUnit | **reachable** |
| `plugins.gradle.org` | Gradle plugins | **reachable** |
| `maven.fabricmc.net` | Fabric Loom, Fabric Loader, Fabric API | **blocked** (proxy 403) |
| `libraries.minecraft.net` | Minecraft libraries | **blocked** |
| `piston-meta.mojang.com` | Minecraft version manifest and jar | **blocked** |
| `repo.spongepowered.org` | Mixin | **blocked** |
| `api.modrinth.com` | Sodium artefact | **blocked** |

What follows from that, precisely:

- **`retina-core` is built and tested here.** 72 tests pass, including compiling real
  GLSL 120/330/430 pack sources through shaderc to SPIR-V and reflecting the modules.
- **`retina-fabric` has never been compiled.** It cannot be, without Minecraft 26.2, Fabric
  Loader, and Loom. Its Blaze3D and Sodium call sites were written against symbols read out
  of the supplied jars — which is much better than guessing, and is still not a compile.
- **Nothing has been run inside Minecraft.** There are no launch results, no frame captures,
  no validation-layer output, and no benchmarks. `docs/BENCHMARKS.md` says so rather than
  containing numbers.

The gates from the brief map onto this as follows:

| Gate | Status |
| --- | --- |
| 1. Audit and independent identity | **Done** — this document; independent packages, ids, resources; `retina-core` builds clean |
| 2. Native Vulkan bootstrap | **Redesigned and unverified** — attaching to Minecraft's Vulkan device is the correct architecture for 26.2, and it has not been run |
| 3. Vanilla-equivalent Sodium path | **Not implemented** — requires compiling against Sodium and Minecraft |
| 4. Pack runtime foundation | **Largely done and tested** — discovery, safe loading, settings, preprocessor, translation, SPIR-V validation and reflection all work and are covered by tests. The transactional swap is designed, not exercised |
| 5. Core pack pipeline | **Partially done** — programs, targets, states, uniforms, mappings and dimensions are modelled and tested; nothing has been bound to a live device |
| 6. Compatibility depth | **Partially done** — see `SHADER_COMPATIBILITY_MATRIX.md`, which marks each feature honestly |
| 7. Optimisation | **Not measured** — the design decisions are made and documented; no profiling has been done, so no performance claim is made |
| 8. Stability and release | **Not reached** — no Prism install test, no lifecycle matrix, no benchmarks |
