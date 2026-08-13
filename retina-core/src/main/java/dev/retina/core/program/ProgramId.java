/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.program;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The program families a shader pack may supply, and how they fall back.
 *
 * <p>The fallback chain is the single most behaviour-defining part of the shader-pack
 * contract: a pack that ships only {@code gbuffers_basic} must still draw entities, signs
 * and particles, because every other gbuffer program resolves to it through this chain. Get
 * the chain wrong and a pack renders with the wrong program rather than failing visibly,
 * which is exactly the class of silent-wrong-output bug that is hardest to diagnose.
 *
 * <p>{@code fallback} is the program used when this one is absent. A {@code null} fallback
 * means "use Retina's internal vanilla-equivalent program", not "skip the geometry".
 */
public enum ProgramId {
    // ---- Non-geometry stages ----
    /** Runs once when the pack is loaded; compute only. */
    SETUP("setup", null, Stage.COMPUTE_ONLY),
    /** Runs once per frame before anything is drawn; compute only. */
    BEGIN("begin", null, Stage.COMPUTE_ONLY),

    // ---- Shadow pass ----
    SHADOW("shadow", null, Stage.GEOMETRY),
    SHADOW_SOLID("shadow_solid", SHADOW, Stage.GEOMETRY),
    SHADOW_CUTOUT("shadow_cutout", SHADOW, Stage.GEOMETRY),
    SHADOW_WATER("shadow_water", SHADOW, Stage.GEOMETRY),
    SHADOWCOMP("shadowcomp", null, Stage.FULLSCREEN),

    // ---- Prepare chain ----
    PREPARE("prepare", null, Stage.FULLSCREEN),

    // ---- Gbuffers ----
    /** The root of the gbuffer fallback tree. */
    GBUFFERS_BASIC("gbuffers_basic", null, Stage.GEOMETRY),
    GBUFFERS_LINE("gbuffers_line", GBUFFERS_BASIC, Stage.GEOMETRY),
    GBUFFERS_TEXTURED("gbuffers_textured", GBUFFERS_BASIC, Stage.GEOMETRY),
    GBUFFERS_TEXTURED_LIT("gbuffers_textured_lit", GBUFFERS_TEXTURED, Stage.GEOMETRY),
    GBUFFERS_SKYBASIC("gbuffers_skybasic", GBUFFERS_BASIC, Stage.GEOMETRY),
    GBUFFERS_SKYTEXTURED("gbuffers_skytextured", GBUFFERS_TEXTURED, Stage.GEOMETRY),
    GBUFFERS_CLOUDS("gbuffers_clouds", GBUFFERS_TEXTURED, Stage.GEOMETRY),
    GBUFFERS_TERRAIN("gbuffers_terrain", GBUFFERS_TEXTURED_LIT, Stage.GEOMETRY),
    GBUFFERS_TERRAIN_SOLID("gbuffers_terrain_solid", GBUFFERS_TERRAIN, Stage.GEOMETRY),
    GBUFFERS_TERRAIN_CUTOUT("gbuffers_terrain_cutout", GBUFFERS_TERRAIN, Stage.GEOMETRY),
    GBUFFERS_DAMAGEDBLOCK("gbuffers_damagedblock", GBUFFERS_TERRAIN, Stage.GEOMETRY),
    GBUFFERS_BLOCK("gbuffers_block", GBUFFERS_TERRAIN, Stage.GEOMETRY),
    GBUFFERS_BLOCK_TRANSLUCENT("gbuffers_block_translucent", GBUFFERS_BLOCK, Stage.GEOMETRY),
    GBUFFERS_BEACONBEAM("gbuffers_beaconbeam", GBUFFERS_TEXTURED, Stage.GEOMETRY),
    GBUFFERS_ITEM("gbuffers_item", GBUFFERS_TEXTURED_LIT, Stage.GEOMETRY),
    GBUFFERS_ENTITIES("gbuffers_entities", GBUFFERS_TEXTURED_LIT, Stage.GEOMETRY),
    GBUFFERS_ENTITIES_TRANSLUCENT("gbuffers_entities_translucent", GBUFFERS_ENTITIES,
        Stage.GEOMETRY),
    GBUFFERS_ENTITIES_GLOWING("gbuffers_entities_glowing", GBUFFERS_ENTITIES, Stage.GEOMETRY),
    GBUFFERS_ARMOR_GLINT("gbuffers_armor_glint", GBUFFERS_TEXTURED, Stage.GEOMETRY),
    GBUFFERS_SPIDEREYES("gbuffers_spidereyes", GBUFFERS_TEXTURED, Stage.GEOMETRY),
    GBUFFERS_HAND("gbuffers_hand", GBUFFERS_TEXTURED_LIT, Stage.GEOMETRY),
    GBUFFERS_WEATHER("gbuffers_weather", GBUFFERS_TEXTURED_LIT, Stage.GEOMETRY),
    GBUFFERS_WATER("gbuffers_water", GBUFFERS_TERRAIN, Stage.GEOMETRY),
    GBUFFERS_HAND_WATER("gbuffers_hand_water", GBUFFERS_HAND, Stage.GEOMETRY),

