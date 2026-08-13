/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.target;

import dev.retina.core.RetinaLimits;
import dev.retina.core.props.OrderedProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The render targets a pack declares, and how they behave across a frame.
 *
 * <p>A target is declared implicitly by being written or read; its format, clear behaviour
 * and scale come from {@code const} directives in shader source and from
 * {@code shaders.properties}. Targets that are never mentioned still exist at their default
 * format, because a pack may read {@code colortex3} without ever declaring it.
 *
 * <p>The <em>flip</em> behaviour is the subtle part. Each colour target is double buffered:
 * a pass that reads {@code colortex0} and also writes it must not read the image it is
 * writing. The format handles this by flipping the pair after any pass that writes the
 * target, so the next read sees what was just written. Retina models this explicitly as two
 * render-graph resources per target plus a flip state, rather than emulating history by
 * sampling an image that is simultaneously an attachment — which is undefined behaviour in
 * Vulkan and is the single most common source of "works on NVIDIA, garbage on AMD".
 */
public final class RenderTargetDirectives {

    /** The default format for a colour target when the pack says nothing. */
    public static final TargetFormat DEFAULT_COLOR_FORMAT = TargetFormat.RGBA8;

    /** Settings for one target. */
    public record TargetSettings(int index, TargetFormat format, boolean clear,
                                 float[] clearColor, boolean mipmap) {

        public TargetSettings {
            clearColor = clearColor.clone();
        }

        /** The clear colour as {@code {r, g, b, a}}. */
        public float[] clearColorCopy() {
            return clearColor.clone();
        }

        /** The default settings for {@code index}. */
        public static TargetSettings defaults(int index) {
            return new TargetSettings(index, DEFAULT_COLOR_FORMAT, true,
                new float[]{0f, 0f, 0f, index == 0 ? 1f : 0f}, false);
        }
    }

    private final Map<Integer, TargetSettings> colorTargets = new LinkedHashMap<>();
    private final Map<Integer, TargetSettings> shadowColorTargets = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();

    /** Settings for {@code colortex<index>}, defaulted when the pack said nothing. */
    public TargetSettings colorTarget(int index) {
        return colorTargets.computeIfAbsent(index, TargetSettings::defaults);
    }

    /** Settings for {@code shadowcolor<index>}. */
    public TargetSettings shadowColorTarget(int index) {
        return shadowColorTargets.computeIfAbsent(index, TargetSettings::defaults);
    }

    /** Every explicitly configured colour target. */
    public Map<Integer, TargetSettings> configuredColorTargets() {
        return Map.copyOf(colorTargets);
    }

    /** Every explicitly configured shadow colour target. */
    public Map<Integer, TargetSettings> configuredShadowColorTargets() {
        return Map.copyOf(shadowColorTargets);
    }

    /** Problems found while reading directives. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /**
     * Applies a {@code const} directive found in shader source.
     *
     * <p>Recognises the format-declaration form packs use:
     *
     * <pre>
     *   const int colortex0Format = RGBA16F;
     *   const bool colortex0Clear = false;
     *   const vec4 colortex0ClearColor = vec4(0.0, 0.0, 0.0, 1.0);
     *   const bool colortex0MipmapEnabled = true;
     *   const bool shadowcolor0Nearest = true;
     * </pre>
     *
     * @return whether the directive was recognised and applied
     */
    public boolean applyConstDirective(String name, String value) {
        for (Suffix suffix : Suffix.values()) {
            if (!name.endsWith(suffix.text)) {
                continue;
            }
            String stem = name.substring(0, name.length() - suffix.text.length());
            Optional<TargetRef> ref = parseTargetName(stem);
            if (ref.isEmpty()) {
                continue;
            }
            return apply(ref.get(), suffix, value);
        }
        return false;
    }

    /** The suffixes a target directive may carry. */
    private enum Suffix {
        FORMAT("Format"),
        CLEAR("Clear"),
        CLEAR_COLOR("ClearColor"),
        MIPMAP_ENABLED("MipmapEnabled");

        final String text;

        Suffix(String text) {
            this.text = text;
        }
    }

    /** Which family and index a directive names. */
    private record TargetRef(boolean shadow, int index) {
    }

    private static Optional<TargetRef> parseTargetName(String stem) {
        if (stem.startsWith("colortex")) {
            return parseIndex(stem.substring("colortex".length()))
                .map(i -> new TargetRef(false, i));
        }
        if (stem.startsWith("shadowcolor")) {
            return parseIndex(stem.substring("shadowcolor".length()))
                .map(i -> new TargetRef(true, i));
        }
        // Legacy names for the first eight colour targets.
        int legacy = switch (stem) {
            case "gcolor" -> 0;
            case "gdepth" -> 1;
            case "gnormal" -> 2;
            case "composite" -> 3;
            case "gaux1" -> 4;
            case "gaux2" -> 5;
            case "gaux3" -> 6;
            case "gaux4" -> 7;
            default -> -1;
        };
        return legacy < 0 ? Optional.empty() : Optional.of(new TargetRef(false, legacy));
    }

