/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core;

import dev.retina.core.cache.CacheKey;
import dev.retina.core.graph.RenderGraph;
import dev.retina.core.graph.TransientAllocator;
import dev.retina.core.material.IdMap;
import dev.retina.core.program.DimensionId;
import dev.retina.core.program.ProgramId;
import dev.retina.core.props.ShaderProperties;
import dev.retina.core.state.AlphaTest;
import dev.retina.core.state.BlendMode;
import dev.retina.core.target.RenderTargetDirectives;
import dev.retina.core.target.TargetFormat;
import dev.retina.core.uniform.UniformSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Behaviour tests for the parts of the shader-pack contract that decide what a pack draws. */
class CompatibilityContractTest {

    @Nested
    @DisplayName("program fallback")
    class Fallbacks {

        @Test
        @DisplayName("every gbuffer program falls back to gbuffers_basic")
        void gbufferChainTerminatesAtBasic() {
            for (ProgramId id : ProgramId.values()) {
                if (!id.sourceName().startsWith("gbuffers_")) {
                    continue;
                }
                List<ProgramId> chain = id.fallbackChain();
                assertEquals(ProgramId.GBUFFERS_BASIC, chain.getLast(),
                    () -> id + " should fall back to gbuffers_basic, chain was " + chain);
            }
        }

        @Test
        @DisplayName("the documented intermediate fallbacks are exact")
        void specificChains() {
            assertEquals(
                List.of(ProgramId.GBUFFERS_WATER, ProgramId.GBUFFERS_TERRAIN,
                    ProgramId.GBUFFERS_TEXTURED_LIT, ProgramId.GBUFFERS_TEXTURED,
                    ProgramId.GBUFFERS_BASIC),
                ProgramId.GBUFFERS_WATER.fallbackChain());
            assertEquals(
                List.of(ProgramId.GBUFFERS_HAND_WATER, ProgramId.GBUFFERS_HAND,
                    ProgramId.GBUFFERS_TEXTURED_LIT, ProgramId.GBUFFERS_TEXTURED,
                    ProgramId.GBUFFERS_BASIC),
                ProgramId.GBUFFERS_HAND_WATER.fallbackChain());
        }

        @Test
        @DisplayName("no fallback chain can loop")
        void chainsTerminate() {
            for (ProgramId id : ProgramId.values()) {
                List<ProgramId> chain = id.fallbackChain();
                assertEquals(chain.size(), chain.stream().distinct().count(),
                    () -> id + " has a cyclic fallback chain: " + chain);
            }
        }

        @Test
        @DisplayName("chained program names round-trip through their numeric suffix")
        void chainedNames() {
            assertEquals(new ProgramId.ProgramRef(ProgramId.COMPOSITE, 3),
                ProgramId.parseSourceName("composite3").orElseThrow());
            assertEquals("composite3",
                new ProgramId.ProgramRef(ProgramId.COMPOSITE, 3).sourceName());
            assertEquals(new ProgramId.ProgramRef(ProgramId.COMPOSITE, 0),
                ProgramId.parseSourceName("composite").orElseThrow());
            assertEquals("composite",
                new ProgramId.ProgramRef(ProgramId.COMPOSITE, 0).sourceName());
            // An unchained program with a numeric suffix is not a program.
            assertTrue(ProgramId.parseSourceName("gbuffers_terrain3").isEmpty());
            assertTrue(ProgramId.parseSourceName("not_a_program").isEmpty());
        }

        @Test
        @DisplayName("dimension folders map to the historical numeric names")
        void dimensionFolders() {
            assertEquals(DimensionId.OVERWORLD,
                DimensionId.forDimensionKey("minecraft", "overworld"));
            assertEquals("world-1",
                DimensionId.forDimensionKey("minecraft", "the_nether").folderName());
            assertEquals("world1",
                DimensionId.forDimensionKey("minecraft", "the_end").folderName());
            // A modded dimension gets a sanitised namespaced folder.
            assertEquals("worldmymod_crystal_realm",
                DimensionId.forDimensionKey("mymod", "crystal_realm").folderName());
        }
    }

    @Nested
    @DisplayName("render state translation")
    class StateTranslation {

