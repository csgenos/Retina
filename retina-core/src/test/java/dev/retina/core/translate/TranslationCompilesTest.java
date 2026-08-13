/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.translate;

import dev.retina.core.spirv.SpirvCompiler;
import dev.retina.core.spirv.SpirvReflection;
import dev.retina.core.state.AlphaTest;
import dev.retina.core.uniform.UniformSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end translation tests: legacy pack GLSL in, valid SPIR-V out.
 *
 * <p>These are the tests that matter most. A translator can be unit-tested against expected
 * output strings and still emit GLSL no compiler accepts; compiling the result and reflecting
 * the module is the only check that proves the output is real.
 */
class TranslationCompilesTest {

    private static SpirvCompiler compiler;

    @BeforeAll
    static void setUp() {
        compiler = new SpirvCompiler();
    }

    @AfterAll
    static void tearDown() {
        compiler.close();
    }

    private static TranslatedSource translate(String source, VulkanTranslator.Options options) {
        return new VulkanTranslator(new BindingLayout(), UniformSchema.standard())
            .translate(source, options);
    }

    private static SpirvReflection compile(TranslatedSource translated, ShaderStage stage) {
        SpirvCompiler.Result result = compiler.compile(translated.text(), stage, "test",
            SpirvCompiler.Optimisation.NONE, List.of());
        try {
            assertTrue(result.ok(), () -> "translation did not compile:\n"
                + result.diagnostics() + "\n--- generated source ---\n"
                + numbered(translated.text()));
            return SpirvReflection.of(result.spirv().orElseThrow());
        } finally {
            SpirvCompiler.free(result);
        }
    }

    private static String numbered(String text) {
        StringBuilder out = new StringBuilder();
        int line = 1;
        for (String l : text.split("\n", -1)) {
            out.append(String.format("%4d | %s%n", line++, l));
        }
        return out.toString();
    }

    @Test
    @DisplayName("a GLSL 120 vertex shader using fixed-function state compiles to SPIR-V")
    void legacyVertexShaderCompiles() {
        String source = """
            #version 120

            varying vec4 texcoord;
            varying vec4 lmcoord;
            varying vec4 color;

            uniform mat4 gbufferModelView;
            uniform float frameTimeCounter;

            void main() {
                gl_Position = ftransform();
                texcoord = gl_TextureMatrix[0] * gl_MultiTexCoord0;
                lmcoord = gl_MultiTexCoord1;
                color = gl_Color;
            }
            """;
        // gl_TextureMatrix is deliberately unsupported; check it is reported, not guessed.
        TranslatedSource translated = translate(source.replace(
            "gl_TextureMatrix[0] * gl_MultiTexCoord0", "gl_MultiTexCoord0"),
            new VulkanTranslator.Options(ShaderStage.VERTEX,
                DrawBuffersDirective.defaultTargets(), AlphaTest.ALWAYS, false, 450));

        SpirvReflection reflection = compile(translated, ShaderStage.VERTEX);

        // The legacy attributes must have become real vertex inputs at the fixed locations.
        assertTrue(reflection.inputs().stream()
                .anyMatch(v -> v.name().equals("retina_Vertex") && v.location() == 0),
            () -> "expected retina_Vertex at location 0, got " + reflection.inputs());
        assertTrue(reflection.inputs().stream()
                .anyMatch(v -> v.name().equals("retina_Color") && v.location() == 1),
            () -> "expected retina_Color at location 1, got " + reflection.inputs());
        assertTrue(reflection.inputs().stream()
                .anyMatch(v -> v.name().equals("retina_MultiTexCoord1") && v.location() == 4),
            () -> "expected retina_MultiTexCoord1 at location 4, got " + reflection.inputs());

        // The uniform block must be at set 0 binding 0.
        assertTrue(reflection.at(BindingLayout.SET_UNIFORMS, BindingLayout.BINDING_UNIFORM_BLOCK)
            .isPresent(), () -> "expected a uniform block at set 0 binding 0, got "
            + reflection.resources());
    }

