/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.uniform;

import dev.retina.core.spirv.SpirvCompiler;
import dev.retina.core.translate.ShaderStage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks Retina's std140 arithmetic against the compiler's.
 *
 * <p>The uniform block is the one place where a CPU-side layout and a GPU-side layout have to
 * agree exactly and nothing reports it when they do not: a wrong offset links cleanly, renders,
 * and quietly hands every uniform its neighbour's bytes. Asserting against expected numbers
 * would only re-encode whatever mistake the layout code makes, so these tests compile the block
 * and read the offsets the compiler actually assigned out of the SPIR-V.
 */
class Std140LayoutTest {

    private static SpirvCompiler compiler;

    @BeforeAll
    static void setUp() {
        compiler = new SpirvCompiler();
    }

    @AfterAll
    static void tearDown() {
        compiler.close();
    }

    @Test
    @DisplayName("a vec3 occupies 12 bytes, so the next scalar packs into the same 16")
    void vec3DoesNotConsumeSixteenBytes() {
        Std140Layout layout = Std140Layout.of(List.of(
            new Std140Layout.Declaration("position", "vec3"),
            new Std140Layout.Declaration("altitude", "float")));

        assertEquals(0, layout.member("position").orElseThrow().offset());
        assertEquals(12, layout.member("altitude").orElseThrow().offset(),
            "a float after a vec3 packs into the vec3's fourth component slot");
        assertEquals(16, layout.size());
        assertAgreesWithCompiler(layout);
    }

    @Test
    @DisplayName("the full scene schema lays out exactly as the compiler lays it out")
    void standardSchemaMatchesSpirv() {
        List<Std140Layout.Declaration> declarations = new ArrayList<>();
        for (UniformSchema.Entry entry : UniformSchema.standard().alwaysPresent()) {
            declarations.add(new Std140Layout.Declaration(entry.name(), entry.glslType()));
        }
        assertAgreesWithCompiler(Std140Layout.of(declarations));
    }

    @Test
    @DisplayName("matrices, arrays and mixed vectors all match the compiler")
    void awkwardTypesMatchSpirv() {
        assertAgreesWithCompiler(Std140Layout.of(List.of(
            new Std140Layout.Declaration("normalMatrix", "mat3"),
            new Std140Layout.Declaration("bias", "float"),
            new Std140Layout.Declaration("modelView", "mat4"),
            new Std140Layout.Declaration("lights", "vec3", 4),
            new Std140Layout.Declaration("weight", "float"),
            new Std140Layout.Declaration("offsets", "vec2", 3),
            new Std140Layout.Declaration("counts", "int", 2),
            new Std140Layout.Declaration("tint", "vec4"),
            new Std140Layout.Declaration("flags", "ivec2"),
            new Std140Layout.Declaration("scale", "float"),
            new Std140Layout.Declaration("skew", "mat2x4"))));
    }

    @Test
    @DisplayName("a mat3 is three vec4-padded columns, not nine tightly packed floats")
    void mat3OccupiesFortyEightBytes() {
        Std140Layout layout = Std140Layout.of(List.of(
            new Std140Layout.Declaration("m", "mat3"),
            new Std140Layout.Declaration("after", "float")));
        assertEquals(48, layout.member("after").orElseThrow().offset());
        assertAgreesWithCompiler(layout);
    }

    @Test
    @DisplayName("an array of vec3 has a 16-byte stride, including its last element")
    void vec3ArrayPadsEveryElement() {
        Std140Layout layout = Std140Layout.of(List.of(
            new Std140Layout.Declaration("lights", "vec3", 3),
            new Std140Layout.Declaration("after", "float")));
        Std140Layout.Member lights = layout.member("lights").orElseThrow();
        assertEquals(16, lights.stride());
        assertEquals(48, lights.size());
        assertEquals(48, layout.member("after").orElseThrow().offset(),
            "the trailing element is padded too, so a following scalar cannot reuse its gap");
        assertAgreesWithCompiler(layout);
    }

    @Test
    @DisplayName("an unsupported type is refused by name rather than laid out incorrectly")
    void unsupportedTypeIsRefused() {
        Std140Layout.UnsupportedTypeException thrown = assertThrows(
            Std140Layout.UnsupportedTypeException.class,
            () -> Std140Layout.of(List.of(new Std140Layout.Declaration("s", "sampler2D"))));
        assertTrue(thrown.getMessage().contains("sampler2D"), thrown.getMessage());
    }

    // ------------------------------------------------------------------

    /**
     * Compiles the block and asserts every member's offset, and the block size, against the
     * decorations the compiler emitted.
     */
    private static void assertAgreesWithCompiler(Std140Layout layout) {
        String source = """
            #version 450
            layout(std140, set = 0, binding = 0) uniform RetinaUniforms {
            """
            + layout.glslMembers()
            + """
            };
            layout(location = 0) out vec4 retina_FragData0;
            void main() { retina_FragData0 = vec4(1.0); }
            """;

        SpirvCompiler.Result result = compiler.compile(source, ShaderStage.FRAGMENT, "std140",
            SpirvCompiler.Optimisation.NONE, List.of());
        try {
            assertTrue(result.ok(), () -> "the generated block did not compile:\n"
                + result.diagnostics() + "\n--- source ---\n" + source);
            Map<Integer, Integer> offsets = memberOffsets(result.spirv().orElseThrow());
            List<Std140Layout.Member> members = layout.members();
            assertEquals(members.size(), offsets.size(),
                "the module decorated a different number of members than were declared");

            List<String> mismatches = new ArrayList<>();
            for (int i = 0; i < members.size(); i++) {
                Std140Layout.Member member = members.get(i);
                Integer actual = offsets.get(i);
                if (actual == null || actual != member.offset()) {
                    mismatches.add(String.format("%s %s: retina=%d spirv=%s",
                        member.glslType(), member.name(), member.offset(), actual));
                }
            }
            assertTrue(mismatches.isEmpty(),
                () -> "std140 offsets disagree with the compiler:\n  "
                    + String.join("\n  ", mismatches));

            // The block size must cover the last member, or a bound range truncates it.
            Std140Layout.Member last = members.getLast();
            assertTrue(layout.size() >= last.offset() + last.size(),
                "block size " + layout.size() + " does not cover " + last.name()
                    + " ending at " + (last.offset() + last.size()));
            assertEquals(0, layout.size() % 16, "a std140 block rounds up to a vec4");
        } finally {
            SpirvCompiler.free(result);
        }
    }

    /** Reads {@code OpMemberDecorate <struct> <member> Offset <n>} out of a module. */
    private static Map<Integer, Integer> memberOffsets(ByteBuffer spirv) {
        final int opMemberDecorate = 72;
        final int decorationOffset = 35;
        IntBuffer words = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        Map<Integer, Integer> out = new LinkedHashMap<>();
        int index = 5; // skip the five-word module header
        while (index < words.limit()) {
            int instruction = words.get(index);
            int opcode = instruction & 0xFFFF;
            int length = (instruction >>> 16) & 0xFFFF;
            if (length == 0) {
                break;
            }
            if (opcode == opMemberDecorate && length >= 5
                && words.get(index + 3) == decorationOffset) {
                out.putIfAbsent(words.get(index + 2), words.get(index + 4));
            }
            index += length;
        }
        return out;
    }
}