    private static Optional<Integer> parseIndex(String digits) {
        if (digits.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(digits));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private boolean apply(TargetRef ref, Suffix suffix, String value) {
        int limit = ref.shadow()
            ? RetinaLimits.MAX_SHADOW_COLOR_TARGETS
            : RetinaLimits.MAX_COLOR_TARGETS;
        if (ref.index() < 0 || ref.index() >= limit) {
            problems.add((ref.shadow() ? "shadowcolor" : "colortex") + ref.index()
                + " is outside the supported range 0.." + (limit - 1));
            return false;
        }
        Map<Integer, TargetSettings> map = ref.shadow() ? shadowColorTargets : colorTargets;
        TargetSettings current = map.computeIfAbsent(ref.index(), TargetSettings::defaults);

        switch (suffix) {
            case FORMAT -> {
                Optional<TargetFormat> format = TargetFormat.parse(value);
                if (format.isEmpty()) {
                    problems.add("unknown render target format '" + value + "' for "
                        + (ref.shadow() ? "shadowcolor" : "colortex") + ref.index());
                    return false;
                }
                if (format.get().vkFormat().isEmpty()) {
                    problems.add("render target format " + format.get().glName()
                        + " has no Vulkan equivalent and cannot be used for "
                        + (ref.shadow() ? "shadowcolor" : "colortex") + ref.index());
                    return false;
                }
                map.put(ref.index(), new TargetSettings(ref.index(), format.get(),
                    current.clear(), current.clearColorCopy(), current.mipmap()));
            }
            case CLEAR -> map.put(ref.index(), new TargetSettings(ref.index(), current.format(),
                Boolean.parseBoolean(value.trim()), current.clearColorCopy(), current.mipmap()));
            case CLEAR_COLOR -> {
                Optional<float[]> colour = parseVec4(value);
                if (colour.isEmpty()) {
                    problems.add("could not read a vec4 clear colour from '" + value + "'");
                    return false;
                }
                map.put(ref.index(), new TargetSettings(ref.index(), current.format(),
                    current.clear(), colour.get(), current.mipmap()));
            }
            case MIPMAP_ENABLED -> map.put(ref.index(), new TargetSettings(ref.index(),
                current.format(), current.clear(), current.clearColorCopy(),
                Boolean.parseBoolean(value.trim())));
        }
        return true;
    }

    /** Parses {@code vec4(a, b, c, d)} or a bare comma-separated list. */
    static Optional<float[]> parseVec4(String value) {
        String text = value.trim();
        if (text.toLowerCase(Locale.ROOT).startsWith("vec4")) {
            int open = text.indexOf('(');
            int close = text.lastIndexOf(')');
            if (open < 0 || close < open) {
                return Optional.empty();
            }
            text = text.substring(open + 1, close);
        }
        String[] parts = text.split("[,\\s]+");
        if (parts.length == 1 && !parts[0].isBlank()) {
            try {
                float all = Float.parseFloat(parts[0]);
                return Optional.of(new float[]{all, all, all, all});
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        if (parts.length != 4) {
            return Optional.empty();
        }
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            try {
                out[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.of(out);
    }

    /**
     * Applies the {@code size.buffer.<name>} and {@code scale.<name>} entries from
     * {@code shaders.properties}.
     *
     * @return the per-pass viewport scales, keyed by program source name
     */
    public Map<String, PassScale> readPassScales(OrderedProperties properties) {
        Map<String, PassScale> scales = new LinkedHashMap<>();
        for (OrderedProperties.Entry entry : properties.withPrefix("scale.")) {
            String program = entry.key().substring("scale.".length());
            String[] parts = entry.value().trim().split("[\\s,]+");
            try {
                float scale = Float.parseFloat(parts[0]);
                float offsetX = parts.length > 1 ? Float.parseFloat(parts[1]) : 0f;
                float offsetY = parts.length > 2 ? Float.parseFloat(parts[2]) : 0f;
                if (!(scale > 0f) || scale > 16f) {
                    problems.add("scale." + program + " is " + scale
                        + "; only values in (0, 16] are supported");
                    continue;
                }
                scales.put(program, new PassScale(scale, offsetX, offsetY));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                problems.add("scale." + program + " could not be read from '"
                    + entry.value() + "'");
            }
        }
        return scales;
    }

    /**
     * A viewport scale applied to one pass.
     *
     * @param scale fraction of the full render resolution
     * @param offsetX viewport x offset in normalised units
     * @param offsetY viewport y offset in normalised units
     */
    public record PassScale(float scale, float offsetX, float offsetY) {
        /** The pixel width for a full-resolution width of {@code width}. */
        public int widthFor(int width) {
            return Math.max(1, Math.round(width * scale));
        }

        /** The pixel height for a full-resolution height of {@code height}. */
        public int heightFor(int height) {
            return Math.max(1, Math.round(height * scale));
        }
    }
}