    @Test
    @DisplayName("gl_TextureMatrix is reported rather than silently mistranslated")
    void textureMatrixIsReported() {
        String source = """
            #version 120
            varying vec4 texcoord;
            void main() {
                gl_Position = ftransform();
                texcoord = gl_TextureMatrix[0] * gl_MultiTexCoord0;
            }
            """;
        TranslatedSource translated = translate(source,
            new VulkanTranslator.Options(ShaderStage.VERTEX,
                DrawBuffersDirective.defaultTargets(), AlphaTest.ALWAYS, false, 450));
        assertTrue(translated.warnings().stream()
                .anyMatch(w -> w.contains("gl_TextureMatrix")),
            () -> "expected a warning naming gl_TextureMatrix, got " + translated.warnings());
    }

    @Test
    @DisplayName("gl_FragData writes become explicit outputs at the DRAWBUFFERS locations")
    void fragDataBecomesExplicitOutputs() {
        String source = """
            #version 120
            uniform sampler2D texture;
            varying vec4 texcoord;

            /* DRAWBUFFERS:021 */
            void main() {
                gl_FragData[0] = texture2D(texture, texcoord.st);
                gl_FragData[1] = vec4(0.0, 1.0, 0.0, 1.0);
                gl_FragData[2] = vec4(1.0);
            }
            """;
        List<GlslLexer.Token> tokens = GlslLexer.tokenize(source);
        DrawBuffersDirective.Result directive = DrawBuffersDirective.find(tokens);
        assertEquals(List.of(0, 2, 1), directive.directive().targets());

        TranslatedSource translated = translate(source,
            new VulkanTranslator.Options(ShaderStage.FRAGMENT, directive.directive(),
                AlphaTest.ALWAYS, false, 450));
        SpirvReflection reflection = compile(translated, ShaderStage.FRAGMENT);

        assertEquals(List.of(0, 1, 2), reflection.fragmentOutputLocations(),
            () -> "expected three fragment outputs, got " + reflection.outputs());

        // The sampler declared as `texture` must have been canonicalised onto gtexture's slot
        // rather than colliding with the GLSL `texture()` builtin.
        assertTrue(reflection.resources().stream()
                .anyMatch(r -> r.set() == BindingLayout.SET_SAMPLERS),
            () -> "expected a sampler in set 1, got " + reflection.resources());
    }

    @Test
    @DisplayName("alphaTest becomes a discard inside main")
    void alphaTestBecomesDiscard() {
        String source = """
            #version 120
            uniform sampler2D gtexture;
            varying vec4 texcoord;
            void main() {
                gl_FragData[0] = texture2D(gtexture, texcoord.st);
            }
            """;
        TranslatedSource translated = translate(source,
            new VulkanTranslator.Options(ShaderStage.FRAGMENT,
                DrawBuffersDirective.defaultTargets(),
                new AlphaTest(AlphaTest.Function.GREATER, 0.1f), false, 450));

        assertTrue(translated.text().contains("discard"),
            () -> "expected an injected discard, got:\n" + translated.text());
        compile(translated, ShaderStage.FRAGMENT);
    }

    @Test
    @DisplayName("texture2D inside comments and identifiers is not rewritten")
    void rewritingIsTokenAccurate() {
        String source = """
            #version 120
            uniform sampler2D gtexture;
            varying vec4 texcoord;
            // this comment mentions texture2D and must survive
            vec4 my_texture2D_helper(vec2 uv) { return vec4(uv, 0.0, 1.0); }
            void main() {
                gl_FragData[0] = texture2D(gtexture, texcoord.st) + my_texture2D_helper(texcoord.st);
            }
            """;
        TranslatedSource translated = translate(source,
            new VulkanTranslator.Options(ShaderStage.FRAGMENT,
                DrawBuffersDirective.defaultTargets(), AlphaTest.ALWAYS, false, 450));

        assertTrue(translated.text().contains("this comment mentions texture2D and must survive"),
            "the comment must not be rewritten");
        assertTrue(translated.text().contains("my_texture2D_helper"),
            "an identifier containing texture2D must not be rewritten");
        assertFalse(translated.text().contains("= texture2D("),
            "the actual call must have been rewritten");
        compile(translated, ShaderStage.FRAGMENT);
    }