        @Test
        @DisplayName("two-factor and four-factor blend forms both parse")
        void blendForms() {
            BlendMode two = BlendMode.parse("SRC_ALPHA ONE_MINUS_SRC_ALPHA").mode().orElseThrow();
            assertEquals(BlendMode.Factor.SRC_ALPHA, two.srcRgb());
            assertEquals(BlendMode.Factor.ONE_MINUS_SRC_ALPHA, two.dstAlpha());

            BlendMode four = BlendMode.parse("SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO")
                .mode().orElseThrow();
            assertEquals(BlendMode.Factor.ONE, four.srcAlpha());
            assertEquals(BlendMode.Factor.ZERO, four.dstAlpha());

            assertFalse(BlendMode.parse("off").mode().orElseThrow().enabled());
            assertTrue(BlendMode.parse("GL_SRC_ALPHA GL_ONE").ok(), "GL_ prefixes are tolerated");
        }

        @Test
        @DisplayName("an illegal blend combination is refused with a reason")
        void illegalBlendIsRefused() {
            BlendMode.Parsed parsed = BlendMode.parse("ONE SRC_ALPHA_SATURATE");
            assertFalse(parsed.ok());
            assertTrue(parsed.error().contains("SRC_ALPHA_SATURATE"), parsed.error());

            assertFalse(BlendMode.parse("SRC_ALPHA").ok(), "one factor is not a blend mode");
            assertFalse(BlendMode.parse("NOT_A_FACTOR ONE").ok());
        }

        @Test
        @DisplayName("dual-source blending is flagged as a capability requirement")
        void dualSourceIsFlagged() {
            BlendMode mode = BlendMode.parse("SRC1_COLOR ONE_MINUS_SRC1_COLOR")
                .mode().orElseThrow();
            assertTrue(mode.requiresDualSourceBlend());
            assertFalse(BlendMode.DEFAULT.requiresDualSourceBlend());
        }

        @Test
        @DisplayName("alpha test becomes a discard condition, not a pipeline bit")
        void alphaTestBecomesDiscard() {
            AlphaTest test = AlphaTest.parse("GREATER 0.1").test().orElseThrow();
            assertTrue(test.discards());
            assertEquals("!(a > 0.1)", test.discardCondition("a"));
            assertFalse(AlphaTest.parse("off").test().orElseThrow().discards());
            assertEquals("false", AlphaTest.ALWAYS.discardCondition("a"));
        }

        @Test
        @DisplayName("every declared target format has a Vulkan equivalent or says it does not")
        void targetFormats() {
            assertEquals(97, TargetFormat.RGBA16F.vkFormat().orElseThrow(),
                "RGBA16F must map to VK_FORMAT_R16G16B16A16_SFLOAT");
            assertEquals("u", TargetFormat.RGBA32UI.samplerPrefix());
            assertEquals("i", TargetFormat.R32I.samplerPrefix());
            assertEquals("", TargetFormat.RGBA8.samplerPrefix());
            assertFalse(TargetFormat.RGB16F.supportsImageStore(),
                "three-component float formats are not storage-image capable");
            assertTrue(TargetFormat.parse("GL_RGBA16F").isPresent());
            assertTrue(TargetFormat.parse("NOT_A_FORMAT").isEmpty());
        }
    }

    @Nested
    @DisplayName("render targets")
    class Targets {

        @Test
        @DisplayName("const directives configure format, clear and clear colour")
        void constDirectives() {
            RenderTargetDirectives directives = new RenderTargetDirectives();
            assertTrue(directives.applyConstDirective("colortex0Format", "RGBA16F"));
            assertTrue(directives.applyConstDirective("colortex0Clear", "false"));
            assertTrue(directives.applyConstDirective("colortex1ClearColor",
                "vec4(1.0, 0.0, 0.0, 1.0)"));

            assertEquals(TargetFormat.RGBA16F, directives.colorTarget(0).format());
            assertFalse(directives.colorTarget(0).clear());
            assertArrayEquals(new float[]{1f, 0f, 0f, 1f},
                directives.colorTarget(1).clearColorCopy(), 1e-6f);
            // An unconfigured target still has usable defaults.
            assertEquals(TargetFormat.RGBA8, directives.colorTarget(5).format());
            assertTrue(directives.colorTarget(5).clear());
        }

