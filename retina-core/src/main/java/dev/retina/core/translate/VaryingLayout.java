/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Assigns {@code layout(location=)} indices to the varyings that connect a program's stages.
 *
 * <p>OpenGL matched stage interfaces by name; Vulkan matches them by location, and Vulkan
 * GLSL rejects a user in/out with no location at all. Packs declare varyings without
 * locations, so Retina has to invent them — and the vertex and fragment stages are separate
 * translation units, so the two must arrive at exactly the same answer independently.
 *
 * <p>The allocation is therefore derived only from the set of varying names and their types,
 * sorted by name. Every stage of one program is translated against the same layout, built
 * from the union of the varyings all its stages declare, so a varying declared in the vertex
 * shader but unused in the fragment shader still keeps its slot rather than shifting
 * everything after it.
 *
 * <p>Slot consumption follows the GLSL rules: a scalar or vector up to four components takes
 * one location, a matrix takes one per column, and an array takes its element cost times its
 * length. Getting this wrong overlaps two varyings onto one location, which links cleanly and
 * renders garbage.
 */
public final class VaryingLayout {

    /** One varying and where it sits. */
    public record Slot(String name, String glslType, int arrayLength, int location,
                       int locationCount) {
    }

    private final Map<String, Slot> slots;
    private final int totalLocations;

    private VaryingLayout(Map<String, Slot> slots, int totalLocations) {
        this.slots = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        this.totalLocations = totalLocations;
    }

    /** An empty layout, for programs with no varyings. */
    public static VaryingLayout empty() {
        return new VaryingLayout(Map.of(), 0);
    }

    /** A declared varying, before locations are assigned. */
    public record Declaration(String name, String glslType, int arrayLength) {
        /** A non-array declaration. */
        public Declaration(String name, String glslType) {
            this(name, glslType, 0);
        }
    }

    /**
     * Builds a layout from every varying the program's stages declare.
     *
     * <p>Duplicate names are expected — the same varying appears in both the vertex and
     * fragment stage — and must agree on type. When they do not, the larger of the two is
     * used and the mismatch is reported, because allocating the smaller would let the other
     * stage write past its slot.
     */
    public static Build build(List<Declaration> declarations) {
        Map<String, Declaration> merged = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (Declaration declaration : declarations) {
            Declaration existing = merged.get(declaration.name());
            if (existing == null) {
                merged.put(declaration.name(), declaration);
                continue;
            }
            if (existing.equals(declaration)) {
                continue;
            }
            int existingCost = locationCount(existing);
            int newCost = locationCount(declaration);
            problems.add("varying '" + declaration.name() + "' is declared as '"
                + existing.glslType() + "' in one stage and '" + declaration.glslType()
                + "' in another; Retina reserved the larger of the two");
            if (newCost > existingCost) {
                merged.put(declaration.name(), declaration);
            }
        }

        Map<String, Slot> slots = new LinkedHashMap<>();
        int next = 0;
        for (Declaration declaration : merged.values()) {
            int count = locationCount(declaration);
            slots.put(declaration.name(), new Slot(declaration.name(), declaration.glslType(),
                declaration.arrayLength(), next, count));
            next += count;
        }
        return new Build(new VaryingLayout(slots, next), problems);
    }

    /** The built layout plus any interface mismatches found. */
    public record Build(VaryingLayout layout, List<String> problems) {
        public Build {
            problems = List.copyOf(problems);
        }
    }

    /** How many locations a declaration occupies. */
    public static int locationCount(Declaration declaration) {
        int perElement = switch (stripArray(declaration.glslType())) {
            case "mat2", "mat2x2", "mat2x3", "mat2x4" -> 2;
            case "mat3", "mat3x2", "mat3x3", "mat3x4" -> 3;
            case "mat4", "mat4x2", "mat4x3", "mat4x4" -> 4;
            case "dmat2" -> 4;
            case "dmat3" -> 6;
            case "dmat4" -> 8;
            case "dvec3", "dvec4" -> 2;
            default -> 1;
        };
        return perElement * Math.max(1, declaration.arrayLength());
    }

    private static String stripArray(String type) {
        int bracket = type.indexOf('[');
        return bracket < 0 ? type : type.substring(0, bracket);
    }

    /** The slot for {@code name}, if it has one. */
    public Optional<Slot> slot(String name) {
        return Optional.ofNullable(slots.get(name));
    }

    /** Every slot, in location order. */
    public List<Slot> slots() {
        return List.copyOf(slots.values());
    }

    /**
     * The number of locations consumed.
     *
     * <p>Checked against {@code maxVertexOutputComponents} / {@code maxFragmentInputComponents}
     * so a pack that exceeds the device's interface budget is reported by name rather than
     * failing inside the driver.
     */
    public int totalLocations() {
        return totalLocations;
    }
}
