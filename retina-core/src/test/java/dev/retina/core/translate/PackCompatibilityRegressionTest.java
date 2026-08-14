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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Constructs taken from real shader packs that Retina used to reject.
 *
 * <p>Each case here failed against a released build. They are grouped because they share one
 * root cause: a declaration the location assigner never saw. Vulkan GLSL requires an explicit
 * location on every user input and output, so any interface declaration the translator does not
 * recognise becomes a compile error rather than a default.
 *
 * <p>Every test compiles its output. A translator can emit plausible-looking GLSL that no
 * compiler accepts, so asserting on text would prove nothing.
 */
class PackCompatibilityRegressionTest {

    private static SpirvCompiler compiler;

    @BeforeAll
    static void setUp() {
        compiler = new SpirvCompiler();
    }

    @AfterAll
    static void tearDown() {
        compiler.close();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("pack-declared vertex attributes get locations above the legacy slots")
    void customVertexAttributesCompile() {
        // mc_Entity, mc_midTexCoord, at_tangent and at_midBlock appear in essentially every
        // published pack; without locations none of them can load.
        String vertex = """
            #version 120
            attribute vec4 mc_Entity;
            attribute vec2 mc_midTexCoord;
            attribute vec4 at_tangent;
            attribute vec3 at_midBlock;
            varying vec2 texcoord;
            void main() {
                gl_Position = ftransform();
                texcoord = gl_MultiTexCoord0.xy + mc_Entity.xy + mc_midTexCoord
                    + at_tangent.xy + at_midBlock.xy;
            }
            """;
        SpirvReflection reflection = compileStage(vertex, ShaderStage.VERTEX,
            DrawBuffersDirective.defaultTargets(), AlphaTest.ALWAYS);

        for (String name : List.of("mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock")) {
            SpirvReflection.InterfaceVariable input = reflection.inputs().stream()
                .filter(variable -> variable.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " was not declared as an input"));
            assertTrue(input.location() >= VulkanTranslator.CUSTOM_ATTRIBUTE_BASE,
                name + " is at location " + input.location()
                    + ", which collides with the reserved legacy attribute slots");
        }
    }

    @Test
    @DisplayName("attribute locations depend on names, not on declaration order")
    void attributeLocationsAreDeterministic() {
        String ordered = """
            #version 120
            attribute vec4 at_tangent;
            attribute vec4 mc_Entity;
            void main() { gl_Position = vec4(mc_Entity.x, at_tangent.y, 0.0, 1.0); }
            """;
        String reversed = """
            #version 120
            attribute vec4 mc_Entity;
            attribute vec4 at_tangent;
            void main() { gl_Position = vec4(mc_Entity.x, at_tangent.y, 0.0, 1.0); }
            """;
        assertEquals(locationsOf(ordered), locationsOf(reversed),
            "two packs declaring the same attributes in different orders must agree, or the"
                + " pipeline cache key stops describing the pipeline");
    }

    @Test
    @DisplayName("a modern pack's own fragment output carries the alpha test")
    void packDeclaredOutputTakesTheAlphaTest() {
        String vertex = """
            #version 330
            in vec3 Position;
            in vec2 UV0;
            out vec2 texcoord;
            void main() {
                gl_Position = vec4(Position, 1.0);
                texcoord = UV0;
            }
            """;
        String fragment = """
            #version 330
            in vec2 texcoord;
            out vec4 fragColor;
            uniform sampler2D gtexture;
            void main() { fragColor = texture(gtexture, texcoord); }
            """;
        compileStage(vertex, ShaderStage.VERTEX, DrawBuffersDirective.defaultTargets(),
            AlphaTest.ALWAYS);

        TranslatedSource translated = translate(fragment, ShaderStage.FRAGMENT,
            DrawBuffersDirective.defaultTargets(), AlphaTest.VANILLA_CUTOUT);
        assertTrue(translated.text().contains("fragColor.a"),
            () -> "the alpha test must read the pack's own output:\n" + translated.text());
        assertFalse(translated.text().contains("retina_FragData0"),
            "Retina must not invent an output the pack already declared");
        compile(translated, ShaderStage.FRAGMENT);
    }

