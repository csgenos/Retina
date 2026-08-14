/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.render;

import dev.retina.core.translate.LegacyBuiltins;
import dev.retina.core.uniform.Std140Layout;
import dev.retina.core.uniform.UniformSchema;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single deterministic std140 layout shared by every terrain program in an active pack.
 *
 * <p>The offset arithmetic itself lives in {@link Std140Layout}, in {@code retina-core}, where
 * it is checked against the offsets a real compiler assigns. Keeping it there is not tidiness:
 * a CPU-side offset that disagrees with the shader's links cleanly and renders, so the only way
 * to know the two agree is to compile the block and compare, and that test cannot run in a
 * module that needs Minecraft on the classpath.
 */
public final class TerrainUniformLayout {

    /** One member of the block, with the offset the shader will read it from. */
    public record Member(String name, String type, int arrayLength, int offset, int size) {
    }

    private final Std140Layout layout;
    private final List<Member> members;

    private TerrainUniformLayout(Std140Layout layout) {
        this.layout = layout;
        this.members = layout.members().stream()
            .map(member -> new Member(member.name(), member.glslType(), member.arrayLength(),
                member.offset(), member.size()))
            .toList();
    }

    /**
     * Builds the union of legacy matrices, pack-declared values, and Retina scene values.
     * The same block is emitted into both shader stages and every terrain pass.
     */
    public static TerrainUniformLayout build(List<Std140Layout.Declaration> packMembers) {
        Map<String, Std140Layout.Declaration> union = new LinkedHashMap<>();
        LegacyBuiltins.MATRIX_UNIFORMS.values().stream()
            .sorted(java.util.Comparator.comparing(LegacyBuiltins.MatrixUniform::glslName))
            .forEach(value -> union.put(value.glslName(),
                new Std140Layout.Declaration(value.glslName(), value.glslType())));
        packMembers.forEach(declaration -> putCompatible(union, declaration));
        UniformSchema.standard().alwaysPresent()
            .forEach(entry -> putCompatible(union,
                new Std140Layout.Declaration(entry.name(), entry.glslType())));
        return new TerrainUniformLayout(Std140Layout.of(new ArrayList<>(union.values())));
    }

    private static void putCompatible(Map<String, Std140Layout.Declaration> union,
                                      Std140Layout.Declaration declaration) {
        Std140Layout.Declaration previous = union.putIfAbsent(declaration.name(), declaration);
        if (previous != null && !previous.equals(declaration)) {
            throw new IllegalArgumentException("uniform '" + declaration.name()
                + "' is declared as both " + describe(previous) + " and "
                + describe(declaration));
        }
    }

    private static String describe(Std140Layout.Declaration declaration) {
        return declaration.glslType()
            + (declaration.arrayLength() > 0 ? "[" + declaration.arrayLength() + "]" : "");
    }

    public List<Member> members() {
        return members;
    }

    public int size() {
        return layout.size();
    }

    /** GLSL body (without the enclosing block). */
    public String glslMembers() {
        return layout.glslMembers();
    }

    /** Zeroes the block before writing known values, so custom uniforms are deterministic. */
    public void clear(ByteBuffer buffer) {
        int size = layout.size();
        for (int i = 0; i < size; i += Long.BYTES) {
            if (i + Long.BYTES <= size) {
                buffer.putLong(i, 0L);
            } else {
                for (int j = i; j < size; j++) {
                    buffer.put(j, (byte)0);
                }
            }
        }
    }
}