    // ---- Distant Horizons (optional, version-gated) ----
    DH_TERRAIN("dh_terrain", null, Stage.GEOMETRY),
    DH_WATER("dh_water", DH_TERRAIN, Stage.GEOMETRY),
    DH_GENERIC("dh_generic", null, Stage.GEOMETRY),
    DH_SHADOW("dh_shadow", null, Stage.GEOMETRY),

    // ---- Post chain ----
    DEFERRED("deferred", null, Stage.FULLSCREEN),
    COMPOSITE("composite", null, Stage.FULLSCREEN),
    FINAL("final", null, Stage.FULLSCREEN);

    /** What kind of work a program family performs. */
    public enum Stage {
        /** Draws world geometry; has vertex and fragment stages. */
        GEOMETRY,
        /** Draws a screen-filling triangle over the render targets. */
        FULLSCREEN,
        /** Has no raster stages; only {@code .csh} is meaningful. */
        COMPUTE_ONLY
    }

    private static final Map<String, ProgramId> BY_NAME;

    static {
        Map<String, ProgramId> byName = new LinkedHashMap<>();
        for (ProgramId id : values()) {
            byName.put(id.name, id);
        }
        BY_NAME = Map.copyOf(byName);
    }

    private final String name;
    private final ProgramId fallback;
    private final Stage stage;

    ProgramId(String name, ProgramId fallback, Stage stage) {
        this.name = name;
        this.fallback = fallback;
        this.stage = stage;
    }

    /** The base source name, without a dimension prefix or numeric suffix. */
    public String sourceName() {
        return name;
    }

    /** The program used when this one is absent, or empty for a root program. */
    public Optional<ProgramId> fallback() {
        return Optional.ofNullable(fallback);
    }

    /** What kind of work this program performs. */
    public Stage stage() {
        return stage;
    }

    /** Whether this family is part of the numbered composite/deferred/prepare chains. */
    public boolean isChained() {
        return this == PREPARE || this == DEFERRED || this == COMPOSITE || this == SHADOWCOMP;
    }

    /** Whether this family belongs to the Distant Horizons extension. */
    public boolean isDistantHorizons() {
        return name.startsWith("dh_");
    }

    /**
     * The chain from this program to the root of its fallback tree, this program first.
     *
     * <p>Resolution walks this list and uses the first entry the pack actually provides.
     */
    public List<ProgramId> fallbackChain() {
        List<ProgramId> chain = new ArrayList<>();
        ProgramId current = this;
        while (current != null && !chain.contains(current)) {
            chain.add(current);
            current = current.fallback;
        }
        return List.copyOf(chain);
    }

    /** Looks up a family by its source name. */
    public static Optional<ProgramId> byName(String name) {
        return Optional.ofNullable(BY_NAME.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Parses a source file base name into a program reference.
     *
     * <p>Handles the numeric suffix used by chained programs ({@code composite3}) and the
     * unnumbered first element ({@code composite} == {@code composite0}).
     *
     * @return empty when the name is not a recognised program
     */
    public static Optional<ProgramRef> parseSourceName(String baseName) {
        String name = baseName.trim().toLowerCase(Locale.ROOT);
        Optional<ProgramId> exact = byName(name);
        if (exact.isPresent()) {
            return Optional.of(new ProgramRef(exact.get(), 0));
        }
        int digits = 0;
        while (digits < name.length()
            && Character.isDigit(name.charAt(name.length() - 1 - digits))) {
            digits++;
        }
        if (digits == 0) {
            return Optional.empty();
        }
        String stem = name.substring(0, name.length() - digits);
        String suffix = name.substring(name.length() - digits);
        Optional<ProgramId> base = byName(stem);
        if (base.isEmpty() || !base.get().isChained()) {
            return Optional.empty();
        }
        int index;
        try {
            index = Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(new ProgramRef(base.get(), index));
    }

    /**
     * A specific program instance: a family plus its index within a chain.
     *
     * @param index 0 for unchained programs and for the first element of a chain
     */
    public record ProgramRef(ProgramId id, int index) {
        /** The source base name, e.g. {@code composite3}. */
        public String sourceName() {
            if (!id.isChained() || index == 0) {
                return id.sourceName();
            }
            return id.sourceName() + index;
        }

        @Override
        public String toString() {
            return sourceName();
        }
    }
}
