/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.props;

import dev.retina.core.state.AlphaTest;
import dev.retina.core.state.BlendMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed {@code shaders.properties}.
 *
 * <p>Every directive Retina understands is read here and turned into a typed value.
 * Directives Retina does <em>not</em> understand are recorded in {@link #unknownDirectives()}
 * and surfaced in the pack diagnostics UI. That is a deliberate choice: silently ignoring a
 * directive produces a pack that renders subtly wrong with no indication why, and the pack
 * author has no way to discover it. An unknown directive is a compatibility gap and is
 * reported as one.
 */
public final class ShaderProperties {

    /** Per-program overrides declared in {@code shaders.properties}. */
    public record ProgramOverrides(Map<String, BlendMode> blend,
                                   Map<String, AlphaTest> alphaTest,
                                   Map<String, Boolean> backFaceCulling,
                                   Map<String, List<Integer>> drawBufferOverride) {
        public ProgramOverrides {
            blend = Map.copyOf(blend);
            alphaTest = Map.copyOf(alphaTest);
            backFaceCulling = Map.copyOf(backFaceCulling);
            drawBufferOverride = Map.copyOf(drawBufferOverride);
        }
    }

    /** How clouds are drawn when a pack is active. */
    public enum CloudSetting {
        /** Follow the player's video setting. */
        DEFAULT,
        FAST,
        FANCY,
        OFF;

        static Optional<CloudSetting> parse(String value) {
            try {
                return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /** Where particles are drawn relative to the deferred chain. */
    public enum ParticleOrdering {
        /** All particles before the deferred passes. */
        BEFORE,
        /** All particles after the deferred passes. */
        AFTER,
        /** Opaque particles before, translucent particles after. */
        MIXED;

        static Optional<ParticleOrdering> parse(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "before" -> Optional.of(BEFORE);
                case "after" -> Optional.of(AFTER);
                case "mixed" -> Optional.of(MIXED);
                default -> Optional.empty();
            };
        }
    }

    private final OrderedProperties raw;
    private final ProgramOverrides programOverrides;
    private final List<String> unknownDirectives = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private final boolean shadowEnabled;
    private final boolean shadowTerrain;
    private final boolean shadowEntities;
    private final boolean shadowBlockEntities;
    private final boolean shadowPlayer;
    private final Optional<Boolean> shadowCulling;
    private final boolean oldLighting;
    private final boolean oldHandLight;
    private final boolean separateAo;
    private final boolean voxelizeLightBlocks;
    private final boolean dynamicHandLight;
    private final boolean vignette;
    private final boolean underwaterOverlay;
    private final boolean sun;
    private final boolean moon;
    private final boolean stars;
    private final boolean sky;
    private final CloudSetting clouds;
    private final ParticleOrdering particleOrdering;
    private final List<String> sliderOptions;
    private final List<String> screenLayout;
    private final Map<String, List<String>> subScreens;
    private final Map<String, Integer> screenColumns;
    private final Map<String, String> customUniforms;
    private final Map<String, String> customVariables;

    private ShaderProperties(OrderedProperties raw) {
        this.raw = raw;

        this.shadowEnabled = flag(raw, "shadow.enabled", true);
        this.shadowTerrain = flag(raw, "shadowTerrain", true);
        this.shadowEntities = flag(raw, "shadowEntities", true);
        this.shadowBlockEntities = flag(raw, "shadowBlockEntities", true);
        this.shadowPlayer = flag(raw, "shadowPlayer", true);
        this.shadowCulling = raw.has("shadow.culling")
            ? raw.getBoolean("shadow.culling")
            : Optional.empty();
        this.oldLighting = flag(raw, "oldLighting", false);
        this.oldHandLight = flag(raw, "oldHandLight", true);
        this.separateAo = flag(raw, "separateAo", false);
        this.voxelizeLightBlocks = flag(raw, "voxelizeLightBlocks", false);
        this.dynamicHandLight = flag(raw, "dynamicHandLight", true);
        this.vignette = flag(raw, "vignette", true);
        this.underwaterOverlay = flag(raw, "underwaterOverlay", true);
        this.sun = flag(raw, "sun", true);
        this.moon = flag(raw, "moon", true);
        this.stars = flag(raw, "stars", true);
        this.sky = flag(raw, "sky", true);

        this.clouds = raw.get("clouds")
            .flatMap(CloudSetting::parse)
            .orElseGet(() -> {
                raw.get("clouds").ifPresent(v -> problems.add(
                    "clouds='" + v + "' is not one of default/fast/fancy/off"));
                return CloudSetting.DEFAULT;
            });
        this.particleOrdering = raw.get("particles.ordering")
            .flatMap(ParticleOrdering::parse)
            .orElse(ParticleOrdering.MIXED);

        this.sliderOptions = raw.getList("sliders");
        this.screenLayout = raw.getList("screen");
        this.subScreens = readSubScreens(raw);
        this.screenColumns = readScreenColumns(raw);
        this.customUniforms = readPrefixed(raw, "uniform.");
        this.customVariables = readPrefixed(raw, "variable.");
        this.programOverrides = readProgramOverrides(raw);

        recordUnknownDirectives(raw);
    }

    /** Parses {@code shaders.properties} text. */
    public static ShaderProperties parse(String text) {
        return new ShaderProperties(OrderedProperties.parse("shaders.properties", text));
    }

    /** An empty set of properties, for a pack that ships none. */
    public static ShaderProperties empty() {
        return parse("");
    }

    private static boolean flag(OrderedProperties raw, String key, boolean fallback) {
        return raw.getBoolean(key).orElse(fallback);
    }

    private static Map<String, List<String>> readSubScreens(OrderedProperties raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (OrderedProperties.Entry entry : raw.withPrefix("screen.")) {
            String name = entry.key().substring("screen.".length());
            // `screen.columns` configures the top-level screen's layout, not a sub-screen
            // called "columns"; `screen.WATER.columns` configures the WATER sub-screen.
            if (name.equals("columns") || name.endsWith(".columns")) {
                continue;
            }
            out.put(name, entry.value().isBlank()
                ? List.of()
                : List.of(entry.value().trim().split("\\s+")));
        }
        return Map.copyOf(out);
    }

    private static Map<String, Integer> readScreenColumns(OrderedProperties raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        raw.getInt("screen.columns").ifPresent(v -> out.put("", v));
        for (OrderedProperties.Entry entry : raw.withPrefix("screen.")) {
            // The top-level form has already been read above; only `screen.<name>.columns`
            // reaches the sub-screen branch, so the exact key must be excluded or the name
            // slice runs past its own start.
            if (entry.key().equals("screen.columns") || !entry.key().endsWith(".columns")) {
                continue;
            }
            String name = entry.key().substring("screen.".length(),
                entry.key().length() - ".columns".length());
            try {
                out.put(name, Integer.parseInt(entry.value().trim()));
            } catch (NumberFormatException ignored) {
                // Reported by OrderedProperties when read through getInt; a bad column count
                // simply falls back to the default layout.
            }
        }
        return Map.copyOf(out);
    }

    private static Map<String, String> readPrefixed(OrderedProperties raw, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (OrderedProperties.Entry entry : raw.withPrefix(prefix)) {
            out.put(entry.key().substring(prefix.length()), entry.value());
        }
        return Map.copyOf(out);
    }

    private ProgramOverrides readProgramOverrides(OrderedProperties raw) {
        Map<String, BlendMode> blend = new LinkedHashMap<>();
        Map<String, AlphaTest> alphaTest = new LinkedHashMap<>();
        Map<String, Boolean> backFace = new LinkedHashMap<>();
        Map<String, List<Integer>> drawBuffers = new LinkedHashMap<>();

        for (OrderedProperties.Entry entry : raw.withPrefix("blend.")) {
            String program = entry.key().substring("blend.".length());
            BlendMode.Parsed parsed = BlendMode.parse(entry.value());
            if (parsed.ok()) {
                blend.put(program, parsed.mode().orElseThrow());
            } else {
                problems.add("blend." + program + ": " + parsed.error());
            }
        }
        for (OrderedProperties.Entry entry : raw.withPrefix("alphaTest.")) {
            String program = entry.key().substring("alphaTest.".length());
            AlphaTest.Parsed parsed = AlphaTest.parse(entry.value());
            if (parsed.ok()) {
                alphaTest.put(program, parsed.test().orElseThrow());
            } else {
                problems.add("alphaTest." + program + ": " + parsed.error());
            }
        }
        for (String face : List.of("solid", "cutout", "cutoutMipped", "translucent")) {
            raw.getBoolean("backFace." + face)
                .ifPresent(value -> backFace.put(face, value));
        }
        for (OrderedProperties.Entry entry : raw.withPrefix("buffer.")) {
            // `buffer.<program>=<targets>` overrides the in-source DRAWBUFFERS directive.
            String program = entry.key().substring("buffer.".length());
            List<Integer> targets = new ArrayList<>();
            boolean ok = true;
            for (String part : entry.value().trim().split("[\\s,]+")) {
                if (part.isEmpty()) {
                    continue;
                }
                try {
                    targets.add(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    problems.add("buffer." + program + ": '" + part
                        + "' is not a render target index");
                    ok = false;
                    break;
                }
            }
            if (ok && !targets.isEmpty()) {
                drawBuffers.put(program, List.copyOf(targets));
            }
        }
        return new ProgramOverrides(blend, alphaTest, backFace, drawBuffers);
    }

    /**
     * Prefixes and exact keys Retina understands.
     *
     * <p>Kept as data rather than as scattered {@code if} statements so that
     * {@link #unknownDirectives()} can be computed by difference, which means a directive
     * added to the parser is automatically removed from the unknown list and one that is not
     * handled cannot be quietly forgotten.
     */
    private static final List<String> KNOWN_PREFIXES = List.of(
        "blend.", "alphaTest.", "backFace.", "buffer.", "scale.", "size.buffer.",
        "flip.", "program.", "screen.", "profile.", "uniform.", "variable.",
        "texture.", "customTexture.", "image.", "sliders", "shadow.", "particles.",
        "dh"
    );

    private static final List<String> KNOWN_KEYS = List.of(
        "clouds", "sun", "moon", "stars", "sky", "vignette", "underwaterOverlay",
        "shadowTerrain", "shadowEntities", "shadowBlockEntities", "shadowPlayer",
        "shadowLightBlockEntities", "oldLighting", "oldHandLight", "separateAo",
        "voxelizeLightBlocks", "dynamicHandLight", "screen", "profiles", "sliders",
        "rain.depth", "beacon.beam.depth", "separateEntityDraws", "frustum.culling",
        "occlusion.culling", "skipAllRendering", "supportsColorCorrection",
        "allowConcurrentCompute", "endFlashShadows", "indirect.pointer"
    );

    private void recordUnknownDirectives(OrderedProperties raw) {
        for (OrderedProperties.Entry entry : raw.entries()) {
            String key = entry.key();
            boolean known = KNOWN_KEYS.contains(key)
                || KNOWN_PREFIXES.stream().anyMatch(key::startsWith);
            if (!known) {
                unknownDirectives.add("line " + entry.line() + ": '" + key
                    + "' is not a directive Retina implements; it was ignored");
            }
        }
    }

    // ---- Accessors ----

    /** The underlying entries, for directives handled elsewhere. */
    public OrderedProperties raw() {
        return raw;
    }

    /** Per-program state overrides. */
    public ProgramOverrides programOverrides() {
        return programOverrides;
    }

    /** Directives Retina does not implement, reported to the user. */
    public List<String> unknownDirectives() {
        return List.copyOf(unknownDirectives);
    }

    /** Malformed values found while parsing, including those from the raw reader. */
    public List<String> problems() {
        List<String> out = new ArrayList<>(problems);
        raw.problems().forEach(p -> out.add(p.toString()));
        return List.copyOf(out);
    }

    /** Whether the shadow pass runs at all. */
    public boolean shadowEnabled() {
        return shadowEnabled;
    }

    /** Whether terrain is rendered into the shadow map. */
    public boolean shadowTerrain() {
        return shadowTerrain;
    }

    /** Whether entities are rendered into the shadow map. */
    public boolean shadowEntities() {
        return shadowEntities;
    }

    /** Whether block entities are rendered into the shadow map. */
    public boolean shadowBlockEntities() {
        return shadowBlockEntities;
    }

    /** Whether the player is rendered into the shadow map. */
    public boolean shadowPlayer() {
        return shadowPlayer;
    }

    /** The pack's shadow culling override, or empty to use Retina's default. */
    public Optional<Boolean> shadowCulling() {
        return shadowCulling;
    }

    /** Whether the pack asked for the pre-1.8 lighting model. */
    public boolean oldLighting() {
        return oldLighting;
    }

    /** Whether held-light uses the legacy behaviour. */
    public boolean oldHandLight() {
        return oldHandLight;
    }

    /** Whether ambient occlusion is delivered separately rather than folded into vertex colour. */
    public boolean separateAo() {
        return separateAo;
    }

    /** Whether light-emitting blocks are voxelised for the pack. */
    public boolean voxelizeLightBlocks() {
        return voxelizeLightBlocks;
    }

    /** Whether held items emit light. */
    public boolean dynamicHandLight() {
        return dynamicHandLight;
    }

    /** Whether the vanilla vignette is drawn. */
    public boolean vignette() {
        return vignette;
    }

    /** Whether the vanilla underwater overlay is drawn. */
    public boolean underwaterOverlay() {
        return underwaterOverlay;
    }

    /** Whether the vanilla sun is drawn. */
    public boolean sun() {
        return sun;
    }

    /** Whether the vanilla moon is drawn. */
    public boolean moon() {
        return moon;
    }

    /** Whether vanilla stars are drawn. */
    public boolean stars() {
        return stars;
    }

    /** Whether the vanilla sky is drawn. */
    public boolean sky() {
        return sky;
    }

    /** How clouds are drawn. */
    public CloudSetting clouds() {
        return clouds;
    }

    /** Where particles sit relative to the deferred chain. */
    public ParticleOrdering particleOrdering() {
        return particleOrdering;
    }

    /** Options the pack wants drawn as sliders. */
    public List<String> sliderOptions() {
        return sliderOptions;
    }

    /** The top-level option screen layout. */
    public List<String> screenLayout() {
        return screenLayout;
    }

    /** Named sub-screens and their contents. */
    public Map<String, List<String>> subScreens() {
        return subScreens;
    }

    /** Column counts, keyed by screen name with {@code ""} for the top level. */
    public Map<String, Integer> screenColumns() {
        return screenColumns;
    }

    /** {@code uniform.<name>=<expression>} entries. */
    public Map<String, String> customUniforms() {
        return customUniforms;
    }

    /** {@code variable.<name>=<expression>} entries. */
    public Map<String, String> customVariables() {
        return customVariables;
    }
}