        @Test
        @DisplayName("legacy target names configure the same targets as colortex0..7")
        void legacyTargetNames() {
            RenderTargetDirectives directives = new RenderTargetDirectives();
            assertTrue(directives.applyConstDirective("gnormalFormat", "RGBA16"));
            assertEquals(TargetFormat.RGBA16, directives.colorTarget(2).format(),
                "gnormal is colortex2");
        }

        @Test
        @DisplayName("an out-of-range or unknown format is reported, not silently substituted")
        void badDirectivesAreReported() {
            RenderTargetDirectives directives = new RenderTargetDirectives();
            assertFalse(directives.applyConstDirective("colortex0Format", "RGBA99F"));
            assertFalse(directives.applyConstDirective("colortex99Format", "RGBA8"));
            assertEquals(2, directives.problems().size(), () -> directives.problems().toString());
        }
    }

    @Nested
    @DisplayName("render graph")
    class Graph {

        private static RenderGraph.Resource colour(String name, boolean persistent) {
            return new RenderGraph.Resource(name, RenderGraph.ResourceKind.COLOR_TARGET,
                1920, 1080, 97, persistent);
        }

        @Test
        @DisplayName("a pass that samples an image it also writes is refused")
        void readWriteHazardIsRefused() {
            RenderGraph graph = new RenderGraph.Builder()
                .resource(colour("colortex0", true))
                .pass(new RenderGraph.Pass("composite0", RenderGraph.PassKind.FULLSCREEN,
                    List.of(
                        new RenderGraph.ResourceUse("colortex0", RenderGraph.Access.SAMPLE),
                        new RenderGraph.ResourceUse("colortex0",
                            RenderGraph.Access.COLOR_ATTACHMENT)),
                    true))
                .build();

            assertFalse(graph.isValid());
            assertTrue(graph.problems().getFirst().contains("ping-pong"),
                graph.problems().getFirst());
        }

        @Test
        @DisplayName("reading a transient resource nothing has written is refused")
        void uninitialisedReadIsRefused() {
            RenderGraph graph = new RenderGraph.Builder()
                .resource(colour("scratch", false))
                .resource(colour("out", false))
                .pass(new RenderGraph.Pass("composite0", RenderGraph.PassKind.FULLSCREEN,
                    List.of(
                        new RenderGraph.ResourceUse("scratch", RenderGraph.Access.SAMPLE),
                        new RenderGraph.ResourceUse("out",
                            RenderGraph.Access.COLOR_ATTACHMENT)),
                    true))
                .build();

            assertFalse(graph.isValid());
            assertTrue(graph.problems().getFirst().contains("before anything has written it"),
                graph.problems().getFirst());
        }

        @Test
        @DisplayName("reading a history resource before any write is allowed")
        void historyReadsAreAllowed() {
            RenderGraph graph = new RenderGraph.Builder()
                .resource(colour("colortex0", true))
                .resource(colour("colortex1", false))
                .pass(new RenderGraph.Pass("composite0", RenderGraph.PassKind.FULLSCREEN,
                    List.of(
                        new RenderGraph.ResourceUse("colortex0", RenderGraph.Access.SAMPLE),
                        new RenderGraph.ResourceUse("colortex1",
                            RenderGraph.Access.COLOR_ATTACHMENT)),
                    true))
                .build();
            assertTrue(graph.isValid(), () -> graph.problems().toString());
        }

        @Test
        @DisplayName("a layout transition between passes produces exactly one barrier")
        void barriersAreInserted() {
            RenderGraph graph = new RenderGraph.Builder()
                .resource(colour("colortex0", false))
                .resource(colour("colortex1", false))
                .pass(new RenderGraph.Pass("gbuffers", RenderGraph.PassKind.GEOMETRY,
                    List.of(new RenderGraph.ResourceUse("colortex0",
                        RenderGraph.Access.COLOR_ATTACHMENT)), true))
                .pass(new RenderGraph.Pass("composite0", RenderGraph.PassKind.FULLSCREEN,
                    List.of(
                        new RenderGraph.ResourceUse("colortex0", RenderGraph.Access.SAMPLE),
                        new RenderGraph.ResourceUse("colortex1",
                            RenderGraph.Access.COLOR_ATTACHMENT)),
                    true))
                .build();

            assertTrue(graph.isValid(), () -> graph.problems().toString());
            List<RenderGraph.Barrier> barriers = graph.schedule().get(1).barriers();
            assertEquals(1, barriers.size(), () -> barriers.toString());
            RenderGraph.Barrier barrier = barriers.getFirst();
            assertEquals("colortex0", barrier.resource());
            assertEquals(2, barrier.fromLayout(), "COLOR_ATTACHMENT_OPTIMAL");
            assertEquals(5, barrier.toLayout(), "SHADER_READ_ONLY_OPTIMAL");
        }

