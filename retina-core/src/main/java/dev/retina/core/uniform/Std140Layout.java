/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.uniform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes std140 offsets for a uniform block, the way the GLSL compiler will.
 *
 * <p>This lives in {@code retina-core} rather than beside the renderer for one reason: it can be
 * checked. {@code Std140LayoutTest} compiles the block this class describes and asserts these
 * offsets against the {@code OpMemberDecorate … Offset} decorations in the resulting SPIR-V, so
 * a disagreement between what the CPU writes and what the shader reads is a test failure rather
 * than a rendering artefact nobody can trace.
 *
 * <p>The rule that is easy to get wrong, and that this class exists to get right, is
 * <strong>{@code vec3} aligns to 16 bytes but occupies 12</strong>. The next member packs into
 * the remaining four. Treating a {@code vec3} as 16 bytes wide shifts every following scalar,
 * which links and renders without complaint while feeding each uniform its neighbour's bytes.
 *
 * <p>Implemented from the std140 rules directly:
 *
 * <ol>
 *   <li>a scalar has base alignment and size equal to its component size;
 *   <li>a two-component vector aligns to twice that, a three- or four-component vector to four
 *       times, but a three-component vector still occupies only three components;
 *   <li>an array's element alignment is rounded up to a {@code vec4}, and that rounded value is
 *       also its stride;
 *   <li>a column-major matrix is an array of its columns, so it follows the array rule.
 * </ol>
 */
public final class Std140Layout {

    /** One laid-out member. */
    public record Member(String name, String glslType, int arrayLength, int offset, int size) {
        /** Whether this member is an array. */
        public boolean isArray() {
            return arrayLength > 0;
        }

        /** The distance between consecutive elements, or the size for a non-array. */
        public int stride() {
            return arrayLength > 0 ? size / arrayLength : size;
        }

        /** The member as it is declared in GLSL, without the trailing semicolon. */
        public String declaration() {
            return glslType + " " + name + (arrayLength > 0 ? "[" + arrayLength + "]" : "");
        }
    }

    /** A member to lay out, before its offset is known. */
    public record Declaration(String name, String glslType, int arrayLength) {
        /** A non-array declaration. */
        public Declaration(String name, String glslType) {
            this(name, glslType, 0);
        }
    }

    /** Thrown when a declared type has no std140 representation Retina can upload. */
    public static final class UnsupportedTypeException extends IllegalArgumentException {
        public UnsupportedTypeException(String message) {
            super(message);
        }
    }

    private final List<Member> members;
    private final Map<String, Member> byName;
    private final int size;

    private Std140Layout(List<Member> members, int size) {
        this.members = List.copyOf(members);
        this.size = size;
        Map<String, Member> index = new LinkedHashMap<>();
        members.forEach(member -> index.put(member.name(), member));
        this.byName = java.util.Collections.unmodifiableMap(index);
    }

    /** Lays out {@code declarations} in the order given. */
    public static Std140Layout of(List<Declaration> declarations) {
        List<Member> laidOut = new ArrayList<>(declarations.size());
        int offset = 0;
        for (Declaration declaration : declarations) {
            int alignment = alignmentOf(declaration);
            int size = sizeOf(declaration);
            offset = align(offset, alignment);
            laidOut.add(new Member(declaration.name(), declaration.glslType(),
                declaration.arrayLength(), offset, size));
            offset += size;
        }
        // The block itself rounds up to a vec4, which is what a UBO binding range must cover.
        return new Std140Layout(laidOut, align(offset, 16));
    }

    /**
     * The base alignment of a declaration.
     *
     * <p>An array or a matrix rounds its element alignment up to a {@code vec4}; everything else
     * uses the scalar/vector rule.
     */
    public static int alignmentOf(Declaration declaration) {
        int element = componentAlignment(declaration.glslType());
        boolean roundsUp = declaration.arrayLength() > 0 || columnsOf(declaration.glslType()) > 0;
        return roundsUp ? align(element, 16) : element;
    }

    /** The bytes a declaration occupies, excluding any padding before the next member. */
    public static int sizeOf(Declaration declaration) {
        int columns = columnsOf(declaration.glslType());
        int elementSize = columns > 0
            // A matrix is an array of `columns` column vectors, each padded to a vec4.
            ? columns * 16
            : componentSize(declaration.glslType());
        if (declaration.arrayLength() <= 0) {
            return elementSize;
        }
        // Every array element is padded up to a vec4, including the last one.
        return align(elementSize, 16) * declaration.arrayLength();
    }

    /** Whether Retina can lay out and upload {@code glslType}. */
    public static boolean supports(String glslType) {
        return TYPES.containsKey(glslType);
    }

    /** One scalar/vector/matrix type's component alignment and size. */
    private record TypeInfo(int alignment, int size, int columns) {
    }

    private static final Map<String, TypeInfo> TYPES = buildTypes();

    private static Map<String, TypeInfo> buildTypes() {
        Map<String, TypeInfo> map = new LinkedHashMap<>();
        // Scalars.
        for (String scalar : new String[] {"float", "int", "uint", "bool"}) {
            map.put(scalar, new TypeInfo(4, 4, 0));
        }
        // Vectors. A three-component vector aligns to 16 and occupies 12 — the whole point of
        // this class.
        for (String prefix : new String[] {"", "i", "u", "b"}) {
            map.put(prefix + "vec2", new TypeInfo(8, 8, 0));
            map.put(prefix + "vec3", new TypeInfo(16, 12, 0));
            map.put(prefix + "vec4", new TypeInfo(16, 16, 0));
        }
        // Matrices, column-major. Size is derived from the column count by sizeOf.
        for (int columns = 2; columns <= 4; columns++) {
            for (int rows = 2; rows <= 4; rows++) {
                TypeInfo info = new TypeInfo(16, columns * 16, columns);
                map.put("mat" + columns + "x" + rows, info);
                if (columns == rows) {
                    map.put("mat" + columns, info);
                }
            }
        }
        return Map.copyOf(map);
    }

    private static TypeInfo info(String glslType) {
        TypeInfo info = TYPES.get(glslType);
        if (info == null) {
            throw new UnsupportedTypeException("uniform type '" + glslType
                + "' has no std140 layout Retina can upload; supported types are scalars,"
                + " 2-4 component vectors, and 2x2 to 4x4 matrices");
        }
        return info;
    }

    private static int componentAlignment(String glslType) {
        return info(glslType).alignment();
    }

    private static int componentSize(String glslType) {
        return info(glslType).size();
    }

    private static int columnsOf(String glslType) {
        return info(glslType).columns();
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    /** Every member, in declaration order. */
    public List<Member> members() {
        return members;
    }

    /** One member by name. */
    public Optional<Member> member(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** The block size in bytes, rounded up to a vec4. */
    public int size() {
        return size;
    }

    /** The GLSL body of the block, without the enclosing declaration. */
    public String glslMembers() {
        StringBuilder out = new StringBuilder(members.size() * 32);
        for (Member member : members) {
            out.append("    ").append(member.declaration()).append(";\n");
        }
        return out.toString();
    }
}
