/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.program;

import java.util.Locale;
import java.util.Optional;

/**
 * Which per-dimension program directory a program is loaded from.
 *
 * <p>A pack may override any program for a specific dimension by placing it in
 * {@code shaders/world<id>/}. The historical numeric ids ({@code world0}, {@code world-1},
 * {@code world1}) address the three vanilla dimensions; modern packs may also use a
 * namespaced folder name for a modded dimension.
 *
 * <p>Resolution order for a program in dimension D is: {@code world<D>/<program>} then
 * {@code <program>} at the shaders root, then the program's fallback chain. Retina keeps the
 * dimension folder and the fallback chain as separate axes because collapsing them produces
 * the wrong program when a pack overrides only some programs per dimension.
 */
public record DimensionId(String folderName, String displayName) {

    /** The Overworld: {@code shaders/world0/}. */
    public static final DimensionId OVERWORLD = new DimensionId("world0", "Overworld");
    /** The Nether: {@code shaders/world-1/}. */
    public static final DimensionId NETHER = new DimensionId("world-1", "The Nether");
    /** The End: {@code shaders/world1/}. */
    public static final DimensionId END = new DimensionId("world1", "The End");

    /**
     * The dimension folder for a Minecraft dimension key.
     *
     * <p>Vanilla keys map onto the historical numeric folders. Anything else maps onto
     * {@code world<namespace>_<path>}, which is the convention packs use for modded
     * dimensions; when that folder is absent the caller falls back to the shaders root, so an
     * unknown dimension renders with the pack's default programs rather than failing.
     */
    public static DimensionId forDimensionKey(String namespace, String path) {
        if (namespace.equals("minecraft")) {
            switch (path) {
                case "overworld" -> {
                    return OVERWORLD;
                }
                case "the_nether" -> {
                    return NETHER;
                }
                case "the_end" -> {
                    return END;
                }
                default -> {
                    // fall through to the namespaced form
                }
            }
        }
        String sanitised = (namespace + "_" + path).toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_\\-]", "_");
        return new DimensionId("world" + sanitised, namespace + ":" + path);
    }

    /** Parses a {@code world*} folder name found in a pack. */
    public static Optional<DimensionId> fromFolderName(String folderName) {
        if (!folderName.startsWith("world") || folderName.length() <= "world".length()) {
            return Optional.empty();
        }
        return Optional.of(switch (folderName) {
            case "world0" -> OVERWORLD;
            case "world-1" -> NETHER;
            case "world1" -> END;
            default -> new DimensionId(folderName, folderName);
        });
    }

    @Override
    public String toString() {
        return folderName;
    }
}