        @Test
        @DisplayName("disabled passes are skipped entirely")
        void disabledPassesAreSkipped() {
            RenderGraph graph = new RenderGraph.Builder()
                .resource(colour("colortex0", false))
                .pass(new RenderGraph.Pass("composite5", RenderGraph.PassKind.FULLSCREEN,
                    List.of(new RenderGraph.ResourceUse("colortex0",
                        RenderGraph.Access.COLOR_ATTACHMENT)), false))
                .build();
            assertTrue(graph.schedule().isEmpty());
        }

        @Test
        @DisplayName("transient resources with disjoint lifetimes share memory")
        void transientAliasing() {
            RenderGraph graph = new RenderGraph.Builder()
                .resource(colour("seed", true))
                .resource(colour("tmpA", false))
                .resource(colour("tmpB", false))
                .pass(new RenderGraph.Pass("p0", RenderGraph.PassKind.FULLSCREEN, List.of(
                    new RenderGraph.ResourceUse("seed", RenderGraph.Access.SAMPLE),
                    new RenderGraph.ResourceUse("tmpA",
                        RenderGraph.Access.COLOR_ATTACHMENT)), true))
                .pass(new RenderGraph.Pass("p1", RenderGraph.PassKind.FULLSCREEN, List.of(
                    new RenderGraph.ResourceUse("tmpA", RenderGraph.Access.SAMPLE),
                    new RenderGraph.ResourceUse("seed",
                        RenderGraph.Access.COLOR_ATTACHMENT)), true))
                .pass(new RenderGraph.Pass("p2", RenderGraph.PassKind.FULLSCREEN, List.of(
                    new RenderGraph.ResourceUse("seed", RenderGraph.Access.SAMPLE),
                    new RenderGraph.ResourceUse("tmpB",
                        RenderGraph.Access.COLOR_ATTACHMENT)), true))
                .build();

            assertTrue(graph.isValid(), () -> graph.problems().toString());
            // tmpA is live over passes 0-1 and tmpB only at pass 2, so they can share.
            assertEquals(1, TransientAllocator.pack(graph).size(),
                () -> "expected one shared bucket, got " + TransientAllocator.pack(graph));
            assertTrue(TransientAllocator.peakBytes(graph)
                    < TransientAllocator.unaliasedBytes(graph),
                "aliasing must reduce the peak");
            // The persistent resource is never aliased.
            assertTrue(TransientAllocator.pack(graph).stream()
                .noneMatch(b -> b.resources().contains("seed")));
        }
    }

    @Nested
    @DisplayName("material maps")
    class Materials {

        @Test
        @DisplayName("namespaced ids, defaults and state predicates all parse")
        void matcherForms() {
            IdMap.Matcher plain = IdMap.parseMatcher("minecraft:grass_block").orElseThrow();
            assertEquals("minecraft", plain.namespace());
            assertEquals("grass_block", plain.path());
            assertTrue(plain.predicates().isEmpty());

            IdMap.Matcher defaulted = IdMap.parseMatcher("stone").orElseThrow();
            assertEquals("minecraft", defaulted.namespace());
            assertEquals("stone", defaulted.path());

            IdMap.Matcher predicated =
                IdMap.parseMatcher("minecraft:oak_leaves:persistent=false").orElseThrow();
            assertEquals(Map.of("persistent", "false"), predicated.predicates());

            IdMap.Matcher tag = IdMap.parseMatcher("#minecraft:logs").orElseThrow();
            assertTrue(tag.tag());

            assertTrue(IdMap.parseMatcher("minecraft:oak_leaves:broken").isEmpty(),
                "a predicate with no '=' is malformed");
        }

