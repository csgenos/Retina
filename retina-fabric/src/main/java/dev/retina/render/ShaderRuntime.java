/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.retina.config.RetinaConfig;
import dev.retina.mixin.blaze3d.RenderTargetColorAccessor;
import dev.retina.pipeline.PackManager;
import dev.retina.pipeline.PreparedTerrainPack;
import dev.retina.pipeline.TerrainPackCompiler;
import dev.retina.vk.VulkanBackend;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Owns asynchronous pack compilation and the atomic render-thread pipeline swap. */
public final class ShaderRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("Retina/Runtime");
    private static final BindGroupLayout RETINA_UNIFORMS = BindGroupLayout.builder()
        .withUniform("RetinaUniforms", UniformType.UNIFORM_BUFFER)
        .build();
    private static final BindGroupLayout UNDERWATER_SAMPLERS = BindGroupLayout.builder()
        .withSampler("SceneColor")
        .withSampler("SceneDepth")
        .build();
    private static final ShaderRuntime INSTANCE = new ShaderRuntime();

    public enum State {
        OFF, COMPILING, ACTIVE, FAILED, WRONG_BACKEND
    }

    public record Status(State state, String pack, String detail) {
    }

    private record ActivePack(PreparedTerrainPack prepared,
                              Map<PreparedTerrainPack.PassKind, RenderPipeline> pipelines,
                              RenderPipeline entityPipeline,
                              RenderPipeline opaqueParticlePipeline,
                              RenderPipeline translucentParticlePipeline,
                              RenderPipeline weatherDepthWritePipeline,
                              RenderPipeline weatherNoDepthWritePipeline,
                              RenderPipeline underwaterPipeline,
                              RenderPipeline shadowPipeline,
                              RenderPipeline entityShadowPipeline,
                              Map<PreparedTerrainPack.PostProgram, RenderPipeline> postPipelines,
                              Map<RenderPipeline, ShaderSource> shaderSources,
                              DynamicUniformStorage<TerrainFrameUniform> uniformStorage,
                              ColortexFramebuffer framebuffer,
                              ShadowFramebuffer shadowFramebuffer) {
    }

    private record RetiredResources(DynamicUniformStorage<TerrainFrameUniform> storage,
                                    ColortexFramebuffer framebuffer,
                                    ShadowFramebuffer shadowFramebuffer, int framesLeft) {
        RetiredResources age() {
            return new RetiredResources(storage, framebuffer, shadowFramebuffer, framesLeft - 1);
        }

        void close() {
            storage.close();
            if (framebuffer != null) {
                framebuffer.close();
            }
            if (shadowFramebuffer != null) {
                shadowFramebuffer.close();
            }
        }
    }

    private record ShadowView(Matrix4f projection, Matrix4f modelView) {
    }

    /** Original main-target attachments retained while LevelRenderer writes the Retina scene. */
    private record MainColorRedirect(com.mojang.blaze3d.pipeline.RenderTarget target,
                                     GpuTexture texture, GpuTextureView view) {
    }

    private static final class TerrainInvocation {
        ChunkRenderMatrices matrices;
        CameraTransform camera;
        FogParameters fog;
        GpuSampler sampler;
        final Map<PreparedTerrainPack.PassKind, TerrainRenderPass> passes =
            new EnumMap<>(PreparedTerrainPack.PassKind.class);
    }

    /** Mutable command state mirrored from the narrow vanilla entity ABI. */
    private static final class EntityDrawState {
        RenderPipeline pipeline;
        final Map<String, GpuBufferSlice> uniforms = new HashMap<>();
        final Map<String, TextureBinding> textures = new HashMap<>();
        GpuBufferSlice vertexBuffer;
        GpuBuffer indexBuffer;
        IndexType indexType;
    }

    private record TextureBinding(GpuTextureView view, GpuSampler sampler) {
    }

    private record IndexedEntityDraw(EntityDrawState state, int indexCount, int instanceCount,
                                     int firstIndex, int vertexOffset, int firstInstance) {
    }

    private final AtomicLong generation = new AtomicLong();
    private final ArrayDeque<RetiredResources> retiredResources = new ArrayDeque<>();
    private final ExecutorService compilerExecutor;
    private volatile PackManager packManager;
    private volatile ActivePack active;
    private volatile Status status = new Status(State.OFF, "", "Shaders are off");
    private int frameCounter;
    private long startNanos = System.nanoTime();
    private long previousUniformNanos = startNanos;
    private Matrix4f previousModelView = new Matrix4f();
    private Matrix4f previousProjection = new Matrix4f();
    private Matrix4f currentModelView = new Matrix4f();
    private Matrix4f currentProjection = new Matrix4f();
    private GpuBufferSlice frameUniforms;
    private GpuBufferSlice shadowUniforms;
    private TerrainInvocation terrainInvocation;
    private ShadowView shadowView;
    private MainColorRedirect mainColorRedirect;
    private boolean shadowRendering;
    private boolean prepareRendered;
    private boolean shadowCompRendered;
    private FogType cameraMedium = FogType.NONE;
    private boolean loggedTerrainMrt;
    private boolean loggedPostChain;
    private boolean loggedShadowPass;
    private boolean loggedSceneRouting;
    private boolean loggedSceneFallback;
    private boolean loggedEntityRouting;
    private boolean loggedEntityShadows;
    private final Map<RenderPass, EntityDrawState> entityPasses = new IdentityHashMap<>();
    private final List<IndexedEntityDraw> entityShadowDraws = new ArrayList<>();

    /**
     * Whether any pack is active that needs vanilla draw state mirrored.
     *
     * <p>{@code RenderPassMixin} injects into six methods that run on every draw call in the
     * game, Retina's and vanilla's alike. Reading one static field and returning is the
     * difference between an inlined no-op and six virtual calls plus six map probes per draw
     * when no pack is loaded, which is the default state.
     */
    private static volatile boolean capturingDrawState;

    /**
     * Memoised pipeline substitutions for the active pack.
     *
     * <p>Resolving a substitution means reading the pipeline's resource location and comparing
     * strings. Vanilla's {@code RenderPipeline} objects are singletons, so the answer only has
     * to be computed once each; a pipeline with no substitution maps to itself rather than
     * being absent, so a miss is never re-resolved. Touched only from the render thread, which
     * is where both {@code setPipeline} and the pack swap run.
     */
    private final Map<RenderPipeline, RenderPipeline> sceneSubstitutions = new IdentityHashMap<>();

    private ShaderRuntime() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Retina shader compiler");
            thread.setDaemon(true);
            return thread;
        };
        this.compilerExecutor = Executors.newSingleThreadExecutor(factory);
    }

    public static ShaderRuntime get() {
        return INSTANCE;
    }

    public void initialize(PackManager manager, RetinaConfig config) {
        this.packManager = manager;
        request(config);
    }

    /** Starts a new transaction; an older working pack remains active until commit succeeds. */
    public void request(RetinaConfig config) {
        long requestedGeneration = generation.incrementAndGet();
        if (!config.shadersEnabled()) {
            status = new Status(State.OFF, "", "Shaders are off");
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
                minecraft.execute(() -> deactivate(requestedGeneration));
            } else {
                active = null;
            }
            return;
        }
        PackManager manager = packManager;
        if (manager == null) {
            status = new Status(State.FAILED, config.selectedPack(),
                "Retina did not finish initialising");
            return;
        }

        status = new Status(State.COMPILING, config.selectedPack(),
            "Preprocessing and validating shaders");
        compilerExecutor.execute(() -> {
            try {
                PreparedTerrainPack prepared = new TerrainPackCompiler().compile(manager, config);
                if (generation.get() != requestedGeneration) {
                    return;
                }
                Minecraft.getInstance().execute(() -> commit(requestedGeneration, prepared));
            } catch (TerrainPackCompiler.CompilationException | RuntimeException e) {
                fail(requestedGeneration, config.selectedPack(), usefulMessage(e), e);
            }
        });
    }

    private void commit(long requestedGeneration, PreparedTerrainPack prepared) {
        if (generation.get() != requestedGeneration) {
            return;
        }
        VulkanBackend.Attachment attachment = VulkanBackend.attach();
        if (!(attachment instanceof VulkanBackend.Attachment.Attached)) {
            String detail = attachment instanceof VulkanBackend.Attachment.WrongBackend wrong
                ? wrong.message() : ((VulkanBackend.Attachment.Unknown)attachment).detail();
            status = new Status(State.WRONG_BACKEND, prepared.name(), detail);
            return;
        }

        try {
            Map<PreparedTerrainPack.PassKind, RenderPipeline> pipelines =
                new EnumMap<>(PreparedTerrainPack.PassKind.class);
            Map<PreparedTerrainPack.PostProgram, RenderPipeline> postPipelines =
                new java.util.LinkedHashMap<>();
            Map<RenderPipeline, ShaderSource> sources = new IdentityHashMap<>();
            com.mojang.blaze3d.pipeline.RenderTarget mainTarget =
                Minecraft.getInstance().gameRenderer.mainRenderTarget();
            for (Map.Entry<PreparedTerrainPack.PassKind, PreparedTerrainPack.Program> entry
                : prepared.programs().entrySet()) {
                RenderPipeline pipeline = buildPipeline(prepared, entry.getKey(),
                    entry.getValue(), RetinaChunkVertexType.VERTEX_FORMAT,
                    mainTarget.getColorTexture().getFormat());
                ShaderSource source = shaderSource(pipeline, entry.getValue());
                CompiledRenderPipeline compiled = RenderSystem.getDevice()
                    .precompilePipeline(pipeline, source);
                if (!compiled.isValid()) {
                    throw new IllegalStateException("Vulkan rejected "
                        + entry.getValue().sourceName());
                }
                pipelines.put(entry.getKey(), pipeline);
                sources.put(pipeline, source);
            }
            RenderPipeline shadowPipeline = null;
            if (prepared.shadowProgram() != null) {
                shadowPipeline = buildShadowPipeline(prepared, prepared.shadowProgram());
                ShaderSource source = shaderSource(shadowPipeline, prepared.shadowProgram());
                precompile(prepared.shadowProgram().sourceName(), shadowPipeline, source);
                sources.put(shadowPipeline, source);
            }
            RenderPipeline entityPipeline = null;
            if (prepared.entityProgram() != null) {
                entityPipeline = buildEntityPipeline(prepared, prepared.entityProgram());
                ShaderSource source = shaderSource(entityPipeline, prepared.entityProgram());
                precompile(prepared.entityProgram().sourceName(), entityPipeline, source);
                sources.put(entityPipeline, source);
            }
            RenderPipeline opaqueParticlePipeline = null;
            RenderPipeline translucentParticlePipeline = null;
            if (prepared.particleProgram() != null) {
                opaqueParticlePipeline = buildParticlePipeline(prepared, prepared.particleProgram(), false);
                translucentParticlePipeline = buildParticlePipeline(prepared, prepared.particleProgram(), true);
                ShaderSource opaqueSource = shaderSource(opaqueParticlePipeline, prepared.particleProgram());
                ShaderSource translucentSource = shaderSource(translucentParticlePipeline, prepared.particleProgram());
                precompile(prepared.particleProgram().sourceName() + " opaque", opaqueParticlePipeline,
                    opaqueSource);
                precompile(prepared.particleProgram().sourceName() + " translucent",
                    translucentParticlePipeline, translucentSource);
                sources.put(opaqueParticlePipeline, opaqueSource);
                sources.put(translucentParticlePipeline, translucentSource);
            }
            RenderPipeline weatherDepthWritePipeline = null;
            RenderPipeline weatherNoDepthWritePipeline = null;
            if (prepared.weatherProgram() != null) {
                weatherDepthWritePipeline = buildWeatherPipeline(prepared, prepared.weatherProgram(), true);
                weatherNoDepthWritePipeline = buildWeatherPipeline(prepared, prepared.weatherProgram(), false);
                ShaderSource depthWriteSource = shaderSource(weatherDepthWritePipeline,
                    prepared.weatherProgram());
                ShaderSource noDepthWriteSource = shaderSource(weatherNoDepthWritePipeline,
                    prepared.weatherProgram());
                precompile(prepared.weatherProgram().sourceName() + " depth write",
                    weatherDepthWritePipeline, depthWriteSource);
                precompile(prepared.weatherProgram().sourceName() + " no depth write",
                    weatherNoDepthWritePipeline, noDepthWriteSource);
                sources.put(weatherDepthWritePipeline, depthWriteSource);
                sources.put(weatherNoDepthWritePipeline, noDepthWriteSource);
            }
            RenderPipeline entityShadowPipeline = null;
            if (prepared.shadowProgram() != null && prepared.entityProgram() != null) {
                entityShadowPipeline = buildEntityShadowPipeline(prepared, prepared.shadowProgram());
                ShaderSource source = entityShadowShaderSource(entityShadowPipeline, prepared);
                precompile("entity shadow", entityShadowPipeline, source);
                sources.put(entityShadowPipeline, source);
            }
            RenderPipeline underwaterPipeline = buildUnderwaterPipeline(prepared);
            ShaderSource underwaterSource = underwaterShaderSource(underwaterPipeline);
            precompile("underwater composition", underwaterPipeline, underwaterSource);
            sources.put(underwaterPipeline, underwaterSource);
            for (PreparedTerrainPack.PostProgram prepare : prepared.preparePrograms()) {
                RenderPipeline pipeline = buildPostPipeline(prepared, prepare, false,
                    mainTarget.getColorTexture().getFormat());
                ShaderSource source = shaderSource(pipeline, prepare);
                precompile(prepare.sourceName(), pipeline, source);
                postPipelines.put(prepare, pipeline);
                sources.put(pipeline, source);
            }
            for (PreparedTerrainPack.PostProgram shadowComp : prepared.shadowCompPrograms()) {
                RenderPipeline pipeline = buildPostPipeline(prepared, shadowComp, false,
                    mainTarget.getColorTexture().getFormat());
                ShaderSource source = shaderSource(pipeline, shadowComp);
                precompile(shadowComp.sourceName(), pipeline, source);
                postPipelines.put(shadowComp, pipeline);
                sources.put(pipeline, source);
            }
            for (PreparedTerrainPack.PostProgram deferred : prepared.deferredPrograms()) {
                RenderPipeline pipeline = buildPostPipeline(prepared, deferred, false,
                    mainTarget.getColorTexture().getFormat());
                ShaderSource source = shaderSource(pipeline, deferred);
                precompile(deferred.sourceName(), pipeline, source);
                postPipelines.put(deferred, pipeline);
                sources.put(pipeline, source);
            }
            for (PreparedTerrainPack.PostProgram post : prepared.compositePrograms()) {
                RenderPipeline pipeline = buildPostPipeline(prepared, post, false,
                    mainTarget.getColorTexture().getFormat());
                ShaderSource source = shaderSource(pipeline, post);
                precompile(post.sourceName(), pipeline, source);
                postPipelines.put(post, pipeline);
                sources.put(pipeline, source);
            }
            if (prepared.finalProgram() != null) {
                PreparedTerrainPack.PostProgram post = prepared.finalProgram();
                RenderPipeline pipeline = buildPostPipeline(prepared, post, true,
                    mainTarget.getColorTexture().getFormat());
                ShaderSource source = shaderSource(pipeline, post);
                precompile(post.sourceName(), pipeline, source);
                postPipelines.put(post, pipeline);
                sources.put(pipeline, source);
            }

            DynamicUniformStorage<TerrainFrameUniform> storage = new DynamicUniformStorage<>(
                "Retina terrain uniforms", prepared.uniforms().size(), 8);
            ColortexFramebuffer framebuffer = prepared.usesOffscreenTargets()
                ? new ColortexFramebuffer(prepared.targets(), mainTarget.width, mainTarget.height)
                : null;
            ShadowFramebuffer shadowFramebuffer = prepared.shadowProgram() == null ? null
                : new ShadowFramebuffer(prepared.shadowProgram());
            ActivePack previous = active;
            active = new ActivePack(prepared, Map.copyOf(pipelines), entityPipeline,
                opaqueParticlePipeline, translucentParticlePipeline,
                weatherDepthWritePipeline, weatherNoDepthWritePipeline,
                underwaterPipeline,
                shadowPipeline, entityShadowPipeline, Map.copyOf(postPipelines), Map.copyOf(sources), storage,
                framebuffer, shadowFramebuffer);
            // Both caches describe the pack that was active a moment ago.
            sceneSubstitutions.clear();
            capturingDrawState = entityShadowPipeline != null;
            if (previous != null) {
                // Keep buffers alive for several rotations so already-submitted command
                // buffers cannot observe a destroyed allocation during a live pack switch.
                retiredResources.addLast(new RetiredResources(previous.uniformStorage(),
                    previous.framebuffer(), previous.shadowFramebuffer(), 4));
            }
            frameCounter = 0;
            startNanos = System.nanoTime();
            previousUniformNanos = startNanos;
            previousModelView.identity();
            previousProjection.identity();
            frameUniforms = null;
            shadowUniforms = null;
            terrainInvocation = null;
            shadowView = null;
            mainColorRedirect = null;
            shadowRendering = false;
            prepareRendered = false;
            shadowCompRendered = false;
            loggedTerrainMrt = false;
            loggedPostChain = false;
            loggedShadowPass = false;
            loggedSceneRouting = false;
            loggedSceneFallback = false;
            loggedEntityRouting = false;
            loggedEntityShadows = false;
            entityPasses.clear();
            entityShadowDraws.clear();
            String detail = prepared.usesOffscreenTargets()
                ? "Vulkan terrain MRT + composite/final active"
                : "Vulkan terrain pipelines active";
            status = new Status(State.ACTIVE, prepared.name(), detail);
            LOGGER.info("Activated shader pack {} ({} terrain, entity={}, particles={}, weather={}, shadow={}, {} prepare, {} shadowcomp, {} deferred, {} composite/final pipelines, {} targets,"
                    + " {} diagnostics)", prepared.name(), pipelines.size(),
                entityPipeline != null, opaqueParticlePipeline != null,
                weatherDepthWritePipeline != null, shadowPipeline != null,
                prepared.preparePrograms().size(), prepared.shadowCompPrograms().size(),
                prepared.deferredPrograms().size(), postPipelines.size(), prepared.targets().size(),
                prepared.diagnostics().size());
        } catch (RuntimeException e) {
            fail(requestedGeneration, prepared.name(), usefulMessage(e), e);
        }
    }

    private static RenderPipeline buildPipeline(PreparedTerrainPack pack,
                                                PreparedTerrainPack.PassKind kind,
                                                PreparedTerrainPack.Program program,
                                                VertexFormat vertexFormat,
                                                com.mojang.blaze3d.GpuFormat mainFormat) {
        String suffix = pack.contentHash().substring(0,
            Math.min(12, pack.contentHash().length())) + "/" + kind.name().toLowerCase();
        Identifier shader = Identifier.fromNamespaceAndPath("retina", suffix);
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withBindGroupLayout(ShaderChunkRenderer.BIND_GROUP)
            .withBindGroupLayout(RETINA_UNIFORMS)
            // Sodium adds the exact twenty-byte push-constant range to namespaces that
            // contain "sodium"; Retina uses the same DrawContext ABI.
            .withLocation(Identifier.fromNamespaceAndPath("retina_sodium", suffix))
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withCull(program.cull())
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withVertexBinding(0, vertexFormat);
        for (int location = 0; location < program.drawTargets().size(); location++) {
            int target = program.drawTargets().get(location);
            com.mojang.blaze3d.GpuFormat format = pack.usesOffscreenTargets()
                ? ColortexFramebuffer.gpuFormat(pack.targets().get(target).settings().format())
                : mainFormat;
            builder.withColorTargetState(location, new ColorTargetState(
                kind == PreparedTerrainPack.PassKind.TRANSLUCENT
                    ? Optional.of(BlendFunction.TRANSLUCENT) : Optional.empty(), format, -1));
        }
        return builder.build();
    }

    private static RenderPipeline buildPostPipeline(PreparedTerrainPack pack,
                                                    PreparedTerrainPack.PostProgram program,
                                                    boolean finalPass,
                                                    com.mojang.blaze3d.GpuFormat mainFormat) {
        String suffix = pack.contentHash().substring(0,
            Math.min(12, pack.contentHash().length())) + "/post/" + program.sourceName();
        Identifier shader = Identifier.fromNamespaceAndPath("retina", suffix);
        BindGroupLayout.Builder samplers = BindGroupLayout.builder();
        program.samplers().forEach(samplers::withSampler);
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withBindGroupLayout(samplers.build())
            .withBindGroupLayout(RETINA_UNIFORMS)
            .withLocation(Identifier.fromNamespaceAndPath("retina", suffix))
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (finalPass) {
            builder.withColorTargetState(new ColorTargetState(Optional.empty(), mainFormat, -1));
        } else {
            for (int location = 0; location < program.drawTargets().size(); location++) {
                int target = program.drawTargets().get(location);
                builder.withColorTargetState(location, new ColorTargetState(Optional.empty(),
                    ColortexFramebuffer.gpuFormat(
                        pack.targets().get(target).settings().format()), -1));
            }
        }
        return builder.build();
    }

    /** Renderer-owned underwater medium pass; packs remain free to add later stylisation. */
    private static RenderPipeline buildUnderwaterPipeline(PreparedTerrainPack pack) {
        String suffix = pack.contentHash().substring(0,
            Math.min(12, pack.contentHash().length())) + "/underwater-medium";
        Identifier shader = Identifier.fromNamespaceAndPath("retina", suffix);
        return RenderPipeline.builder()
            .withBindGroupLayout(UNDERWATER_SAMPLERS)
            .withLocation(Identifier.fromNamespaceAndPath("retina", suffix))
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(new ColorTargetState(Optional.empty(),
                com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, -1))
            .build();
    }

    private static RenderPipeline buildShadowPipeline(PreparedTerrainPack pack,
                                                       PreparedTerrainPack.ShadowProgram program) {
        String suffix = pack.contentHash().substring(0,
            Math.min(12, pack.contentHash().length())) + "/shadow";
        Identifier shader = Identifier.fromNamespaceAndPath("retina", suffix);
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withBindGroupLayout(ShaderChunkRenderer.BIND_GROUP)
            .withBindGroupLayout(RETINA_UNIFORMS)
            .withLocation(Identifier.fromNamespaceAndPath("retina_sodium", suffix))
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withCull(program.cull())
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withVertexBinding(0, RetinaChunkVertexType.VERTEX_FORMAT);
        for (int location = 0; location < program.drawTargets().size(); location++) {
            int target = program.drawTargets().get(location);
            builder.withColorTargetState(location, new ColorTargetState(Optional.empty(),
                ColortexFramebuffer.gpuFormat(program.colorTargets().get(target).format()), -1));
        }
        return builder.build();
    }

    /** Shadow-only version of Minecraft's stable entity vertex ABI. */
    private static RenderPipeline buildEntityShadowPipeline(PreparedTerrainPack pack,
                                                             PreparedTerrainPack.ShadowProgram program) {
        String suffix = pack.contentHash().substring(0,
            Math.min(12, pack.contentHash().length())) + "/entity-shadow";
        Identifier shader = Identifier.fromNamespaceAndPath("retina", suffix);
        RenderPipeline.Builder builder = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.LIGHTING)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withBindGroupLayout(RETINA_UNIFORMS)
            .withLocation(Identifier.fromNamespaceAndPath("retina", suffix))
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withCull(program.cull())
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withVertexBinding(0, DefaultVertexFormat.ENTITY);
        for (int location = 0; location < program.drawTargets().size(); location++) {
            int target = program.drawTargets().get(location);
            builder.withColorTargetState(location, new ColorTargetState(Optional.empty(),
                ColortexFramebuffer.gpuFormat(program.colorTargets().get(target).format()), -1));
        }
        return builder.build();
    }

    /**
     * A deliberately narrow replacement for Minecraft's normal entity family. The bind-group
     * order mirrors Minecraft's built-in entity snippet, so vanilla's entity renderer keeps
     * supplying per-draw matrices, fog, lighting, atlas, overlay and lightmap resources.
     */
    private static RenderPipeline buildEntityPipeline(PreparedTerrainPack pack,
                                                       PreparedTerrainPack.EntityProgram program) {
        String suffix = pack.contentHash().substring(0,
            Math.min(12, pack.contentHash().length())) + "/entities";
        Identifier shader = Identifier.fromNamespaceAndPath("retina", suffix);
        return RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.LIGHTING)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withLocation(Identifier.fromNamespaceAndPath("retina", suffix))
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withCull(program.cull())
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withColorTargetState(new ColorTargetState(Optional.empty(),
                com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, -1))
            .build();
    }

    /** Standard Minecraft particle quad ABI, with the vanilla opaque/translucent split retained. */
    private static RenderPipeline buildParticlePipeline(PreparedTerrainPack pack,
        PreparedTerrainPack.ParticleProgram program, boolean translucent) {
        String suffix = pack.contentHash().substring(0,
            Math.min(12, pack.contentHash().length())) + "/particles/"
            + (translucent ? "translucent" : "opaque");
        Identifier shader = Identifier.fromNamespaceAndPath("retina", suffix);
        return RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withLocation(Identifier.fromNamespaceAndPath("retina", suffix))
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withCull(program.cull())
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
            .withColorTargetState(new ColorTargetState(translucent
                ? Optional.of(BlendFunction.TRANSLUCENT) : Optional.empty(),
                com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, -1))
            .build();
    }

    /** Weather uses the particle vertex ABI but preserves Minecraft's two depth-write modes. */
    private static RenderPipeline buildWeatherPipeline(PreparedTerrainPack pack,
        PreparedTerrainPack.WeatherProgram program, boolean depthWrite) {
        String suffix = pack.contentHash().substring(0,
            Math.min(12, pack.contentHash().length())) + "/weather/"
            + (depthWrite ? "depth-write" : "no-depth-write");
        Identifier shader = Identifier.fromNamespaceAndPath("retina", suffix);
        return RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withLocation(Identifier.fromNamespaceAndPath("retina", suffix))
            .withVertexShader(shader)
            .withFragmentShader(shader)
            .withCull(program.cull())
            .withDepthStencilState(depthWrite ? DepthStencilState.DEFAULT
                : new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT),
                com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, -1))
            .build();
    }

    private static void precompile(String name, RenderPipeline pipeline, ShaderSource source) {
        CompiledRenderPipeline compiled = RenderSystem.getDevice()
            .precompilePipeline(pipeline, source);
        if (!compiled.isValid()) {
            throw new IllegalStateException("Vulkan rejected " + name);
        }
    }

    private static ShaderSource shaderSource(RenderPipeline pipeline,
                                             PreparedTerrainPack.Program program) {
        return (id, type) -> {
            if (!id.equals(pipeline.getVertexShader()) && !id.equals(pipeline.getFragmentShader())) {
                return null;
            }
            return type == ShaderType.VERTEX ? program.vertexSource() : program.fragmentSource();
        };
    }

    private static ShaderSource underwaterShaderSource(RenderPipeline pipeline) {
        String vertex = """
            #version 450
            const vec2 POSITIONS[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
            layout(location = 0) out vec2 uv;
            void main() {
                vec2 position = POSITIONS[gl_VertexIndex];
                gl_Position = vec4(position, 0.0, 1.0);
                uv = position * 0.5 + 0.5;
            }
            """;
        String fragment = """
            #version 450
            layout(binding = 0) uniform sampler2D SceneColor;
            layout(binding = 1) uniform sampler2D SceneDepth;
            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 fragColor;
            void main() {
                vec4 scene = texture(SceneColor, uv);
                // Minecraft's world depth is reversed-Z: distant visible geometry approaches 0.
                float depth = clamp(texture(SceneDepth, uv).r, 0.0, 1.0);
                float distanceFog = pow(1.0 - depth, 0.22);
                float medium = smoothstep(0.08, 0.76, distanceFog);
                vec3 waterMedium = vec3(0.025, 0.115, 0.285);
                scene.rgb = mix(scene.rgb, waterMedium, medium * 0.82);
                fragColor = scene;
            }
            """;
        return (id, type) -> {
            if (!id.equals(pipeline.getVertexShader()) && !id.equals(pipeline.getFragmentShader())) {
                return null;
            }
            return type == ShaderType.VERTEX ? vertex : fragment;
        };
    }

    private static ShaderSource shaderSource(RenderPipeline pipeline,
                                             PreparedTerrainPack.PostProgram program) {
        return (id, type) -> {
            if (!id.equals(pipeline.getVertexShader()) && !id.equals(pipeline.getFragmentShader())) {
                return null;
            }
            return type == ShaderType.VERTEX ? program.vertexSource() : program.fragmentSource();
        };
    }

    private static ShaderSource shaderSource(RenderPipeline pipeline,
                                             PreparedTerrainPack.ShadowProgram program) {
        return (id, type) -> {
            if (!id.equals(pipeline.getVertexShader()) && !id.equals(pipeline.getFragmentShader())) {
                return null;
            }
            return type == ShaderType.VERTEX ? program.vertexSource() : program.fragmentSource();
        };
    }

    private static ShaderSource shaderSource(RenderPipeline pipeline,
                                             PreparedTerrainPack.EntityProgram program) {
        return (id, type) -> {
            if (!id.equals(pipeline.getVertexShader()) && !id.equals(pipeline.getFragmentShader())) {
                return null;
            }
            return type == ShaderType.VERTEX ? program.vertexSource() : program.fragmentSource();
        };
    }

    private static ShaderSource shaderSource(RenderPipeline pipeline,
                                             PreparedTerrainPack.ParticleProgram program) {
        return (id, type) -> {
            if (!id.equals(pipeline.getVertexShader()) && !id.equals(pipeline.getFragmentShader())) {
                return null;
            }
            return type == ShaderType.VERTEX ? program.vertexSource() : program.fragmentSource();
        };
    }

    private static ShaderSource shaderSource(RenderPipeline pipeline,
                                             PreparedTerrainPack.WeatherProgram program) {
        return (id, type) -> {
            if (!id.equals(pipeline.getVertexShader()) && !id.equals(pipeline.getFragmentShader())) {
                return null;
            }
            return type == ShaderType.VERTEX ? program.vertexSource() : program.fragmentSource();
        };
    }

    /** Minimal alpha-tested entity caster using the pack's generated shadow matrices. */
    private static ShaderSource entityShadowShaderSource(RenderPipeline pipeline,
                                                         PreparedTerrainPack pack) {
        String vertex = """
            #version 450
            layout(std140, binding = 0) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
            };
            layout(std140, binding = 7) uniform RetinaUniforms {
            """ + pack.uniforms().glslMembers() + """
            };
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            layout(location = 2) in vec2 UV0;
            layout(location = 0) out vec2 retina_uv;
            layout(location = 1) out vec4 retina_color;
            void main() {
                gl_Position = shadowProjection * shadowModelView * vec4(Position + ModelOffset, 1.0);
                retina_uv = UV0;
                retina_color = Color * ColorModulator;
            }
            """;
        String fragment = """
            #version 450
            layout(binding = 4) uniform sampler2D Sampler0;
            layout(location = 0) in vec2 retina_uv;
            layout(location = 1) in vec4 retina_color;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = texture(Sampler0, retina_uv) * retina_color;
                if (fragColor.a < 0.1) discard;
            }
            """;
        return (id, type) -> {
            if (!id.equals(pipeline.getVertexShader()) && !id.equals(pipeline.getFragmentShader())) {
                return null;
            }
            return type == ShaderType.VERTEX ? vertex : fragment;
        };
    }

    private void deactivate(long requestedGeneration) {
        if (generation.get() != requestedGeneration) {
            return;
        }
        restoreMainColorTarget();
        ActivePack previous = active;
        active = null;
        sceneSubstitutions.clear();
        capturingDrawState = false;
        frameUniforms = null;
        if (previous != null) {
            retiredResources.addLast(new RetiredResources(previous.uniformStorage(),
                previous.framebuffer(), previous.shadowFramebuffer(), 4));
        }
    }

    private void fail(long requestedGeneration, String pack, String detail, Throwable failure) {
        if (generation.get() != requestedGeneration) {
            return;
        }
        status = new Status(State.FAILED, pack, detail);
        LOGGER.error("Shader pack {} was not activated; the previous pipeline remains active: {}",
            pack, detail, failure);
    }

    public RenderPipeline pipelineFor(TerrainRenderPass pass) {
        ActivePack current = active;
        if (current == null) {
            return null;
        }
        if (shadowRendering && current.shadowPipeline() != null) {
            return current.shadowPipeline();
        }
        PreparedTerrainPack.PassKind kind = pass.isTranslucent()
            ? PreparedTerrainPack.PassKind.TRANSLUCENT
            : pass.supportsFragmentDiscard() ? PreparedTerrainPack.PassKind.CUTOUT
                : PreparedTerrainPack.PassKind.SOLID;
        return current.pipelines().get(kind);
    }

    /** Replaces only dedicated, ABI-validated scene stages while colortex0 owns the scene. */
    public RenderPipeline scenePipelineFor(RenderPipeline original) {
        ActivePack current = active;
        if (current == null || mainColorRedirect == null) {
            return original;
        }
        RenderPipeline cached = sceneSubstitutions.get(original);
        if (cached != null) {
            return cached;
        }
        RenderPipeline resolved = resolveScenePipeline(current, original);
        sceneSubstitutions.put(original, resolved);
        return resolved;
    }

    /** Works out the substitution for one pipeline; called once per pipeline per pack. */
    private RenderPipeline resolveScenePipeline(ActivePack current, RenderPipeline original) {
        if (original.getPrimitiveTopology() != PrimitiveTopology.QUADS) {
            return original;
        }
        String path = original.getLocation().getPath();
        if (original.getVertexFormatBinding(0) == DefaultVertexFormat.PARTICLE) {
            if (path.equals("pipeline/weather_depth_write") && current.weatherDepthWritePipeline() != null) {
                return current.weatherDepthWritePipeline();
            }
            if (path.equals("pipeline/weather_no_depth_write")
                && current.weatherNoDepthWritePipeline() != null) {
                return current.weatherNoDepthWritePipeline();
            }
            if (path.equals("pipeline/opaque_particle") && current.opaqueParticlePipeline() != null) {
                return current.opaqueParticlePipeline();
            }
            if (path.equals("pipeline/translucent_particle") && current.translucentParticlePipeline() != null) {
                return current.translucentParticlePipeline();
            }
            return original;
        }
        if (current.entityPipeline() == null || original.getVertexFormatBinding(0)
            != DefaultVertexFormat.ENTITY) {
            return original;
        }
        // Vanilla's projected entity-shadow decal is an alpha-blended screen-space effect. It
        // is not an entity mesh and must keep its own translucent pipeline; routing it through
        // gbuffers_entities makes the decal an opaque black disk.
        if (!path.startsWith("pipeline/entity_") || path.equals("pipeline/entity_shadow")) {
            return original;
        }
        if (!loggedEntityRouting) {
            loggedEntityRouting = true;
            LOGGER.info("Routing {} through gbuffers_entities", original.getLocation());
        }
        return current.entityPipeline();
    }

    /**
     * Whether vanilla draw state currently needs mirroring.
     *
     * <p>The per-draw hooks call this before anything else. When no pack casts entity shadows —
     * which includes every session with shaders off — the whole capture path folds away.
     */
    public static boolean isCapturingDrawState() {
        return capturingDrawState;
    }

    /** Mirrors the resources of an eligible entity pass until its indexed draws are issued. */
    public void trackEntityPipeline(RenderPass pass, RenderPipeline pipeline) {
        ActivePack current = active;
        if (current == null || current.entityShadowPipeline() == null || shadowRendering
            || !isEntityPipeline(pipeline)) {
            entityPasses.remove(pass);
            return;
        }
        EntityDrawState state = new EntityDrawState();
        state.pipeline = pipeline;
        entityPasses.put(pass, state);
    }

    public void trackEntityUniform(RenderPass pass, String name, GpuBufferSlice value) {
        EntityDrawState state = entityPasses.get(pass);
        if (state != null) state.uniforms.put(name, value);
    }

    public void trackEntityTexture(RenderPass pass, String name, GpuTextureView view,
                                   GpuSampler sampler) {
        EntityDrawState state = entityPasses.get(pass);
        if (state != null) state.textures.put(name, new TextureBinding(view, sampler));
    }

    public void trackEntityVertexBuffer(RenderPass pass, int slot, GpuBufferSlice value) {
        EntityDrawState state = entityPasses.get(pass);
        if (state != null && slot == 0) state.vertexBuffer = value;
    }

    public void trackEntityIndexBuffer(RenderPass pass, GpuBuffer buffer, IndexType type) {
        EntityDrawState state = entityPasses.get(pass);
        if (state != null) {
            state.indexBuffer = buffer;
            state.indexType = type;
        }
    }

    public void captureEntityDraw(RenderPass pass, int indexCount, int instanceCount,
                                  int firstIndex, int vertexOffset, int firstInstance) {
        EntityDrawState state = entityPasses.get(pass);
        if (state != null && state.vertexBuffer != null && state.indexBuffer != null
            && state.uniforms.containsKey("DynamicTransforms") && state.textures.containsKey("Sampler0")) {
            EntityDrawState snapshot = new EntityDrawState();
            snapshot.pipeline = state.pipeline;
            snapshot.uniforms.putAll(state.uniforms);
            snapshot.textures.putAll(state.textures);
            snapshot.vertexBuffer = state.vertexBuffer;
            snapshot.indexBuffer = state.indexBuffer;
            snapshot.indexType = state.indexType;
            entityShadowDraws.add(new IndexedEntityDraw(snapshot, indexCount, instanceCount,
                firstIndex, vertexOffset, firstInstance));
        }
    }

    private static boolean isEntityPipeline(RenderPipeline pipeline) {
        return pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.ENTITY
            && pipeline.getPrimitiveTopology() == PrimitiveTopology.QUADS
            && pipeline.getLocation().getPath().startsWith("pipeline/entity_")
            && !pipeline.getLocation().getPath().equals("pipeline/entity_shadow");
    }

    /**
     * Returns the in-memory source for one active pipeline identity.
     *
     * <p>Minecraft clears its Vulkan pipeline cache during resource reloads. The backend
     * mixin calls this on the later cache miss so the same validated source is compiled
     * again instead of asking the resource manager for a non-existent classpath shader.
     */
    public ShaderSource customSourceFor(RenderPipeline pipeline) {
        ActivePack current = active;
        if (current == null) {
            return null;
        }
        return current.shaderSources().get(pipeline);
    }

    /** Allocates/resizes and clears Retina-owned targets before world command recording. */
    public void beginWorldFrame() {
        // A failed frame must never leave Minecraft pointing at a retired colortex texture.
        restoreMainColorTarget();
        ActivePack current = active;
        entityPasses.clear();
        entityShadowDraws.clear();
        if (current == null) {
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (current.framebuffer() != null) {
            com.mojang.blaze3d.pipeline.RenderTarget main =
                Minecraft.getInstance().gameRenderer.mainRenderTarget();
            current.framebuffer().resize(main.width, main.height);
            current.framebuffer().clearFrame(encoder);
            redirectMainColorTarget(current, main);
        }
        if (current.shadowFramebuffer() != null) {
            current.shadowFramebuffer().clear(encoder);
        }
        frameUniforms = null;
        shadowUniforms = null;
        terrainInvocation = null;
        shadowView = null;
        prepareRendered = false;
        shadowCompRendered = false;
        cameraMedium = FogType.NONE;
    }

    /**
     * Lets LevelRenderer's existing frame graph render sky, weather, clouds, particles,
     * entities and block entities into colortex0. Terrain already uses that attachment; all
     * other world features can therefore remain on their tested vanilla rendering paths.
     */
    private void redirectMainColorTarget(ActivePack current,
                                         com.mojang.blaze3d.pipeline.RenderTarget main) {
        PreparedTerrainPack.TargetPlan target = current.prepared().targets().get(0);
        GpuTexture original = main.getColorTexture();
        GpuTextureView originalView = main.getColorTextureView();
        if (target == null || original == null || originalView == null
            || current.framebuffer().targetWidth(0) != main.width
            || current.framebuffer().targetHeight(0) != main.height
            || current.framebuffer().format(0) != original.getFormat()) {
            if (!loggedSceneFallback) {
                loggedSceneFallback = true;
                LOGGER.warn("colortex0 is not a full-resolution {} scene target; retaining "
                    + "terrain-only routing for {}", original == null ? "unknown" : original.getFormat(),
                    current.prepared().name());
            }
            return;
        }
        RenderTargetColorAccessor access = (RenderTargetColorAccessor)(Object)main;
        mainColorRedirect = new MainColorRedirect(main, original, originalView);
        access.retina$colorTexture(current.framebuffer().mainTexture(0));
        access.retina$colorTextureView(current.framebuffer().mainView(0));
        if (!loggedSceneRouting) {
            loggedSceneRouting = true;
            LOGGER.info("Routing vanilla world features into colortex0 (sky, clouds, weather, "
                + "particles, entities, block entities)");
        }
    }

    /** Restores Minecraft's presentation attachment before Retina's final pass writes it. */
    public void restoreMainColorTarget() {
        MainColorRedirect redirect = mainColorRedirect;
        if (redirect == null) {
            return;
        }
        RenderTargetColorAccessor access = (RenderTargetColorAccessor)(Object)redirect.target();
        access.retina$colorTexture(redirect.texture());
        access.retina$colorTextureView(redirect.view());
        mainColorRedirect = null;
    }

    /** Captured from Minecraft's main camera so scene composition follows vanilla medium state. */
    public void setCameraMedium(FogType medium) {
        cameraMedium = medium;
    }

    /** Replaces Sodium's single-colour terrain pass with the validated MRT descriptor. */
    public RenderPass createTerrainRenderPass(CommandEncoder encoder, Supplier<String> label,
                                               GpuTextureView originalColor,
                                               Optional<org.joml.Vector4fc> colorClear,
                                               GpuTextureView depth,
                                               OptionalDouble depthClear,
                                               TerrainRenderPass terrainPass) {
        ActivePack current = active;
        if (current != null && shadowRendering && current.shadowFramebuffer() != null) {
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Retina shadow terrain");
            for (GpuTextureView attachment : current.shadowFramebuffer().colorAttachments()) {
                descriptor.withColorAttachment(attachment);
            }
            descriptor.withDepthAttachment(current.shadowFramebuffer().depthView(),
                OptionalDouble.of(0.0));
            int size = current.shadowFramebuffer().size();
            descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, size, size));
            return encoder.createRenderPass(descriptor);
        }
        if (current == null || current.framebuffer() == null) {
            return encoder.createRenderPass(label, originalColor, colorClear, depth, depthClear);
        }
        PreparedTerrainPack.PassKind kind = passKind(terrainPass);
        PreparedTerrainPack.Program program = current.prepared().programs().get(kind);
        if (!loggedTerrainMrt) {
            loggedTerrainMrt = true;
            LOGGER.info("Recording first terrain MRT pass {} into colortex{}", kind,
                program.drawTargets());
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(label);
        for (GpuTextureView attachment
            : current.framebuffer().terrainAttachments(program.drawTargets())) {
            descriptor.withColorAttachment(attachment);
        }
        descriptor.withDepthAttachment(depth, depthClear);
        int firstTarget = program.drawTargets().getFirst();
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0,
            current.framebuffer().targetWidth(firstTarget),
            current.framebuffer().targetHeight(firstTarget)));
        return encoder.createRenderPass(descriptor);
    }

    /** Records enough of Sodium's normal terrain invocation to repeat it from the light view. */
    public void captureTerrainInvocation(ChunkRenderMatrices matrices, CameraTransform camera,
                                         FogParameters fog, GpuSampler sampler,
                                         TerrainRenderPass pass) {
        if (shadowRendering || active == null || active.shadowFramebuffer() == null) {
            return;
        }
        // The shared per-frame values are recorded regardless of which pass kind supplied
        // them, so a frame where translucent is the only terrain Sodium actually drew still
        // leaves entity-shadow replay and the shadowcomp chain (below, in renderShadowPass)
        // with a camera/fog/sampler to work from -- only the terrain-pass entry itself is
        // conditionally withheld.
        if (terrainInvocation == null) {
            terrainInvocation = new TerrainInvocation();
        }
        terrainInvocation.matrices = matrices;
        terrainInvocation.camera = camera;
        terrainInvocation.fog = fog;
        terrainInvocation.sampler = sampler;
        if (pass.isTranslucent()) {
            // Sodium's own TerrainRenderPass.getTarget() resolves a translucent pass to
            // Minecraft's translucentTarget(), a target scoped to vanilla's own translucent
            // draw within the normal frame. Retina's shadow replay runs later, in the same
            // frame but outside that window, where translucentTarget() is null -- and
            // DefaultChunkRenderer.render() dereferences getTarget() as a plain argument
            // expression before Retina's render-pass redirect mixin ever runs, so there is no
            // way to redirect this pass to the shadow framebuffer at all. Shadow maps
            // conventionally skip translucent/water depth regardless, so the correct fix is
            // simply not to queue it for replay.
            return;
        }
        terrainInvocation.passes.put(passKind(pass), pass);
    }

    /** Replays Sodium's visible terrain lists through the active shadow shader and depth map. */
    public void renderShadowPass() {
        ActivePack current = active;
        if (current == null || current.shadowFramebuffer() == null) {
            return;
        }
        // A frame with no terrain invocation to replay -- nothing visible yet, or every
        // visible pass was translucent-only -- still needs entity-shadow replay and the
        // shadowcomp chain to run below; neither of those two reads terrain-invocation state,
        // so only the terrain replay itself is conditional on it.
        renderTerrainShadows(current);
        replayEntityShadows(current);
        renderShadowCompChain(current);
    }

    private void renderTerrainShadows(ActivePack current) {
        TerrainInvocation invocation = terrainInvocation;
        if (invocation == null || invocation.camera == null || invocation.fog == null
            || invocation.sampler == null || invocation.passes.isEmpty()) {
            return;
        }
        if (shadowView == null) {
            shadowView = createShadowView(current.prepared().shadowProgram(), invocation.camera);
        }
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer == null) {
            return;
        }
        shadowRendering = true;
        try {
            if (!loggedShadowPass) {
                loggedShadowPass = true;
                LOGGER.info("Recording terrain shadow map ({}px, {} visible layers)",
                    current.shadowFramebuffer().size(), invocation.passes.size());
            }
            ChunkRenderMatrices matrices = new ChunkRenderMatrices(shadowView.projection(),
                shadowView.modelView());
            for (TerrainRenderPass pass : invocation.passes.values()) {
                renderer.renderLayer(matrices, pass, invocation.camera.x, invocation.camera.y,
                    invocation.camera.z, invocation.fog, invocation.sampler);
            }
        } catch (RuntimeException e) {
            long failedGeneration = generation.incrementAndGet();
            status = new Status(State.FAILED, current.prepared().name(),
                "shadow pass failed: " + usefulMessage(e));
            LOGGER.error("Shadow pass for {} failed and was deactivated", current.prepared().name(), e);
            deactivate(failedGeneration);
        } finally {
            shadowRendering = false;
        }
    }

    private void replayEntityShadows(ActivePack current) {
        if (current.entityShadowPipeline() == null || entityShadowDraws.isEmpty()
            || shadowUniforms == null || current.shadowFramebuffer() == null) {
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Retina entity shadows");
        for (GpuTextureView attachment : current.shadowFramebuffer().colorAttachments()) {
            descriptor.withColorAttachment(attachment);
        }
        descriptor.withDepthAttachment(current.shadowFramebuffer().depthView(), OptionalDouble.empty());
        int size = current.shadowFramebuffer().size();
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, size, size));
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(current.entityShadowPipeline());
            pass.setUniform("RetinaUniforms", shadowUniforms);
            for (IndexedEntityDraw draw : entityShadowDraws) {
                for (Map.Entry<String, GpuBufferSlice> uniform : draw.state().uniforms.entrySet()) {
                    pass.setUniform(uniform.getKey(), uniform.getValue());
                }
                for (Map.Entry<String, TextureBinding> texture : draw.state().textures.entrySet()) {
                    pass.bindTexture(texture.getKey(), texture.getValue().view(), texture.getValue().sampler());
                }
                pass.setVertexBuffer(0, draw.state().vertexBuffer);
                pass.setIndexBuffer(draw.state().indexBuffer, draw.state().indexType);
                pass.drawIndexed(draw.indexCount(), draw.instanceCount(), draw.firstIndex(),
                    draw.vertexOffset(), draw.firstInstance());
            }
            if (!loggedEntityShadows) {
                loggedEntityShadows = true;
                LOGGER.info("Recorded {} standard entity/block-entity shadow draws", entityShadowDraws.size());
            }
        }
    }

    /** Runs colortex shadowcomp stages after terrain and entity shadow replay are complete. */
    private void renderShadowCompChain(ActivePack current) {
        if (shadowCompRendered || current.prepared().shadowCompPrograms().isEmpty()
            || current.framebuffer() == null || frameUniforms == null) {
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try {
            for (PreparedTerrainPack.PostProgram program : current.prepared().shadowCompPrograms()) {
                executePost(current, encoder, program, frameUniforms, false);
                current.framebuffer().finishPost(program);
            }
            shadowCompRendered = true;
        } catch (RuntimeException e) {
            long failedGeneration = generation.incrementAndGet();
            status = new Status(State.FAILED, current.prepared().name(),
                "shadowcomp chain failed: " + usefulMessage(e));
            LOGGER.error("Shadowcomp chain for {} failed and was deactivated",
                current.prepared().name(), e);
            deactivate(failedGeneration);
        }
    }

    /** Whether Sodium's renderer is currently being replayed from the shadow camera. */
    public boolean isShadowRendering() {
        return shadowRendering;
    }

    /** Executes deferred, composite, and final programs after the assembled world scene. */
    public void renderPostChain() {
        restoreMainColorTarget();
        ActivePack current = active;
        GpuBufferSlice uniforms = frameUniforms;
        if (current == null || current.framebuffer() == null || uniforms == null) {
            return;
        }
        if (!loggedPostChain) {
            loggedPostChain = true;
            LOGGER.info("Recording first post chain ({} deferred, {} composite, final={})",
                current.prepared().deferredPrograms().size(),
                current.prepared().compositePrograms().size(),
                current.prepared().finalProgram() != null);
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try {
            // Sodium has completed every terrain render pass by this point. Prepare used to be
            // recorded from prepareUniforms(), which is called while Sodium's pass is open;
            // nesting a Blaze3D render pass there corrupts the Vulkan command encoder.
            renderPrepareChain(current, uniforms, encoder);
            renderUnderwaterComposition(current, encoder);
            for (PreparedTerrainPack.PostProgram program
                : current.prepared().deferredPrograms()) {
                executePost(current, encoder, program, uniforms, false);
                current.framebuffer().finishPost(program);
            }
            for (PreparedTerrainPack.PostProgram program
                : current.prepared().compositePrograms()) {
                executePost(current, encoder, program, uniforms, false);
                current.framebuffer().finishPost(program);
            }
            PreparedTerrainPack.PostProgram finalProgram = current.prepared().finalProgram();
            if (finalProgram != null) {
                executePost(current, encoder, finalProgram, uniforms, true);
            }
        } catch (RuntimeException e) {
            // A runtime driver/resource failure must disable only the post chain. Keeping the
            // pack selected would repeat the fault each frame and can hang the graphics queue.
            long failedGeneration = generation.incrementAndGet();
            status = new Status(State.FAILED, current.prepared().name(),
                "post chain failed: " + usefulMessage(e));
            LOGGER.error("Post chain for {} failed and was deactivated",
                current.prepared().name(), e);
            deactivate(failedGeneration);
        }
    }

    /** Applies depth-aware attenuation only when Minecraft reports a submerged camera. */
    private void renderUnderwaterComposition(ActivePack current, CommandEncoder encoder) {
        if (currentCameraMedium(current) != FogType.WATER) {
            return;
        }
        com.mojang.blaze3d.pipeline.RenderTarget main =
            Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (main.getDepthTextureView() == null) {
            return;
        }
        ColortexFramebuffer framebuffer = current.framebuffer();
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Retina underwater medium");
        descriptor.withColorAttachment(framebuffer.alternateView(0));
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, framebuffer.targetWidth(0),
            framebuffer.targetHeight(0)));
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(current.underwaterPipeline());
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            pass.bindTexture("SceneColor", framebuffer.mainView(0), sampler);
            pass.bindTexture("SceneDepth", main.getDepthTextureView(), sampler);
            pass.draw(3, 1, 0, 0);
        }
        framebuffer.flipTarget(0);
    }

    private FogType currentCameraMedium(ActivePack current) {
        return current == active ? cameraMedium : FogType.NONE;
    }

    /** Executes prepare stages once at a render-pass-safe boundary before deferred work. */
    private void renderPrepareChain(ActivePack current, GpuBufferSlice uniforms,
                                    CommandEncoder encoder) {
        if (prepareRendered || current.prepared().preparePrograms().isEmpty()
            || current.framebuffer() == null) {
            return;
        }
        try {
            for (PreparedTerrainPack.PostProgram program : current.prepared().preparePrograms()) {
                executePost(current, encoder, program, uniforms, false);
                current.framebuffer().finishPost(program);
            }
            prepareRendered = true;
        } catch (RuntimeException e) {
            long failedGeneration = generation.incrementAndGet();
            status = new Status(State.FAILED, current.prepared().name(),
                "prepare chain failed: " + usefulMessage(e));
            LOGGER.error("Prepare chain for {} failed and was deactivated",
                current.prepared().name(), e);
            deactivate(failedGeneration);
        }
    }

    private static void executePost(ActivePack current, CommandEncoder encoder,
                                    PreparedTerrainPack.PostProgram program,
                                    GpuBufferSlice uniforms, boolean finalPass) {
        ColortexFramebuffer framebuffer = current.framebuffer();
        for (String sampler : program.samplers()) {
            if (sampler.startsWith("colortex")) {
                int index = colortexIndex(sampler);
                if (current.prepared().targets().get(index).settings().mipmap()) {
                    framebuffer.generateMipmaps(encoder, index);
                }
            }
        }

        RenderPassDescriptor descriptor = RenderPassDescriptor.create(
            () -> "Retina " + program.sourceName());
        int targetWidth;
        int targetHeight;
        if (finalPass) {
            com.mojang.blaze3d.pipeline.RenderTarget main =
                Minecraft.getInstance().gameRenderer.mainRenderTarget();
            descriptor.withColorAttachment(main.getColorTextureView());
            targetWidth = main.width;
            targetHeight = main.height;
        } else {
            for (GpuTextureView attachment
                : framebuffer.postAttachments(program.drawTargets())) {
                descriptor.withColorAttachment(attachment);
            }
            int first = program.drawTargets().getFirst();
            targetWidth = framebuffer.targetWidth(first);
            targetHeight = framebuffer.targetHeight(first);
        }
        var scale = program.scale();
        int x = Math.clamp(Math.round(targetWidth * scale.offsetX()), 0,
            targetWidth - 1);
        int y = Math.clamp(Math.round(targetHeight * scale.offsetY()), 0,
            targetHeight - 1);
        int areaWidth = Math.clamp(Math.round(targetWidth * scale.scale()), 1,
            targetWidth - x);
        int areaHeight = Math.clamp(Math.round(targetHeight * scale.scale()), 1,
            targetHeight - y);
        descriptor.withRenderArea(new RenderPass.RenderArea(x, y,
            areaWidth, areaHeight));

        try (RenderPass renderPass = encoder.createRenderPass(descriptor)) {
            renderPass.setPipeline(current.postPipelines().get(program));
            renderPass.setUniform("RetinaUniforms", uniforms);
            for (String sampler : program.samplers()) {
                if (sampler.startsWith("colortex")) {
                    int index = colortexIndex(sampler);
                    boolean mipmapped = current.prepared().targets().get(index)
                        .settings().mipmap();
                    GpuSampler gpuSampler = RenderSystem.getSamplerCache()
                        .getClampToEdge(FilterMode.LINEAR, mipmapped);
                    renderPass.bindTexture(sampler, framebuffer.mainView(index), gpuSampler);
                } else if (sampler.equals("shadowtex0") || sampler.equals("shadowtex1")
                    || sampler.equals("shadow")) {
                    if (current.shadowFramebuffer() == null) {
                        throw new IllegalStateException(program.sourceName() + " samples " + sampler
                            + " but this pack has no shadow.vsh/shadow.fsh");
                    }
                    boolean comparison = program.samplerTypes().getOrDefault(sampler, "")
                        .endsWith("Shadow");
                    renderPass.bindTexture(sampler, current.shadowFramebuffer().depthView(),
                        comparison ? current.shadowFramebuffer().comparisonSampler()
                            : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                } else if (sampler.startsWith("shadowcolor")) {
                    if (current.shadowFramebuffer() == null) {
                        throw new IllegalStateException(program.sourceName() + " samples " + sampler
                            + " but this pack has no shadow pass");
                    }
                    int index = Integer.parseInt(sampler.substring("shadowcolor".length()));
                    renderPass.bindTexture(sampler, current.shadowFramebuffer().colorView(index),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                }
            }
            renderPass.draw(3, 1, 0, 0);
        }
    }

    private static int colortexIndex(String sampler) {
        return Integer.parseInt(sampler.substring("colortex".length()));
    }

    private static PreparedTerrainPack.PassKind passKind(TerrainRenderPass pass) {
        return pass.isTranslucent() ? PreparedTerrainPack.PassKind.TRANSLUCENT
            : pass.supportsFragmentDiscard() ? PreparedTerrainPack.PassKind.CUTOUT
            : PreparedTerrainPack.PassKind.SOLID;
    }

    private static ShadowView createShadowView(PreparedTerrainPack.ShadowProgram program,
                                               CameraTransform camera) {
        Minecraft minecraft = Minecraft.getInstance();
        long dayTime = minecraft.level == null ? 0L : minecraft.level.getOverworldClockTime();
        float angle = (float)(Math.floorMod(dayTime - 6000L, 24000L) * (Math.PI * 2.0 / 24000.0));
        Vector3f light = new Vector3f((float)Math.cos(angle), 0.72f,
            (float)Math.sin(angle)).normalize();
        float distance = program.distance();
        Vector3f center = new Vector3f((float)camera.x, (float)camera.y, (float)camera.z);
        Vector3f eye = new Vector3f(center).fma(distance * 1.5f, light);
        Vector3f up = Math.abs(light.y) > 0.95f ? new Vector3f(0, 0, 1)
            : new Vector3f(0, 1, 0);
        Matrix4f modelView = new Matrix4f().lookAt(eye, center, up);
        // Minecraft's terrain path uses reversed-Z. Giving JOML far then near preserves that
        // convention so the shared Sodium draw path can keep GREATER depth tests.
        Matrix4f projection = new Matrix4f().setOrtho(-distance, distance, -distance, distance,
            distance * 4.0f, 0.05f, true);
        return new ShadowView(projection, modelView);
    }

    /** Writes and returns the current terrain UBO slice, or null while shaders are off. */
    public GpuBufferSlice prepareUniforms(ChunkRenderMatrices matrices, CameraTransform camera,
                                          FogParameters fog) {
        ActivePack current = active;
        if (current == null) {
            return null;
        }
        long now = System.nanoTime();
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getWidth();
        int height = minecraft.getWindow().getHeight();
        long dayTime = minecraft.level == null ? 0L : minecraft.level.getOverworldClockTime();
        int moonPhase = (int)Math.floorMod(dayTime / 24000L, 8L);
        float rain = minecraft.level == null ? 0.0f : minecraft.level.getRainLevel(1.0f);
        float far = minecraft.options.renderDistance().get() * 16.0f;
        if (current.shadowFramebuffer() != null && shadowView == null) {
            shadowView = createShadowView(current.prepared().shadowProgram(), camera);
        }
        Matrix4f viewProjection = shadowRendering && shadowView != null
            ? new Matrix4f(shadowView.projection()) : new Matrix4f(matrices.projection());
        Matrix4f viewModelView = shadowRendering && shadowView != null
            ? new Matrix4f(shadowView.modelView()) : new Matrix4f(matrices.modelView());
        Matrix4f shadowProjection = shadowView == null ? new Matrix4f(matrices.projection())
            : new Matrix4f(shadowView.projection());
        Matrix4f shadowModelView = shadowView == null ? new Matrix4f(matrices.modelView())
            : new Matrix4f(shadowView.modelView());
        TerrainFrameUniform uniform = new TerrainFrameUniform(
            current.prepared().uniforms(), viewProjection, viewModelView,
            shadowProjection, shadowModelView, new Matrix4f(previousProjection),
            new Matrix4f(previousModelView), camera.x, camera.y, camera.z,
            fog, width, height, (int)(dayTime % 24000L), (int)(dayTime / 24000L),
            moonPhase, rain, frameCounter % 720720,
            (float)((now - previousUniformNanos) / 1_000_000_000.0),
            (float)(((now - startNanos) / 1_000_000_000.0) % 3600.0), 0.05f, far);
        if (shadowRendering) {
            if (shadowUniforms == null) {
                shadowUniforms = current.uniformStorage().writeUniform(uniform);
            }
            return shadowUniforms;
        }
        previousUniformNanos = now;
        currentModelView.set(matrices.modelView());
        currentProjection.set(matrices.projection());
        frameUniforms = current.uniformStorage().writeUniform(uniform);
        return frameUniforms;
    }

    /** Rotates ring buffers once the world's command recording for this frame has ended. */
    public void endFrame() {
        ActivePack current = active;
        if (current != null) {
            current.uniformStorage().endFrame();
            previousModelView.set(currentModelView);
            previousProjection.set(currentProjection);
            frameCounter++;
        }
        int count = retiredResources.size();
        for (int i = 0; i < count; i++) {
            RetiredResources retired = retiredResources.removeFirst();
            if (retired.framesLeft() <= 1) {
                retired.close();
            } else {
                retiredResources.addLast(retired.age());
            }
        }
    }

    public Status status() {
        return status;
    }

    private static String usefulMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName()
            : throwable.getMessage();
    }

    /** Immutable snapshot written into the program's union std140 block. */
    private record TerrainFrameUniform(
        TerrainUniformLayout layout, Matrix4f projection, Matrix4f modelView,
        Matrix4f shadowProjection, Matrix4f shadowModelView,
        Matrix4f previousProjection, Matrix4f previousModelView,
        double cameraX, double cameraY, double cameraZ, FogParameters fog,
        int width, int height, int worldTime, int worldDay, int moonPhase, float rain,
        int frameCounter, float frameTime, float frameTimeCounter, float near, float far
    ) implements DynamicUniformStorage.DynamicUniform {
        @Override
        public void write(ByteBuffer buffer) {
            layout.clear(buffer);
            Matrix4f modelViewInverse = new Matrix4f(modelView).invert();
            Matrix4f projectionInverse = new Matrix4f(projection).invert();
            Matrix4f modelViewProjection = new Matrix4f(projection).mul(modelView);
            Matrix4f modelViewProjectionInverse = new Matrix4f(modelViewProjection).invert();
            Matrix4f shadowModelViewInverse = new Matrix4f(shadowModelView).invert();
            Matrix4f shadowProjectionInverse = new Matrix4f(shadowProjection).invert();
            Matrix3f normal = new Matrix3f(modelView).invert().transpose();
            float sunAngle = Math.floorMod(worldTime - 6000, 24000) / 24000.0f;

            for (TerrainUniformLayout.Member member : layout.members()) {
                int at = member.offset();
                switch (member.name()) {
                    case "retina_ModelViewMatrix", "gbufferModelView" -> put(buffer, at, modelView);
                    case "retina_ModelViewMatrixInverse", "gbufferModelViewInverse" ->
                        put(buffer, at, modelViewInverse);
                    case "retina_ProjectionMatrix", "gbufferProjection" ->
                        put(buffer, at, projection);
                    case "retina_ProjectionMatrixInverse", "gbufferProjectionInverse" ->
                        put(buffer, at, projectionInverse);
                    case "retina_ModelViewProjectionMatrix" -> put(buffer, at,
                        modelViewProjection);
                    case "retina_ModelViewProjectionMatrixInverse" -> put(buffer, at,
                        modelViewProjectionInverse);
                    case "shadowModelView" -> put(buffer, at, shadowModelView);
                    case "shadowModelViewInverse" -> put(buffer, at, shadowModelViewInverse);
                    case "shadowProjection" -> put(buffer, at, shadowProjection);
                    case "shadowProjectionInverse" -> put(buffer, at, shadowProjectionInverse);
                    case "retina_NormalMatrix" -> put(buffer, at, normal);
                    case "gbufferPreviousModelView" -> put(buffer, at, previousModelView);
                    case "gbufferPreviousProjection" -> put(buffer, at, previousProjection);
                    case "cameraPosition" -> putVec3(buffer, at, (float)cameraX,
                        (float)cameraY, (float)cameraZ);
                    case "previousCameraPosition" -> putVec3(buffer, at, (float)cameraX,
                        (float)cameraY, (float)cameraZ);
                    case "eyeAltitude" -> buffer.putFloat(at, (float)cameraY);
                    case "frameCounter" -> buffer.putInt(at, frameCounter);
                    case "frameTime" -> buffer.putFloat(at, frameTime);
                    case "frameTimeCounter" -> buffer.putFloat(at, frameTimeCounter);
                    case "worldTime" -> buffer.putInt(at, worldTime);
                    case "worldDay" -> buffer.putInt(at, worldDay);
                    case "moonPhase" -> buffer.putInt(at, moonPhase);
                    case "sunAngle", "celestialAngle" -> buffer.putFloat(at, sunAngle);
                    case "shadowAngle" -> buffer.putFloat(at,
                        sunAngle < 0.5f ? sunAngle : sunAngle - 0.5f);
                    case "rainStrength", "wetness" -> buffer.putFloat(at, rain);
                    case "fogColor", "skyColor" -> putVec3(buffer, at, fog.red(),
                        fog.green(), fog.blue());
                    case "fogStart" -> buffer.putFloat(at, fog.renderStart());
                    case "fogEnd" -> buffer.putFloat(at, fog.renderEnd());
                    case "viewWidth" -> buffer.putFloat(at, width);
                    case "viewHeight" -> buffer.putFloat(at, height);
                    case "aspectRatio" -> buffer.putFloat(at,
                        height == 0 ? 1.0f : (float)width / height);
                    case "near" -> buffer.putFloat(at, near);
                    case "far" -> buffer.putFloat(at, far);
                    case "upPosition" -> putVec3(buffer, at, 0.0f, 1.0f, 0.0f);
                    default -> {
                        // Custom and not-yet-sourced scene uniforms intentionally remain zero.
                    }
                }
            }
        }

        private static void put(ByteBuffer buffer, int offset, Matrix4f matrix) {
            matrix.get(offset, buffer);
        }

        private static void put(ByteBuffer buffer, int offset, Matrix3f matrix) {
            buffer.putFloat(offset, matrix.m00());
            buffer.putFloat(offset + 4, matrix.m01());
            buffer.putFloat(offset + 8, matrix.m02());
            buffer.putFloat(offset + 16, matrix.m10());
            buffer.putFloat(offset + 20, matrix.m11());
            buffer.putFloat(offset + 24, matrix.m12());
            buffer.putFloat(offset + 32, matrix.m20());
            buffer.putFloat(offset + 36, matrix.m21());
            buffer.putFloat(offset + 40, matrix.m22());
        }

        private static void putVec3(ByteBuffer buffer, int offset, float x, float y, float z) {
            buffer.putFloat(offset, x);
            buffer.putFloat(offset + 4, y);
            buffer.putFloat(offset + 8, z);
        }
    }
}
