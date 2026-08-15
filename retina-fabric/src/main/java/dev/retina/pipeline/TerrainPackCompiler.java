/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.pipeline;

import dev.retina.config.RetinaConfig;
import dev.retina.core.option.OptionApplier;
import dev.retina.core.option.OptionValues;
import dev.retina.core.pack.PackDiscovery;
import dev.retina.core.pack.PackLoader;
import dev.retina.core.pack.PackPath;
import dev.retina.core.pack.PackSource;
import dev.retina.core.preprocess.PreprocessedSource;
import dev.retina.core.preprocess.ShaderPreprocessor;
import dev.retina.core.program.ProgramId;
import dev.retina.core.spirv.SpirvCompiler;
import dev.retina.core.state.AlphaTest;
import dev.retina.core.target.RenderTargetDirectives;
import dev.retina.core.translate.BindingLayout;
import dev.retina.core.translate.DrawBuffersDirective;
import dev.retina.core.translate.GlslLexer;
import dev.retina.core.translate.ShaderStage;
import dev.retina.core.translate.TranslatedSource;
import dev.retina.core.translate.VaryingLayout;
import dev.retina.core.translate.VulkanTranslator;
import dev.retina.core.uniform.UniformSchema;
import dev.retina.render.TerrainShaderAdapter;
import dev.retina.render.FullscreenShaderAdapter;
import dev.retina.render.EntityShaderAdapter;
import dev.retina.render.ParticleShaderAdapter;
import dev.retina.render.TerrainUniformLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preprocesses, translates, adapts and shaderc-validates a pack away from the render thread. */
public final class TerrainPackCompiler {
    private static final int MAX_COLOR_ATTACHMENTS = 8;
    private static final Map<String, String> DEFINES = Map.of(
        "RETINA", "1",
        "IS_IRIS", "1",
        "MC_VERSION", "2602",
        "MC_GL_VERSION", "450",
        "MC_GLSL_VERSION", "450"
    );

    public static final class CompilationException extends Exception {
        public CompilationException(String message) {
            super(message);
        }

        public CompilationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record TranslatedProgram(String sourceName, TranslatedSource vertex,
                                     TranslatedSource fragment, List<Integer> drawTargets,
                                     boolean cull) {
    }

    private record TranslatedPost(String sourceName, TranslatedSource vertex,
                                  TranslatedSource fragment, List<Integer> drawTargets,
                                  List<String> samplers, Map<String, String> samplerTypes,
                                  RenderTargetDirectives.PassScale scale,
                                  Map<Integer, Boolean> flips, boolean internal) {
    }

    private record TranslatedShadow(String sourceName, TranslatedSource vertex,
                                    TranslatedSource fragment, List<Integer> drawTargets,
                                    int resolution, float distance, boolean cull) {
    }

    private record TranslatedEntity(String sourceName, TranslatedSource vertex,
                                   TranslatedSource fragment, boolean cull) {
    }

    private record TranslatedParticle(String sourceName, TranslatedSource vertex,
                                      TranslatedSource fragment, boolean cull) {
    }

    private static final Pattern CONST_DIRECTIVE = Pattern.compile(
        "(?m)^\\s*(?:/\\*\\s*)?const\\s+(?:int|bool|vec4)\\s+"
            + "([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^;]+)\\s*;(?:\\s*\\*/)?");
    private static final Pattern SHADOW_INT = Pattern.compile(
        "(?m)^\\s*(?:/\\*\\s*)?const\\s+int\\s+(shadowMapResolution)\\s*=\\s*([0-9]+)\\s*;(?:\\s*\\*/)?");
    private static final Pattern SHADOW_FLOAT = Pattern.compile(
        "(?m)^\\s*(?:/\\*\\s*)?const\\s+float\\s+(shadowDistance)\\s*=\\s*"
            + "([0-9]+(?:\\.[0-9]+)?)\\s*;(?:\\s*\\*/)?");

    public PreparedTerrainPack compile(PackManager manager, RetinaConfig config)
        throws CompilationException {
        String packName = config.selectedPack();
        PackDiscovery.Candidate candidate = manager.findCandidate(packName)
            .orElseThrow(() -> new CompilationException(
                "shader pack '" + packName + "' is no longer present"));
        PackManager.PackDetails details = manager.inspect(packName)
            .orElseThrow(() -> new CompilationException(
                "shader pack '" + packName + "' could not be inspected"));
        OptionValues values = manager.loadOptions(packName, details.options());

        try (PackSource source = PackDiscovery.open(candidate)) {
            PackLoader.Result resolved = PackLoader.resolve(source);
            if (!(resolved instanceof PackLoader.Result.Ok ok)) {
                throw new CompilationException(((PackLoader.Result.Invalid)resolved).reason());
            }
            return compileOpenPack(packName, details, values, source, ok.shadersRoot(), config);
        } catch (CompilationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new CompilationException("could not compile '" + packName + "': "
                + usefulMessage(e), e);
        }
    }