    @Test
    @DisplayName("line numbers survive translation so diagnostics can be remapped")
    void lineNumbersSurvive() {
        String source = """
            #version 120
            uniform mat4 gbufferModelView;
            uniform float frameTimeCounter;
            varying vec4 color;

            void main() {
                gl_Position = ftransform();
                color = vec4(frameTimeCounter);
            }
            """;
        TranslatedSource translated = translate(source,
            new VulkanTranslator.Options(ShaderStage.VERTEX,
                DrawBuffersDirective.defaultTargets(), AlphaTest.ALWAYS, false, 450));

        List<String> before = source.lines().toList();
        List<String> after = translated.text().lines().toList();

        // Everything after the prologue must sit at its original line plus the prologue size.
        for (int i = 1; i < before.size(); i++) {
            int translatedLine = i + 1 + translated.prologueLines();
            assertTrue(translatedLine <= after.size(),
                "translated source lost lines: " + after.size() + " < " + translatedLine);
            assertEquals(java.util.Optional.of(i + 1),
                translated.toPreprocessedLine(translatedLine),
                "line " + translatedLine + " of the translated source should map back to line "
                    + (i + 1));
        }
        compile(translated, ShaderStage.VERTEX);
    }

    @Test
    @DisplayName("a modern GLSL 330 composite shader compiles unchanged in meaning")
    void modernCompositeCompiles() {
        String source = """
            #version 330 compatibility
            uniform sampler2D colortex0;
            uniform sampler2D depthtex0;
            uniform mat4 gbufferProjectionInverse;
            uniform float viewWidth;
            uniform float viewHeight;
            in vec2 texcoord;

            /* RENDERTARGETS: 0 */
            void main() {
                vec3 colour = texture(colortex0, texcoord).rgb;
                float depth = texture(depthtex0, texcoord).r;
                gl_FragData[0] = vec4(colour * depth, 1.0);
            }
            """;
        List<GlslLexer.Token> tokens = GlslLexer.tokenize(source);
        DrawBuffersDirective.Result directive = DrawBuffersDirective.find(tokens);
        assertEquals(List.of(0), directive.directive().targets());
        assertEquals(DrawBuffersDirective.Form.RENDERTARGETS, directive.directive().form());

        TranslatedSource translated = translate(source,
            new VulkanTranslator.Options(ShaderStage.FRAGMENT, directive.directive(),
                AlphaTest.ALWAYS, false, 450));
        SpirvReflection reflection = compile(translated, ShaderStage.FRAGMENT);

        assertEquals(List.of(0), reflection.fragmentOutputLocations());
        assertTrue(reflection.hasPushConstants(),
            "the generated push-constant block should be present");
    }

    @Test
    @DisplayName("a compute program compiles")
    void computeCompiles() {
        String source = """
            #version 430
            layout(local_size_x = 16, local_size_y = 16) in;
            uniform sampler2D colortex0;
            layout(rgba16f) uniform image2D colorimg0;
            uniform float frameTimeCounter;
            void main() {
                ivec2 texel = ivec2(gl_GlobalInvocationID.xy);
                vec4 value = texelFetch(colortex0, texel, 0);
                imageStore(colorimg0, texel, value * frameTimeCounter);
            }
            """;
        TranslatedSource translated = translate(source,
            VulkanTranslator.Options.fullscreen(ShaderStage.COMPUTE));
        SpirvReflection reflection = compile(translated, ShaderStage.COMPUTE);

        assertTrue(reflection.resources().stream()
                .anyMatch(r -> r.set() == BindingLayout.SET_IMAGES),
            () -> "expected the storage image in set 2, got " + reflection.resources());
    }
}
