/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.render;

import dev.retina.core.translate.LegacyBuiltins;
import dev.retina.core.uniform.UniformSchema;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A single deterministic std140 layout shared by every terrain program in an active pack. */
public final class TerrainUniformLayout {
    public record Member(String name, String type, int offset, int size) {
    }

    private final List<Member> members;
    private final int size;

    private TerrainUniformLayout(List<Member> members, int size) {
        this.members = List.copyOf(members);
        this.size = size;
    }

    /**
     * Builds the union of legacy matrices, pack-declared values, and Retina scene values.
     * The same block is emitted into both shader stages and every terrain pass.
     */
    public static TerrainUniformLayout build(Map<String, String> packMembers) {
        Map<String, String> union = new LinkedHashMap<>();
        LegacyBuiltins.MATRIX_UNIFORMS.values().stream()
            .sorted(java.util.Comparator.comparing(LegacyBuiltins.MatrixUniform::glslName))
            .forEach(value -> union.put(value.glslName(), value.glslType()));
        packMembers.forEach((name, type) -> putCompatible(union, name, type));
        UniformSchema.standard().alwaysPresent()
            .forEach(entry -> putCompatible(union, entry.name(), entry.glslType()));

        List<Member> laidOut = new ArrayList<>();
        int offset = 0;
        for (Map.Entry<String, String> entry : union.entrySet()) {
            TypeLayout type = typeLayout(entry.getValue());
            offset = align(offset, type.alignment());
            laidOut.add(new Member(entry.getKey(), entry.getValue(), offset, type.size()));
            offset += type.size();
        }
        return new TerrainUniformLayout(laidOut, align(offset, 16));
    }

    private static void putCompatible(Map<String, String> union, String name, String type) {
        String previous = union.putIfAbsent(name, type);
        if (previous != null && !previous.equals(type)) {
            throw new IllegalArgumentException("uniform '" + name + "' is declared as both "
                + previous + " and " + type);
        }
    }

    private record TypeLayout(int alignment, int size) {
    }

    private static TypeLayout typeLayout(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> new TypeLayout(4, 4);
            case "vec2", "ivec2", "uvec2" -> new TypeLayout(8, 8);
            case "vec3", "ivec3", "uvec3", "vec4", "ivec4", "uvec4" ->
                new TypeLayout(16, 16);
            case "mat3" -> new TypeLayout(16, 48);
            case "mat4" -> new TypeLayout(16, 64);
            default -> throw new IllegalArgumentException("terrain uniform type '" + type
                + "' is not supported by the std140 uploader");
        };
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    public List<Member> members() {
        return members;
    }

    public int size() {
        return size;
    }

    /** GLSL body (without the enclosing block). */
    public String glslMembers() {
        StringBuilder out = new StringBuilder();
        for (Member member : members) {
            out.append("    ").append(member.type()).append(' ')
                .append(member.name()).append(";\n");
        }
        return out.toString();
    }

    /** Zeroes the block before writing known values, so custom uniforms are deterministic. */
    public void clear(ByteBuffer buffer) {
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