        @Test
        @DisplayName("ids come from the pack and resolution is first-match-wins")
        void resolution() {
            IdMap map = IdMap.parse(IdMap.Kind.BLOCK, """
                block.31 = minecraft:oak_leaves:persistent=true
                block.40 = minecraft:oak_leaves minecraft:birch_leaves
                block.41 = #minecraft:logs
                """);
            assertEquals(List.of(31, 40, 41), map.ids());
            assertEquals(31, map.resolve("minecraft", "oak_leaves",
                Map.of("persistent", "true")).orElseThrow());
            assertEquals(40, map.resolve("minecraft", "oak_leaves",
                Map.of("persistent", "false")).orElseThrow());
            assertEquals(40, map.resolve("minecraft", "birch_leaves", Map.of()).orElseThrow());
            assertTrue(map.resolve("minecraft", "stone", Map.of()).isEmpty());
        }

        @Test
        @DisplayName("malformed entries are reported and do not discard the whole file")
        void malformedEntriesAreReported() {
            IdMap map = IdMap.parse(IdMap.Kind.BLOCK, """
                block.10 = minecraft:stone
                block.notanumber = minecraft:dirt
                block.11 = !!!invalid!!!
                """);
            assertEquals(10, map.resolve("minecraft", "stone", Map.of()).orElseThrow());
            assertEquals(2, map.problems().size(), () -> map.problems().toString());
        }

        @Test
        @DisplayName("the fingerprint is stable across declaration order")
        void fingerprintStability() {
            IdMap a = IdMap.parse(IdMap.Kind.BLOCK,
                "block.10 = minecraft:stone\nblock.20 = minecraft:dirt\n");
            IdMap b = IdMap.parse(IdMap.Kind.BLOCK,
                "block.20 = minecraft:dirt\nblock.10 = minecraft:stone\n");
            assertEquals(a.cacheFingerprint(), b.cacheFingerprint());
        }
    }

    @Nested
    @DisplayName("shaders.properties")
    class Properties {

        @Test
        @DisplayName("directives are typed and defaults match the format")
        void directivesAreTyped() {
            ShaderProperties properties = ShaderProperties.parse("""
                # a comment
                shadow.enabled = true
                shadowTerrain = false
                clouds = fancy
                particles.ordering = after
                blend.gbuffers_water = SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO
                alphaTest.gbuffers_terrain = GREATER 0.1
                sliders = QUALITY SHADOW_DISTANCE
                """);

            assertTrue(properties.shadowEnabled());
            assertFalse(properties.shadowTerrain());
            assertTrue(properties.shadowEntities(), "unspecified shadow flags default to on");
            assertEquals(ShaderProperties.CloudSetting.FANCY, properties.clouds());
            assertEquals(ShaderProperties.ParticleOrdering.AFTER, properties.particleOrdering());
            assertEquals(List.of("QUALITY", "SHADOW_DISTANCE"), properties.sliderOptions());
            assertTrue(properties.programOverrides().blend().containsKey("gbuffers_water"));
            assertEquals(0.1f, properties.programOverrides().alphaTest()
                .get("gbuffers_terrain").reference(), 1e-6f);
        }

        @Test
        @DisplayName("an unimplemented directive is reported rather than silently ignored")
        void unknownDirectivesAreReported() {
            ShaderProperties properties = ShaderProperties.parse("""
                clouds = fast
                someFutureDirective = 42
                """);
            assertEquals(1, properties.unknownDirectives().size());
            assertTrue(properties.unknownDirectives().getFirst().contains("someFutureDirective"),
                properties.unknownDirectives().getFirst());
        }

        @Test
        @DisplayName("a malformed value is reported and does not break the rest of the file")
        void malformedValuesAreReported() {
            ShaderProperties properties = ShaderProperties.parse("""
                blend.gbuffers_water = NOT_A_FACTOR ONE
                clouds = fancy
                """);
            assertEquals(ShaderProperties.CloudSetting.FANCY, properties.clouds());
            assertTrue(properties.problems().stream()
                    .anyMatch(p -> p.contains("NOT_A_FACTOR")),
                () -> properties.problems().toString());
        }