    private PreparedTerrainPack compileOpenPack(String packName, PackManager.PackDetails details,
                                                OptionValues values, PackSource source,
                                                PackPath shadersRoot, RetinaConfig config)
        throws IOException, CompilationException {
        ShaderPreprocessor preprocessor = new ShaderPreprocessor(source, shadersRoot);
        OptionApplier optionApplier = new OptionApplier(details.options(), values);
        BindingLayout bindings = new BindingLayout();
        VulkanTranslator translator = new VulkanTranslator(bindings, UniformSchema.standard());
        List<String> diagnostics = new ArrayList<>(details.diagnostics());
        RenderTargetDirectives targetDirectives = new RenderTargetDirectives();
        Map<PreparedTerrainPack.PassKind, TranslatedProgram> translated = new EnumMap<>(
            PreparedTerrainPack.PassKind.class);

        translateProgram(translated, PreparedTerrainPack.PassKind.SOLID,
            ProgramId.GBUFFERS_TERRAIN_SOLID, preprocessor, optionApplier, source, shadersRoot,
            details, translator, targetDirectives, diagnostics);
        translateProgram(translated, PreparedTerrainPack.PassKind.CUTOUT,
            ProgramId.GBUFFERS_TERRAIN_CUTOUT, preprocessor, optionApplier, source, shadersRoot,
            details, translator, targetDirectives, diagnostics);
        translateProgram(translated, PreparedTerrainPack.PassKind.TRANSLUCENT,
            ProgramId.GBUFFERS_WATER, preprocessor, optionApplier, source, shadersRoot,
            details, translator, targetDirectives, diagnostics);

        TranslatedShadow translatedShadow = translateOptionalShadow(preprocessor, optionApplier,
            source, shadersRoot, details, translator, targetDirectives, diagnostics);
        TranslatedEntity translatedEntity = translateOptionalEntity(preprocessor, optionApplier,
            source, shadersRoot, details, translator, targetDirectives, diagnostics);
        TranslatedParticle translatedParticle = translateOptionalParticle(preprocessor, optionApplier,
            source, shadersRoot, details, translator, targetDirectives, diagnostics,
            ProgramId.GBUFFERS_PARTICLES, "particles");
        TranslatedParticle translatedWeather = translateOptionalParticle(preprocessor, optionApplier,
            source, shadersRoot, details, translator, targetDirectives, diagnostics,
            ProgramId.GBUFFERS_WEATHER, "weather");

        Map<String, RenderTargetDirectives.PassScale> scales =
            targetDirectives.readPassScales(details.properties().raw());
        List<TranslatedPost> preparePrograms = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String name = index == 0 ? "prepare" : "prepare" + index;
            TranslatedPost post = translateOptionalPost(name, false, preprocessor,
                optionApplier, source, shadersRoot, details, translator, targetDirectives,
                scales, diagnostics);
            if (post != null) {
                preparePrograms.add(post);
            }
        }
        List<TranslatedPost> shadowCompPrograms = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String name = index == 0 ? "shadowcomp" : "shadowcomp" + index;
            TranslatedPost post = translateOptionalPost(name, false, preprocessor,
                optionApplier, source, shadersRoot, details, translator, targetDirectives,
                scales, diagnostics);
            if (post != null) {
                shadowCompPrograms.add(post);
            }
        }
        if (!shadowCompPrograms.isEmpty() && translatedShadow == null) {
            throw new CompilationException("shadowcomp requires an active shadow.vsh/shadow.fsh pass");
        }
        List<TranslatedPost> deferredPrograms = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String name = index == 0 ? "deferred" : "deferred" + index;
            TranslatedPost post = translateOptionalPost(name, false, preprocessor,
                optionApplier, source, shadersRoot, details, translator, targetDirectives,
                scales, diagnostics);
            if (post != null) {
                deferredPrograms.add(post);
            }
        }
        List<TranslatedPost> postPrograms = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String name = index == 0 ? "composite" : "composite" + index;
            TranslatedPost post = translateOptionalPost(name, false, preprocessor,
                optionApplier, source, shadersRoot, details, translator, targetDirectives,
                scales, diagnostics);
            if (post != null) {
                postPrograms.add(post);
            }
        }
        TranslatedPost finalTranslated = translateOptionalPost("final", true, preprocessor,
            optionApplier, source, shadersRoot, details, translator, targetDirectives,
            scales, diagnostics);

        // Every active Retina pack now owns the full world scene in colortex0. Even a legacy
        // terrain-only pack therefore needs a presentation pass to return that scene to
        // Minecraft's swapchain target.
        if (finalTranslated == null) {
            finalTranslated = translateInternalFinal(translator, targetDirectives, diagnostics);
        }

        List<TranslatedSource> allSources = new ArrayList<>();
        translated.values().forEach(program -> {
            allSources.add(program.vertex());
            allSources.add(program.fragment());
        });
        if (translatedShadow != null) {
            allSources.add(translatedShadow.vertex());
            allSources.add(translatedShadow.fragment());
        }
        preparePrograms.forEach(program -> {
            allSources.add(program.vertex());
            allSources.add(program.fragment());
        });
        shadowCompPrograms.forEach(program -> {
            allSources.add(program.vertex());
            allSources.add(program.fragment());
        });
        deferredPrograms.forEach(program -> {
            allSources.add(program.vertex());
            allSources.add(program.fragment());
        });
        postPrograms.forEach(program -> {
            allSources.add(program.vertex());
            allSources.add(program.fragment());
        });
        if (finalTranslated != null) {
            allSources.add(finalTranslated.vertex());
            allSources.add(finalTranslated.fragment());
        }
        TerrainUniformLayout uniforms = TerrainUniformLayout.build(
            TerrainShaderAdapter.collectUniformMembers(allSources));
        Map<PreparedTerrainPack.PassKind, PreparedTerrainPack.Program> programs =
            new EnumMap<>(PreparedTerrainPack.PassKind.class);
        SpirvCompiler.Optimisation optimisation = optimisation(config.profile());
        try (SpirvCompiler compiler = new SpirvCompiler()) {
            for (Map.Entry<PreparedTerrainPack.PassKind, TranslatedProgram> entry
                : translated.entrySet()) {
                TranslatedProgram program = entry.getValue();
                String vertex = TerrainShaderAdapter.adapt(program.vertex(), uniforms, true);
                String fragment = TerrainShaderAdapter.adapt(program.fragment(), uniforms, false);
                validate(compiler, program.sourceName() + ".vsh", vertex,
                    ShaderStage.VERTEX, optimisation);
                validate(compiler, program.sourceName() + ".fsh", fragment,
                    ShaderStage.FRAGMENT, optimisation);
                programs.put(entry.getKey(), new PreparedTerrainPack.Program(
                    program.sourceName(), vertex, fragment, program.drawTargets(),
                    program.cull()));
            }
            PreparedTerrainPack.ShadowProgram shadowProgram = translatedShadow == null ? null
                : adaptShadow(compiler, translatedShadow, targetDirectives, uniforms, optimisation);
            PreparedTerrainPack.EntityProgram entityProgram = translatedEntity == null ? null
                : adaptEntity(compiler, translatedEntity, optimisation);
            PreparedTerrainPack.ParticleProgram particleProgram = translatedParticle == null ? null
                : adaptParticle(compiler, translatedParticle, optimisation);
            PreparedTerrainPack.WeatherProgram weatherProgram = translatedWeather == null ? null
                : adaptWeather(compiler, translatedWeather, optimisation);
            List<PreparedTerrainPack.PostProgram> preparedPreparePrograms = new ArrayList<>();
            for (TranslatedPost post : preparePrograms) {
                preparedPreparePrograms.add(adaptPost(compiler, post, uniforms, optimisation));
            }
            List<PreparedTerrainPack.PostProgram> preparedShadowCompPrograms = new ArrayList<>();
            for (TranslatedPost post : shadowCompPrograms) {
                preparedShadowCompPrograms.add(adaptPost(compiler, post, uniforms, optimisation));
            }
            List<PreparedTerrainPack.PostProgram> preparedDeferredPrograms = new ArrayList<>();
            for (TranslatedPost post : deferredPrograms) {
                preparedDeferredPrograms.add(adaptPost(compiler, post, uniforms, optimisation));
            }
            List<PreparedTerrainPack.PostProgram> compositePrograms = new ArrayList<>();
            for (TranslatedPost post : postPrograms) {
                compositePrograms.add(adaptPost(compiler, post, uniforms, optimisation));
            }
            PreparedTerrainPack.PostProgram finalProgram = finalTranslated == null ? null
                : adaptPost(compiler, finalTranslated, uniforms, optimisation);

            Set<Integer> referencedTargets = new LinkedHashSet<>();
            programs.values().forEach(p -> referencedTargets.addAll(p.drawTargets()));
            preparedPreparePrograms.forEach(p -> {
                referencedTargets.addAll(p.drawTargets());
                referencedTargets.addAll(colortexIndices(p.samplers()));
            });
            preparedShadowCompPrograms.forEach(p -> {
                referencedTargets.addAll(p.drawTargets());
                referencedTargets.addAll(colortexIndices(p.samplers()));
            });
            preparedDeferredPrograms.forEach(p -> {
                referencedTargets.addAll(p.drawTargets());
                referencedTargets.addAll(colortexIndices(p.samplers()));
            });
            compositePrograms.forEach(p -> {
                referencedTargets.addAll(p.drawTargets());
                referencedTargets.addAll(colortexIndices(p.samplers()));
            });
            if (finalProgram != null) {
                referencedTargets.addAll(colortexIndices(finalProgram.samplers()));
            }
            List<PreparedTerrainPack.PostProgram> targetPrograms = new ArrayList<>(
                preparedPreparePrograms);
            targetPrograms.addAll(preparedShadowCompPrograms);
            targetPrograms.addAll(preparedDeferredPrograms);
            targetPrograms.addAll(compositePrograms);
            Map<Integer, PreparedTerrainPack.TargetPlan> targets = buildTargetPlans(
                referencedTargets, targetDirectives, details, programs, targetPrograms);
            diagnostics.addAll(targetDirectives.problems());
            return new PreparedTerrainPack(packName, details.contentHash(), programs, entityProgram,
                particleProgram, weatherProgram,
                shadowProgram, preparedPreparePrograms, preparedShadowCompPrograms,
                preparedDeferredPrograms, compositePrograms, finalProgram, targets, uniforms,
                diagnostics);
        }
    }

    /** Translates an optional entity program against Minecraft's standard entity vertex ABI. */
    private static TranslatedEntity translateOptionalEntity(ShaderPreprocessor preprocessor,
        OptionApplier optionApplier, PackSource source, PackPath shadersRoot,
        PackManager.PackDetails details, VulkanTranslator translator,
        RenderTargetDirectives targets, List<String> diagnostics)
        throws IOException, CompilationException {
        String name = ProgramId.GBUFFERS_ENTITIES.sourceName();
        PackPath vertexPath = shadersRoot.resolve(name + ".vsh");
        PackPath fragmentPath = shadersRoot.resolve(name + ".fsh");
        boolean hasVertex = source.exists(vertexPath);
        boolean hasFragment = source.exists(fragmentPath);
        if (!hasVertex && !hasFragment) {
            return null;
        }
        if (!hasVertex || !hasFragment) {
            throw new CompilationException(name + " must supply both .vsh and .fsh");
        }
        PreprocessedSource vertexPreprocessed = preprocessor.process(vertexPath, DEFINES,
            optionApplier);
        PreprocessedSource fragmentPreprocessed = preprocessor.process(fragmentPath, DEFINES,
            optionApplier);
        applyTargetDirectives(targets, vertexPreprocessed.text());
        applyTargetDirectives(targets, fragmentPreprocessed.text());
        DrawBuffersDirective.Result found = DrawBuffersDirective.find(
            GlslLexer.tokenize(fragmentPreprocessed.text()));
        diagnostics.addAll(found.problems());
        DrawBuffersDirective drawBuffers = found.directive();
        if (!drawBuffers.targets().equals(List.of(0))) {
            throw new CompilationException(name + " currently supports only RENDERTARGETS: 0; "
                + "entity MRT requires dedicated draw routing");
        }
        List<VaryingLayout.Declaration> declarations = new ArrayList<>();
        declarations.addAll(VulkanTranslator.collectVaryings(vertexPreprocessed.text(),
            ShaderStage.VERTEX));
        declarations.addAll(VulkanTranslator.collectVaryings(fragmentPreprocessed.text(),
            ShaderStage.FRAGMENT));
        VaryingLayout.Build varying = VaryingLayout.build(declarations);
        diagnostics.addAll(varying.problems());
        TranslatedSource vertex = translator.translate(vertexPreprocessed.text(),
            new VulkanTranslator.Options(ShaderStage.VERTEX, drawBuffers, AlphaTest.ALWAYS,
                false, 450, varying.layout()));
        TranslatedSource fragment = translator.translate(fragmentPreprocessed.text(),
            new VulkanTranslator.Options(ShaderStage.FRAGMENT, drawBuffers,
                details.properties().programOverrides().alphaTest().getOrDefault(name,
                    AlphaTest.VANILLA_CUTOUT), false, 450, varying.layout()));
        diagnostics.addAll(vertex.warnings());
        diagnostics.addAll(fragment.warnings());
        rejectUnboundResources(name, vertex, fragment);
        boolean cull = details.properties().programOverrides().backFaceCulling()
            .getOrDefault("entities", false);
        return new TranslatedEntity(name, vertex, fragment, cull);
    }

    /** Translates an optional particle program against Minecraft's particle quad ABI. */
    private static TranslatedParticle translateOptionalParticle(ShaderPreprocessor preprocessor,
        OptionApplier optionApplier, PackSource source, PackPath shadersRoot,
        PackManager.PackDetails details, VulkanTranslator translator,
        RenderTargetDirectives targets, List<String> diagnostics, ProgramId programId,
        String cullKey)
        throws IOException, CompilationException {
        String name = programId.sourceName();
        PackPath vertexPath = shadersRoot.resolve(name + ".vsh");
        PackPath fragmentPath = shadersRoot.resolve(name + ".fsh");
        boolean hasVertex = source.exists(vertexPath);
        boolean hasFragment = source.exists(fragmentPath);
        if (!hasVertex && !hasFragment) {
            return null;
        }
        if (!hasVertex || !hasFragment) {
            throw new CompilationException(name + " must supply both .vsh and .fsh");
        }
        PreprocessedSource vertexPreprocessed = preprocessor.process(vertexPath, DEFINES,
            optionApplier);
        PreprocessedSource fragmentPreprocessed = preprocessor.process(fragmentPath, DEFINES,
            optionApplier);
        applyTargetDirectives(targets, vertexPreprocessed.text());
        applyTargetDirectives(targets, fragmentPreprocessed.text());
        DrawBuffersDirective.Result found = DrawBuffersDirective.find(
            GlslLexer.tokenize(fragmentPreprocessed.text()));
        diagnostics.addAll(found.problems());
        DrawBuffersDirective drawBuffers = found.directive();
        if (!drawBuffers.targets().equals(List.of(0))) {
            throw new CompilationException(name + " currently supports only RENDERTARGETS: 0; "
                + "particle MRT requires dedicated draw routing");
        }
        List<VaryingLayout.Declaration> declarations = new ArrayList<>();
        declarations.addAll(VulkanTranslator.collectVaryings(vertexPreprocessed.text(),
            ShaderStage.VERTEX));
        declarations.addAll(VulkanTranslator.collectVaryings(fragmentPreprocessed.text(),
            ShaderStage.FRAGMENT));
        VaryingLayout.Build varying = VaryingLayout.build(declarations);
        diagnostics.addAll(varying.problems());
        TranslatedSource vertex = translator.translate(vertexPreprocessed.text(),
            new VulkanTranslator.Options(ShaderStage.VERTEX, drawBuffers, AlphaTest.ALWAYS,
                false, 450, varying.layout()));
        TranslatedSource fragment = translator.translate(fragmentPreprocessed.text(),
            new VulkanTranslator.Options(ShaderStage.FRAGMENT, drawBuffers,
                details.properties().programOverrides().alphaTest().getOrDefault(name,
                    AlphaTest.VANILLA_CUTOUT), false, 450, varying.layout()));
        diagnostics.addAll(vertex.warnings());
        diagnostics.addAll(fragment.warnings());
        rejectUnboundResources(name, vertex, fragment);
        boolean cull = details.properties().programOverrides().backFaceCulling()
            .getOrDefault(cullKey, false);
        return new TranslatedParticle(name, vertex, fragment, cull);
    }

    private static TranslatedShadow translateOptionalShadow(ShaderPreprocessor preprocessor,
        OptionApplier optionApplier, PackSource source, PackPath shadersRoot,
        PackManager.PackDetails details, VulkanTranslator translator,
        RenderTargetDirectives targetDirectives, List<String> diagnostics)
        throws IOException, CompilationException {
        if (!details.properties().shadowEnabled() || !details.properties().shadowTerrain()) {
            return null;
        }
        PackPath vertexPath = shadersRoot.resolve("shadow.vsh");
        PackPath fragmentPath = shadersRoot.resolve("shadow.fsh");
        boolean hasVertex = source.exists(vertexPath);
        boolean hasFragment = source.exists(fragmentPath);
        if (!hasVertex && !hasFragment) {
            return null;
        }
        if (!hasVertex || !hasFragment) {
            throw new CompilationException("shadow must supply both .vsh and .fsh");
        }
        PreprocessedSource vertexPreprocessed = preprocessor.process(vertexPath, DEFINES,
            optionApplier);
        PreprocessedSource fragmentPreprocessed = preprocessor.process(fragmentPath, DEFINES,
            optionApplier);
        applyTargetDirectives(targetDirectives, vertexPreprocessed.text());
        applyTargetDirectives(targetDirectives, fragmentPreprocessed.text());
        DrawBuffersDirective.Result found = DrawBuffersDirective.find(
            GlslLexer.tokenize(fragmentPreprocessed.text()));
        diagnostics.addAll(found.problems());
        DrawBuffersDirective drawBuffers = details.properties().programOverrides()
            .drawBufferOverride().containsKey("shadow")
            ? new DrawBuffersDirective(details.properties().programOverrides()
                .drawBufferOverride().get("shadow"), DrawBuffersDirective.Form.RENDERTARGETS, -1)
            : found.directive();
        validateDrawTargets("shadow", drawBuffers.targets());
        List<VaryingLayout.Declaration> declarations = new ArrayList<>();
        declarations.addAll(VulkanTranslator.collectVaryings(vertexPreprocessed.text(),
            ShaderStage.VERTEX));
        declarations.addAll(VulkanTranslator.collectVaryings(fragmentPreprocessed.text(),
            ShaderStage.FRAGMENT));
        VaryingLayout.Build varying = VaryingLayout.build(declarations);
        diagnostics.addAll(varying.problems());
        TranslatedSource vertex = translator.translate(vertexPreprocessed.text(),
            new VulkanTranslator.Options(ShaderStage.VERTEX, drawBuffers, AlphaTest.ALWAYS,
                true, 450, varying.layout()));
        TranslatedSource fragment = translator.translate(fragmentPreprocessed.text(),
            new VulkanTranslator.Options(ShaderStage.FRAGMENT, drawBuffers,
                details.properties().programOverrides().alphaTest().getOrDefault("shadow",
                    AlphaTest.ALWAYS), true, 450, varying.layout()));
        diagnostics.addAll(vertex.warnings());
        diagnostics.addAll(fragment.warnings());
        rejectUnboundResources("shadow", vertex, fragment);
        String shadowDirectives = vertexPreprocessed.text() + '\n' + fragmentPreprocessed.text();
        int resolution = shadowInt(shadowDirectives, 1024);
        if (resolution < 128 || resolution > 4096 || (resolution & (resolution - 1)) != 0) {
            throw new CompilationException("shadowMapResolution must be a power of two in 128..4096");
        }
        float distance = shadowFloat(shadowDirectives, 160.0f);
        if (!Float.isFinite(distance) || distance < 16.0f || distance > 2048.0f) {
            throw new CompilationException("shadowDistance must be finite and in 16..2048");
        }
        return new TranslatedShadow("shadow", vertex, fragment, drawBuffers.targets(),
            resolution, distance, details.properties().shadowCulling().orElse(true));
    }

    private static PreparedTerrainPack.ShadowProgram adaptShadow(SpirvCompiler compiler,
        TranslatedShadow shadow, RenderTargetDirectives directives, TerrainUniformLayout uniforms,
        SpirvCompiler.Optimisation optimisation) throws CompilationException {
        String vertex = TerrainShaderAdapter.adapt(shadow.vertex(), uniforms, true);
        String fragment = TerrainShaderAdapter.adapt(shadow.fragment(), uniforms, false);
        validate(compiler, "shadow.vsh", vertex, ShaderStage.VERTEX, optimisation);
        validate(compiler, "shadow.fsh", fragment, ShaderStage.FRAGMENT, optimisation);
        Map<Integer, RenderTargetDirectives.TargetSettings> colors = new LinkedHashMap<>();
        for (int index : shadow.drawTargets()) {
            colors.put(index, directives.shadowColorTarget(index));
        }
        return new PreparedTerrainPack.ShadowProgram(shadow.sourceName(), vertex, fragment,
            shadow.drawTargets(), colors, shadow.resolution(), shadow.distance(), shadow.cull());
    }

    private static int shadowInt(String source, int fallback) {
        Matcher matcher = SHADOW_INT.matcher(source);
        return matcher.find() ? Integer.parseInt(matcher.group(2)) : fallback;
    }

    private static float shadowFloat(String source, float fallback) {
        Matcher matcher = SHADOW_FLOAT.matcher(source);
        return matcher.find() ? Float.parseFloat(matcher.group(2)) : fallback;
    }

    private static void translateProgram(
        Map<PreparedTerrainPack.PassKind, TranslatedProgram> output,
        PreparedTerrainPack.PassKind kind, ProgramId requested,
        ShaderPreprocessor preprocessor, OptionApplier optionApplier, PackSource source,
        PackPath shadersRoot, PackManager.PackDetails details, VulkanTranslator translator,
        RenderTargetDirectives targetDirectives, List<String> diagnostics)
        throws IOException, CompilationException {
        ProgramId resolved = resolveProgram(requested, source, shadersRoot);
        String name = resolved.sourceName();
        PackPath vertexPath = shadersRoot.resolve(name + ".vsh");
        PackPath fragmentPath = shadersRoot.resolve(name + ".fsh");
        PreprocessedSource vertexPreprocessed = preprocessor.process(vertexPath, DEFINES,
            optionApplier);
        PreprocessedSource fragmentPreprocessed = preprocessor.process(fragmentPath, DEFINES,
            optionApplier);
        applyTargetDirectives(targetDirectives, vertexPreprocessed.text());
        applyTargetDirectives(targetDirectives, fragmentPreprocessed.text());

        DrawBuffersDirective.Result found = DrawBuffersDirective.find(
            GlslLexer.tokenize(fragmentPreprocessed.text()));
        diagnostics.addAll(found.problems());
        DrawBuffersDirective drawBuffers = details.properties().programOverrides()
            .drawBufferOverride().containsKey(name)
            ? new DrawBuffersDirective(details.properties().programOverrides()
                .drawBufferOverride().get(name), DrawBuffersDirective.Form.RENDERTARGETS, -1)
            : found.directive();
        validateDrawTargets(name, drawBuffers.targets());

        List<VaryingLayout.Declaration> varyingDeclarations = new ArrayList<>();
        varyingDeclarations.addAll(VulkanTranslator.collectVaryings(
            vertexPreprocessed.text(), ShaderStage.VERTEX));
        varyingDeclarations.addAll(VulkanTranslator.collectVaryings(
            fragmentPreprocessed.text(), ShaderStage.FRAGMENT));
        VaryingLayout.Build varyingBuild = VaryingLayout.build(varyingDeclarations);
        diagnostics.addAll(varyingBuild.problems());

        AlphaTest alpha = details.properties().programOverrides().alphaTest()
            .getOrDefault(name, kind == PreparedTerrainPack.PassKind.CUTOUT
                ? AlphaTest.VANILLA_CUTOUT : AlphaTest.ALWAYS);
        VulkanTranslator.Options vertexOptions = new VulkanTranslator.Options(
            ShaderStage.VERTEX, drawBuffers, AlphaTest.ALWAYS, false, 450,
            varyingBuild.layout());
        VulkanTranslator.Options fragmentOptions = new VulkanTranslator.Options(
            ShaderStage.FRAGMENT, drawBuffers, alpha, false, 450, varyingBuild.layout());
        TranslatedSource vertex = translator.translate(vertexPreprocessed.text(), vertexOptions);
        TranslatedSource fragment = translator.translate(fragmentPreprocessed.text(),
            fragmentOptions);
        diagnostics.addAll(vertex.warnings());
        diagnostics.addAll(fragment.warnings());
        rejectUnboundResources(name, vertex, fragment);

        String passName = switch (kind) {
            case SOLID -> "solid";
            case CUTOUT -> "cutout";
            case TRANSLUCENT -> "translucent";
        };
        boolean cull = details.properties().programOverrides().backFaceCulling()
            .getOrDefault(passName, true);
        output.put(kind, new TranslatedProgram(name, vertex, fragment,
            drawBuffers.targets(), cull));
    }

    private static TranslatedPost translateOptionalPost(
        String name, boolean finalPass, ShaderPreprocessor preprocessor,
        OptionApplier optionApplier, PackSource source, PackPath shadersRoot,
        PackManager.PackDetails details, VulkanTranslator translator,
        RenderTargetDirectives targets,
        Map<String, RenderTargetDirectives.PassScale> scales, List<String> diagnostics)
        throws IOException, CompilationException {
        PackPath vertexPath = shadersRoot.resolve(name + ".vsh");
        PackPath fragmentPath = shadersRoot.resolve(name + ".fsh");
        // exists() is an index probe. readText().isPresent() decompresses the whole entry, and
        // the numbered-pass scan asks about hundreds of files that are usually absent.
        boolean hasVertex = source.exists(vertexPath);
        boolean hasFragment = source.exists(fragmentPath);
        if (!hasVertex && !hasFragment) {
            return null;
        }
        if (!hasVertex || !hasFragment) {
            throw new CompilationException(name + " must supply both .vsh and .fsh");
        }
        PreprocessedSource vertexPreprocessed = preprocessor.process(vertexPath, DEFINES,
            optionApplier);
        PreprocessedSource fragmentPreprocessed = preprocessor.process(fragmentPath, DEFINES,
            optionApplier);
        applyTargetDirectives(targets, vertexPreprocessed.text());
        applyTargetDirectives(targets, fragmentPreprocessed.text());

        DrawBuffersDirective.Result found = DrawBuffersDirective.find(
            GlslLexer.tokenize(fragmentPreprocessed.text()));
        diagnostics.addAll(found.problems());
        DrawBuffersDirective drawBuffers = details.properties().programOverrides()
            .drawBufferOverride().containsKey(name)
            ? new DrawBuffersDirective(details.properties().programOverrides()
                .drawBufferOverride().get(name), DrawBuffersDirective.Form.RENDERTARGETS, -1)
            : found.directive();
        validateDrawTargets(name, drawBuffers.targets());
        if (finalPass && drawBuffers.attachmentCount() != 1) {
            throw new CompilationException("final requests " + drawBuffers.attachmentCount()
                + " outputs; the final pass writes the single Minecraft presentation target");
        }

        List<VaryingLayout.Declaration> varyingDeclarations = new ArrayList<>();
        varyingDeclarations.addAll(VulkanTranslator.collectVaryings(
            vertexPreprocessed.text(), ShaderStage.VERTEX));
        varyingDeclarations.addAll(VulkanTranslator.collectVaryings(
            fragmentPreprocessed.text(), ShaderStage.FRAGMENT));
        VaryingLayout.Build varyingBuild = VaryingLayout.build(varyingDeclarations);
        diagnostics.addAll(varyingBuild.problems());
        TranslatedSource vertex = translator.translate(vertexPreprocessed.text(),
            new VulkanTranslator.Options(ShaderStage.VERTEX, drawBuffers, AlphaTest.ALWAYS,
                false, 450, varyingBuild.layout()));
        TranslatedSource fragment = translator.translate(fragmentPreprocessed.text(),
            new VulkanTranslator.Options(ShaderStage.FRAGMENT, drawBuffers, AlphaTest.ALWAYS,
                false, 450, varyingBuild.layout()));
        diagnostics.addAll(vertex.warnings());
        diagnostics.addAll(fragment.warnings());
        Map<String, String> samplerTypes = postSamplers(name, vertex, fragment);
        return new TranslatedPost(name, vertex, fragment, drawBuffers.targets(),
            List.copyOf(samplerTypes.keySet()), samplerTypes,
            scales.getOrDefault(name, new RenderTargetDirectives.PassScale(1.0f, 0.0f, 0.0f)),
            readFlips(details, name, drawBuffers.targets()), false);
    }

    private static TranslatedPost translateInternalFinal(VulkanTranslator translator,
                                                         RenderTargetDirectives targets,
                                                         List<String> diagnostics)
        throws CompilationException {
        String vertexSource = """
            #version 120
            varying vec2 texcoord;
            void main() {
                gl_Position = ftransform();
                texcoord = gl_MultiTexCoord0.xy;
            }
            """;
        String fragmentSource = """
            #version 120
            uniform sampler2D colortex0;
            varying vec2 texcoord;
            void main() {
                gl_FragColor = texture2D(colortex0, texcoord);
            }
            """;
        DrawBuffersDirective drawBuffers = DrawBuffersDirective.defaultTargets();
        List<VaryingLayout.Declaration> declarations = new ArrayList<>();
        declarations.addAll(VulkanTranslator.collectVaryings(vertexSource, ShaderStage.VERTEX));
        declarations.addAll(VulkanTranslator.collectVaryings(fragmentSource,
            ShaderStage.FRAGMENT));
        VaryingLayout.Build varying = VaryingLayout.build(declarations);
        diagnostics.addAll(varying.problems());
        TranslatedSource vertex = translator.translate(vertexSource,
            new VulkanTranslator.Options(ShaderStage.VERTEX, drawBuffers, AlphaTest.ALWAYS,
                false, 450, varying.layout()));
        TranslatedSource fragment = translator.translate(fragmentSource,
            new VulkanTranslator.Options(ShaderStage.FRAGMENT, drawBuffers, AlphaTest.ALWAYS,
                false, 450, varying.layout()));
        targets.colorTarget(0);
        return new TranslatedPost("retina_internal_final", vertex, fragment, List.of(0),
            List.of("colortex0"), Map.of("colortex0", "sampler2D"),
            new RenderTargetDirectives.PassScale(1, 0, 0),
            Map.of(), true);
    }

    private static PreparedTerrainPack.PostProgram adaptPost(
        SpirvCompiler compiler, TranslatedPost post, TerrainUniformLayout uniforms,
        SpirvCompiler.Optimisation optimisation) throws CompilationException {
        String vertex = FullscreenShaderAdapter.adapt(post.vertex(), uniforms, true);
        String fragment = FullscreenShaderAdapter.adapt(post.fragment(), uniforms, false);
        validate(compiler, post.sourceName() + ".vsh", vertex, ShaderStage.VERTEX,
            optimisation);
        validate(compiler, post.sourceName() + ".fsh", fragment, ShaderStage.FRAGMENT,
            optimisation);
        return new PreparedTerrainPack.PostProgram(post.sourceName(), vertex, fragment,
            post.drawTargets(), post.samplers(), post.samplerTypes(), post.scale(), post.flips(),
            post.internal());
    }

    private static PreparedTerrainPack.EntityProgram adaptEntity(SpirvCompiler compiler,
        TranslatedEntity entity, SpirvCompiler.Optimisation optimisation)
        throws CompilationException {
        String vertex;
        String fragment;
        try {
            vertex = EntityShaderAdapter.adapt(entity.vertex(), true);
            fragment = EntityShaderAdapter.adapt(entity.fragment(), false);
        } catch (IllegalArgumentException e) {
            throw new CompilationException(entity.sourceName() + " cannot use Minecraft's "
                + "standard entity ABI: " + e.getMessage(), e);
        }
        validate(compiler, entity.sourceName() + ".vsh", vertex, ShaderStage.VERTEX,
            optimisation);
        validate(compiler, entity.sourceName() + ".fsh", fragment, ShaderStage.FRAGMENT,
            optimisation);
        return new PreparedTerrainPack.EntityProgram(entity.sourceName(), vertex, fragment,
            entity.cull());
    }

    private static PreparedTerrainPack.ParticleProgram adaptParticle(SpirvCompiler compiler,
        TranslatedParticle particle, SpirvCompiler.Optimisation optimisation)
        throws CompilationException {
        String vertex;
        String fragment;
        try {
            vertex = ParticleShaderAdapter.adapt(particle.vertex(), true);
            fragment = ParticleShaderAdapter.adapt(particle.fragment(), false);
        } catch (IllegalArgumentException e) {
            throw new CompilationException(particle.sourceName() + " cannot use Minecraft's "
                + "particle ABI: " + e.getMessage(), e);
        }
        validate(compiler, particle.sourceName() + ".vsh", vertex, ShaderStage.VERTEX,
            optimisation);
        validate(compiler, particle.sourceName() + ".fsh", fragment, ShaderStage.FRAGMENT,
            optimisation);
        return new PreparedTerrainPack.ParticleProgram(particle.sourceName(), vertex, fragment,
            particle.cull());
    }

    private static PreparedTerrainPack.WeatherProgram adaptWeather(SpirvCompiler compiler,
        TranslatedParticle weather, SpirvCompiler.Optimisation optimisation)
        throws CompilationException {
        PreparedTerrainPack.ParticleProgram adapted = adaptParticle(compiler, weather, optimisation);
        return new PreparedTerrainPack.WeatherProgram(adapted.sourceName(), adapted.vertexSource(),
            adapted.fragmentSource(), adapted.cull());
    }

    private static final Set<String> POST_EXTRA_RESOURCES =
        Set.of("normals", "specular", "noisetex", "depthtex0");

    private static Map<String, String> postSamplers(String program, TranslatedSource... stages)
        throws CompilationException {
        Map<String, String> samplers = new LinkedHashMap<>();
        for (TranslatedSource stage : stages) {
            for (BindingLayout.Binding binding : stage.bindings()) {
                String resource = BindingLayout.canonicalName(binding.name());
                // depthtex0 is real data (ShaderRuntime.executePost reads the main depth
                // attachment directly); normals/specular/noisetex are the same flat fallback
                // bindPbrDefaults gives every gbuffer/shadow stage. depthtex1/depthtex2 and
                // colortex-as-input for gbuffer stages are not implemented -- see README.
                if (!resource.matches("colortex(?:[0-9]|1[0-5])")
                    && !resource.matches("shadowtex[01]|shadow|shadowcolor[01]")
                    && !POST_EXTRA_RESOURCES.contains(resource)) {
                    throw new CompilationException(program + " uses resource '" + resource
                        + "' (" + binding.glslType() + "); composite/final currently bind"
                        + " colortex, shadow-map, normals/specular/noisetex, and depthtex0"
                        + " samplers only");
                }
                String previous = samplers.putIfAbsent(resource, binding.glslType());
                if (previous != null && !previous.equals(binding.glslType())) {
                    throw new CompilationException(program + " declares " + resource
                        + " with incompatible sampler types " + previous + " and "
                        + binding.glslType());
                }
            }
        }
        // The sampler list is used to assign bind-group slots, so retain the
        // source declaration order while still returning an immutable view.
        return Collections.unmodifiableMap(samplers);
    }

    private static List<Integer> colortexIndices(List<String> samplers) {
        return samplers.stream().filter(name -> name.startsWith("colortex"))
            .map(name -> Integer.parseInt(name.substring("colortex".length())))
            .toList();
    }

    private static Map<Integer, PreparedTerrainPack.TargetPlan> buildTargetPlans(
        Set<Integer> referenced, RenderTargetDirectives directives,
        PackManager.PackDetails details,
        Map<PreparedTerrainPack.PassKind, PreparedTerrainPack.Program> terrainPrograms,
        List<PreparedTerrainPack.PostProgram> postPrograms) throws CompilationException {
        Map<Integer, PreparedTerrainPack.TargetPlan> plans = new LinkedHashMap<>();
        for (int index : referenced) {
            RenderTargetDirectives.TargetSettings settings = directives.colorTarget(index);
            float width = 1.0f;
            float height = 1.0f;
            boolean relative = true;
            String key = "size.buffer.colortex" + index;
            String raw = details.properties().raw().get(key).orElse(null);
            if (raw != null) {
                String[] parts = raw.trim().split("[\\s,]+");
                if (parts.length != 2) {
                    throw new CompilationException(key + " expects width and height");
                }
                try {
                    boolean integerWidth = parts[0].matches("[0-9]+");
                    boolean integerHeight = parts[1].matches("[0-9]+");
                    if (integerWidth != integerHeight) {
                        throw new CompilationException(key
                            + " must use either two integer pixels or two relative decimals");
                    }
                    relative = !integerWidth;
                    width = Float.parseFloat(parts[0]);
                    height = Float.parseFloat(parts[1]);
                } catch (NumberFormatException e) {
                    throw new CompilationException(key + " could not be read from '" + raw + "'");
                }
                if (!(width > 0) || !(height > 0)) {
                    throw new CompilationException(key + " dimensions must be positive");
                }
            }
            plans.put(index, new PreparedTerrainPack.TargetPlan(settings, width, height,
                relative));
        }

        Set<Integer> terrainTargets = new LinkedHashSet<>();
        terrainPrograms.values().forEach(program -> terrainTargets.addAll(program.drawTargets()));
        for (int index : terrainTargets) {
            PreparedTerrainPack.TargetPlan plan = plans.get(index);
            if (plan != null && (!plan.relativeSize() || plan.width() != 1.0f
                || plan.height() != 1.0f)) {
                throw new CompilationException("colortex" + index
                    + " has a custom size but is written by a terrain program; gbuffers"
                    + " attachments must match Minecraft's depth target dimensions");
            }
        }
        for (PreparedTerrainPack.PostProgram program : postPrograms) {
            PreparedTerrainPack.TargetPlan first = null;
            for (int index : program.drawTargets()) {
                PreparedTerrainPack.TargetPlan plan = plans.get(index);
                if (first == null) {
                    first = plan;
                } else if (plan.relativeSize() != first.relativeSize()
                    || plan.width() != first.width() || plan.height() != first.height()) {
                    throw new CompilationException(program.sourceName()
                        + " writes attachments with different sizes");
                }
            }
        }
        return Map.copyOf(plans);
    }

    private static Map<Integer, Boolean> readFlips(PackManager.PackDetails details,
                                                   String program,
                                                   List<Integer> drawTargets)
        throws CompilationException {
        Map<Integer, Boolean> flips = new LinkedHashMap<>();
        for (int index : drawTargets) {
            String key = "flip." + program + ".colortex" + index;
            String raw = details.properties().raw().get(key).orElse("true").trim();
            boolean flip = switch (raw.toLowerCase(java.util.Locale.ROOT)) {
                case "true", "on", "1", "yes" -> true;
                case "false", "off", "0", "no" -> false;
                default -> throw new CompilationException(key + " expects true/false but found '"
                    + raw + "'");
            };
            flips.put(index, flip);
        }
        return Map.copyOf(flips);
    }

    private static void validateDrawTargets(String program, List<Integer> targets)
        throws CompilationException {
        if (targets.size() > MAX_COLOR_ATTACHMENTS) {
            throw new CompilationException(program + " requests " + targets.size()
                + " simultaneous color attachments; Retina's Blaze3D Vulkan path supports "
                + MAX_COLOR_ATTACHMENTS);
        }
        if (new LinkedHashSet<>(targets).size() != targets.size()) {
            throw new CompilationException(program
                + " requests the same colortex attachment more than once");
        }
    }

    private static void applyTargetDirectives(RenderTargetDirectives directives, String source) {
        Matcher matcher = CONST_DIRECTIVE.matcher(source);
        while (matcher.find()) {
            directives.applyConstDirective(matcher.group(1), matcher.group(2));
        }
    }

    private static ProgramId resolveProgram(ProgramId requested, PackSource source,
                                            PackPath root) throws IOException,
        CompilationException {
        for (ProgramId candidate : requested.fallbackChain()) {
            PackPath vertex = root.resolve(candidate.sourceName() + ".vsh");
            PackPath fragment = root.resolve(candidate.sourceName() + ".fsh");
            if (source.exists(vertex) && source.exists(fragment)) {
                return candidate;
            }
        }
        throw new CompilationException("pack supplies no complete vertex/fragment program for "
            + requested.sourceName() + " or its fallback chain");
    }

    // gtexture/lightmap are supplied by renaming onto Sodium's/vanilla's own bound resources
    // (TerrainShaderAdapter, EntityShaderAdapter, ParticleShaderAdapter); normals/specular/
    // noisetex are supplied by ShaderRuntime's own RETINA_PBR_SAMPLERS bind group and flat
    // fallback textures, added to every one of these pipelines. shadowtex0/shadowtex1/shadow/
    // shadowcolor0/shadowcolor1 are real data too, now that ShaderRuntime renders the terrain
    // shadow map at the start of the frame, before the main scene draws
    // (ShaderRuntime.renderIndependentTerrainShadows, called from beginWorldFrame) -- except for
    // the shadow program itself, excluded below, which is what is currently writing that same
    // shadow map this same draw. Anything else declared here has nothing behind it -- this must
    // track what ShaderRuntime actually binds for these pipelines, or a resource it does bind
    // gets refused here before ever reaching that code (as normals/specular did until this
    // comment was added, and as noisetex did until a later fix).
    private static final Set<String> BOUND_RESOURCES =
        Set.of("gtexture", "lightmap", "normals", "specular", "noisetex",
            "shadowtex0", "shadowtex1", "shadow", "shadowcolor0", "shadowcolor1");

    private static void rejectUnboundResources(String name, TranslatedSource... stages)
        throws CompilationException {
        for (TranslatedSource stage : stages) {
            for (BindingLayout.Binding binding : stage.bindings()) {
                String resource = binding.name();
                // The shadow program is what is currently writing the shadow map this same
                // draw; sampling it from within that same draw is the same same-pass hazard
                // depthtex0 has for every other caller of this gate, just for the shadow
                // attachment instead of the main one.
                boolean shadowSamplingItself = name.equals("shadow")
                    && resource.matches("shadowtex[01]|shadow|shadowcolor[01]");
                if (!BOUND_RESOURCES.contains(resource) || shadowSamplingItself) {
                    throw new CompilationException(name + " uses resource '" + resource
                        + "' (" + binding.glslType() + "), which the Sodium terrain pass"
                        + " cannot bind yet" + unboundReason(name, resource));
                }
            }
        }
    }

    /**
     * A specific reason for a name {@link BindingLayout} recognises but this gbuffer-stage gate
     * still refuses, so the message says whether a refusal is a missing allowlist entry (which
     * would be a bug -- see the audit note on {@link #BOUND_RESOURCES}) or a real architecture
     * gap. Keep in sync with what {@code postSamplers} allows and {@code ShaderRuntime.executePost}
     * actually binds, and with what {@code BOUND_RESOURCES} and {@code rejectUnboundResources}'s
     * {@code shadowSamplingItself} check actually permit. Unrecognised, pack-custom resource
     * names fall through to no reason at all.
     *
     * <p>{@code program} matters for both {@code depthtex0} and the shadow-map names now.
     * Terrain/entities/particles/weather run as part of Minecraft's normal world render pass;
     * {@code shadow} does not -- {@code ShaderRuntime.renderIndependentTerrainShadows} now runs
     * it at the very start of the frame (from {@code beginWorldFrame}), before that normal
     * render pass has drawn anything at all this frame. That is why {@code depthtex0} is
     * refused for a different reason for {@code shadow} than for everyone else, and why the
     * shadow-map names themselves are refused only for {@code shadow} -- it is the one program
     * still writing the very attachment those names would read, this same draw.
     */
    private static String unboundReason(String program, String resource) {
        if (resource.matches("shadowtex[01]|shadow|shadowcolor[01]")) {
            if (program.equals("shadow")) {
                return ": the shadow program is what is currently writing the shadow map this"
                    + " same draw; sampling it from within that same draw is not implemented,"
                    + " the same same-pass hazard depthtex0 has for every other program, just"
                    + " for this attachment instead";
            }
            return ": this name is normally bound by now -- if you are seeing this, the"
                + " allowlist and this message have gone out of sync, please report it";
        }
        if (resource.equals("depthtex0")) {
            if (program.equals("shadow")) {
                return ": the shadow program now runs at the start of the frame, before the"
                    + " main scene has drawn anything at all this frame, so depthtex0 does not"
                    + " hold this frame's main scene depth at this point either -- and it"
                    + " writes its own separate shadow depth attachment regardless, not this one";
            }
            return ": this stage is still writing the depth attachment depthtex0 would read;"
                + " only post-processing programs can read it";
        }
        if (resource.matches("depthtex[12]")) {
            return ": a separate opaque-only/no-handheld-item depth snapshot is not captured"
                + " anywhere yet";
        }
        if (resource.matches("colortex(?:[0-9]|1[0-5])")) {
            return ": reading a colortex target as an input during a gbuffer stage is not"
                + " implemented yet";
        }
        return "";
    }

    private static void validate(SpirvCompiler compiler, String name, String source,
                                 ShaderStage stage, SpirvCompiler.Optimisation optimisation)
        throws CompilationException {
        SpirvCompiler.Result result = compiler.compile(source, stage, name, optimisation,
            List.of());
        try {
            if (!result.ok()) {
                throw new CompilationException(name + " failed Vulkan GLSL validation:\n"
                    + result.diagnostics());
            }
        } finally {
            SpirvCompiler.free(result);
        }
    }

    private static SpirvCompiler.Optimisation optimisation(RetinaConfig.RendererProfile profile) {
        return switch (profile) {
            case DEBUG -> SpirvCompiler.Optimisation.NONE;
            case COMPATIBILITY, BALANCED -> SpirvCompiler.Optimisation.SIZE;
            case PERFORMANCE -> SpirvCompiler.Optimisation.PERFORMANCE;
        };
    }

    private static String usefulMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName()
            : throwable.getMessage();
    }
}