    @Test
    @DisplayName("writing past the declared draw buffers is refused, naming the directive")
    void surplusFragDataIsRefused() {
        String fragment = """
            #version 120
            /* DRAWBUFFERS:01 */
            void main() {
                gl_FragData[0] = vec4(1.0);
                gl_FragData[1] = vec4(0.5);
                gl_FragData[2] = vec4(0.25);
            }
            """;
        DrawBuffersDirective directive = DrawBuffersDirective
            .find(GlslLexer.tokenize("/* DRAWBUFFERS:01 */")).directive();

        VulkanTranslator.UnsupportedConstructException thrown = assertThrows(
            VulkanTranslator.UnsupportedConstructException.class,
            () -> translate(fragment, ShaderStage.FRAGMENT, directive, AlphaTest.ALWAYS));
        assertTrue(thrown.getMessage().contains("DRAWBUFFERS"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("output 2"), thrown.getMessage());
    }

    @Test
    @DisplayName("an array uniform keeps its length through the generated block")
    void arrayUniformKeepsItsLength() {
        for (String declaration : List.of("uniform vec3 lights[4];", "uniform vec3[4] lights;")) {
            String vertex = "#version 120\n"
                + declaration + "\n"
                + "varying vec3 c;\n"
                + "void main() { gl_Position = ftransform(); c = lights[3]; }\n";
            TranslatedSource translated = translate(vertex, ShaderStage.VERTEX,
                DrawBuffersDirective.defaultTargets(), AlphaTest.ALWAYS);
            assertTrue(translated.text().contains("vec3 lights[4];"),
                () -> declaration + " lost its length:\n" + translated.text());
            compile(translated, ShaderStage.VERTEX);
        }
    }

    @Test
    @DisplayName("each name in a multi-declarator varying gets its own location")
    void multiDeclaratorVaryingsDoNotOverlap() {
        String vertex = """
            #version 120
            varying vec2 a, b;
            void main() {
                gl_Position = ftransform();
                a = vec2(1.0);
                b = vec2(2.0);
            }
            """;
        String fragment = """
            #version 120
            varying vec2 a, b;
            void main() { gl_FragColor = vec4(a, b); }
            """;
        List<VaryingLayout.Declaration> declarations = new ArrayList<>();
        declarations.addAll(VulkanTranslator.collectVaryings(vertex, ShaderStage.VERTEX));
        declarations.addAll(VulkanTranslator.collectVaryings(fragment, ShaderStage.FRAGMENT));
        VaryingLayout layout = VaryingLayout.build(declarations).layout();
        assertTrue(layout.slot("b").isPresent(), "the second declarator was never given a slot");
        assertNotEquals(layout.slot("a").orElseThrow().location(),
            layout.slot("b").orElseThrow().location());

        SpirvReflection vertexModule = compile(
            translate(vertex, ShaderStage.VERTEX, DrawBuffersDirective.defaultTargets(),
                AlphaTest.ALWAYS, layout), ShaderStage.VERTEX);
        assertEquals(2, vertexModule.outputs().size(),
            "both varyings must reach the interface");
        compile(translate(fragment, ShaderStage.FRAGMENT, DrawBuffersDirective.defaultTargets(),
            AlphaTest.ALWAYS, layout), ShaderStage.FRAGMENT);
    }

    @Test
    @DisplayName("a multi-line comment inside a relocated declaration keeps its lines")
    void blockCommentsDoNotShiftLineNumbers() {
        String vertex = """
            #version 120
            uniform /* a
            multi-line
            comment */ float wobble;
            varying vec2 texcoord;
            void main() {
                gl_Position = ftransform();
                texcoord = vec2(wobble);
            }
            """;
        TranslatedSource translated = translate(vertex, ShaderStage.VERTEX,
            DrawBuffersDirective.defaultTargets(), AlphaTest.ALWAYS);
        assertEquals(vertex.lines().count() + translated.prologueLines(),
            translated.text().lines().count(),
            "translation must not move any line, or every remapped diagnostic points at the"
                + " wrong source line");
        compile(translated, ShaderStage.VERTEX);
    }

    @Test
    @DisplayName("a struct uniform is refused by name rather than left to fail at link time")
    void structUniformIsRefused() {
        String vertex = """
            #version 120
            struct Light { vec3 dir; float power; };
            uniform Light sun;
            void main() { gl_Position = vec4(sun.dir * sun.power, 1.0); }
            """;
        VulkanTranslator.UnsupportedConstructException thrown = assertThrows(
            VulkanTranslator.UnsupportedConstructException.class,
            () -> translate(vertex, ShaderStage.VERTEX, DrawBuffersDirective.defaultTargets(),
                AlphaTest.ALWAYS));
        assertTrue(thrown.getMessage().contains("sun"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Light"), thrown.getMessage());
    }

    // ------------------------------------------------------------------

    private static List<Integer> locationsOf(String vertex) {
        return compileStage(vertex, ShaderStage.VERTEX, DrawBuffersDirective.defaultTargets(),
            AlphaTest.ALWAYS).inputs().stream()
            .filter(variable -> variable.name().equals("mc_Entity")
                || variable.name().equals("at_tangent"))
            .sorted(java.util.Comparator.comparing(SpirvReflection.InterfaceVariable::name))
            .map(SpirvReflection.InterfaceVariable::location)
            .toList();
    }

    private static TranslatedSource translate(String source, ShaderStage stage,
                                              DrawBuffersDirective drawBuffers,
                                              AlphaTest alphaTest) {
        return translate(source, stage, drawBuffers, alphaTest,
            VaryingLayout.build(VulkanTranslator.collectVaryings(source, stage)).layout());
    }

    private static TranslatedSource translate(String source, ShaderStage stage,
                                              DrawBuffersDirective drawBuffers,
                                              AlphaTest alphaTest, VaryingLayout varyings) {
        return new VulkanTranslator(new BindingLayout(), UniformSchema.standard())
            .translate(source, new VulkanTranslator.Options(stage, drawBuffers, alphaTest,
                false, 450, varyings));
    }

    private static SpirvReflection compileStage(String source, ShaderStage stage,
                                                DrawBuffersDirective drawBuffers,
                                                AlphaTest alphaTest) {
        return compile(translate(source, stage, drawBuffers, alphaTest), stage);
    }

    private static SpirvReflection compile(TranslatedSource translated, ShaderStage stage) {
        SpirvCompiler.Result result = compiler.compile(translated.text(), stage, "regression",
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
}