        @Test
        @DisplayName("screen layout, sub-screens and columns are preserved in order")
        void screenLayout() {
            ShaderProperties properties = ShaderProperties.parse("""
                screen = QUALITY <empty> SHADOWS [WATER]
                screen.WATER = WAVES CAUSTICS
                screen.WATER.columns = 1
                screen.columns = 2
                """);
            assertEquals(List.of("QUALITY", "<empty>", "SHADOWS", "[WATER]"),
                properties.screenLayout());
            assertEquals(List.of("WAVES", "CAUSTICS"), properties.subScreens().get("WATER"));
            assertEquals(2, properties.screenColumns().get(""));
            assertEquals(1, properties.screenColumns().get("WATER"));
        }
    }

    @Nested
    @DisplayName("cache keys")
    class Caching {

        private static CacheKey.Builder complete() {
            return CacheKey.builder()
                .put(CacheKey.Required.RENDERER_VERSION, "retina", "0.1.0")
                .put(CacheKey.Required.PACK_CONTENT, "pack", "abc123")
                .put(CacheKey.Required.OPTIONS, "options", "QUALITY=2")
                .put(CacheKey.Required.DEVICE, "device", "AMD Radeon 780M 24.10.1")
                .put(CacheKey.Required.CAPABILITIES, "caps", "vk1.3+dynrender");
        }

        @Test
        @DisplayName("a key missing a required input refuses to build")
        void requiredInputsAreEnforced() {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CacheKey.builder()
                    .put(CacheKey.Required.RENDERER_VERSION, "retina", "0.1.0")
                    .build());
            assertTrue(failure.getMessage().contains("PACK_CONTENT")
                    || failure.getMessage().contains("missing"),
                failure.getMessage());
        }

        @Test
        @DisplayName("changing any input changes the key")
        void everyInputAffectsTheKey() {
            CacheKey base = complete().build();
            assertNotEquals(base, complete().put("options", "QUALITY=4").build());
            assertNotEquals(base, complete().put("device", "NVIDIA RTX 4070 560.1").build());
            assertNotEquals(base, complete().put("caps", "vk1.2").build());
            assertNotEquals(base, complete().put("pack", "def456").build());
            assertEquals(base, complete().build(), "the same inputs must give the same key");
        }

        @Test
        @DisplayName("input insertion order does not change the key")
        void keyIsOrderIndependent() {
            CacheKey a = CacheKey.builder()
                .put(CacheKey.Required.RENDERER_VERSION, "retina", "0.1.0")
                .put(CacheKey.Required.PACK_CONTENT, "pack", "abc")
                .put(CacheKey.Required.OPTIONS, "options", "")
                .put(CacheKey.Required.DEVICE, "device", "d")
                .put(CacheKey.Required.CAPABILITIES, "caps", "c")
                .build();
            CacheKey b = CacheKey.builder()
                .put(CacheKey.Required.CAPABILITIES, "caps", "c")
                .put(CacheKey.Required.DEVICE, "device", "d")
                .put(CacheKey.Required.OPTIONS, "options", "")
                .put(CacheKey.Required.PACK_CONTENT, "pack", "abc")
                .put(CacheKey.Required.RENDERER_VERSION, "retina", "0.1.0")
                .build();
            assertEquals(a, b);
        }
    }

    @Nested
    @DisplayName("uniform schema")
    class Uniforms {

        @Test
        @DisplayName("push constants fit the guaranteed Vulkan minimum")
        void pushConstantsFit() {
            int size = UniformSchema.standard().pushConstantSize();
            assertTrue(size <= 128, "push constants are " + size
                + " bytes, over the 128-byte Vulkan guaranteed minimum; hardware at the"
                + " minimum would be excluded");
        }

        @Test
        @DisplayName("member order is stable across instances so the block layout is stable")
        void memberOrderIsStable() {
            List<String> first = UniformSchema.standard().alwaysPresent().stream()
                .map(UniformSchema.Entry::name).toList();
            List<String> second = UniformSchema.standard().alwaysPresent().stream()
                .map(UniformSchema.Entry::name).toList();
            assertEquals(first, second);
            assertEquals("gbufferModelView", first.getFirst(),
                "the declared order must be preserved, not hash order");
        }

        @Test
        @DisplayName("every entry documents its coordinate space or timing")
        void everyEntryIsDocumented() {
            for (UniformSchema.Entry entry : UniformSchema.standard().entries()) {
                assertFalse(entry.notes().isBlank(),
                    entry.name() + " has no documented contract");
            }
        }
    }
}
