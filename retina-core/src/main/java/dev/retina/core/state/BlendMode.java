/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.state;

import java.util.Locale;
import java.util.Optional;

/**
 * A blend-state override declared by {@code blend.<program>} or {@code blend.<program>.<n>}.
 *
 * <p>Packs express blending with OpenGL {@code glBlendFuncSeparate} factor names. Those map
 * one-to-one onto {@code VkBlendFactor}, so the translation is exact rather than approximate
 * — with two exceptions that are recorded rather than silently dropped:
 *
 * <ul>
 *   <li>{@code SRC_ALPHA_SATURATE} is only valid as a source factor in Vulkan, matching
 *       OpenGL, so a pack using it as a destination factor is refused;
 *   <li>dual-source factors ({@code SRC1_*}) require {@code dualSrcBlend}, and are reported
 *       as a capability requirement instead of being downgraded.
 * </ul>
 */
public record BlendMode(Factor srcRgb, Factor dstRgb, Factor srcAlpha, Factor dstAlpha) {

    /** {@code OFF} in a pack's blend directive: blending disabled for the target. */
    public static final BlendMode DISABLED = new BlendMode(null, null, null, null);

    /** The vanilla alpha-blend state a program gets when the pack says nothing. */
    public static final BlendMode DEFAULT = new BlendMode(
        Factor.SRC_ALPHA, Factor.ONE_MINUS_SRC_ALPHA, Factor.ONE, Factor.ONE_MINUS_SRC_ALPHA);

    /** Whether blending is enabled at all. */
    public boolean enabled() {
        return srcRgb != null;
    }

    /** Whether this mode needs {@code VkPhysicalDeviceFeatures::dualSrcBlend}. */
    public boolean requiresDualSourceBlend() {
        return enabled() && (srcRgb.dualSource || dstRgb.dualSource
            || srcAlpha.dualSource || dstAlpha.dualSource);
    }

    /**
     * Parses a blend directive value.
     *
     * <p>Accepts the two spellings packs use: two factors (applied to both RGB and alpha) or
     * four factors (separate RGB and alpha). {@code off} disables blending.
     *
     * @return the parsed mode, or empty with a reason in {@code error} when unsupported
     */
    public static Parsed parse(String value) {
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("off") || trimmed.equalsIgnoreCase("false")) {
            return new Parsed(Optional.of(DISABLED), null);
        }
        String[] parts = trimmed.isEmpty() ? new String[0] : trimmed.split("[\\s,]+");
        if (parts.length != 2 && parts.length != 4) {
            return new Parsed(Optional.empty(),
                "expected 'off', two blend factors, or four blend factors, but found "
                    + parts.length + " token(s)");
        }
        Factor[] factors = new Factor[4];
        for (int i = 0; i < parts.length; i++) {
            Optional<Factor> factor = Factor.parse(parts[i]);
            if (factor.isEmpty()) {
                return new Parsed(Optional.empty(), "unknown blend factor '" + parts[i] + "'");
            }
            factors[i] = factor.get();
        }
        if (parts.length == 2) {
            factors[2] = factors[0];
            factors[3] = factors[1];
        }
        if (factors[1] == Factor.SRC_ALPHA_SATURATE || factors[3] == Factor.SRC_ALPHA_SATURATE) {
            return new Parsed(Optional.empty(),
                "SRC_ALPHA_SATURATE is only valid as a source factor");
        }
        return new Parsed(
            Optional.of(new BlendMode(factors[0], factors[1], factors[2], factors[3])), null);
    }

    /** Parse outcome: either a mode or a precise reason it was refused. */
    public record Parsed(Optional<BlendMode> mode, String error) {
        public boolean ok() {
            return mode.isPresent();
        }
    }

    /**
     * An OpenGL blend factor and its {@code VkBlendFactor} equivalent.
     *
     * <p>The {@code vk} values are the numeric {@code VkBlendFactor} enumerants; they are
     * written literally rather than referencing {@code VK10} so this class stays usable in
     * unit tests without loading the Vulkan natives.
     */
    public enum Factor {
        ZERO(0, false),
        ONE(1, false),
        SRC_COLOR(2, false),
        ONE_MINUS_SRC_COLOR(3, false),
        DST_COLOR(4, false),
        ONE_MINUS_DST_COLOR(5, false),
        SRC_ALPHA(6, false),
        ONE_MINUS_SRC_ALPHA(7, false),
        DST_ALPHA(8, false),
        ONE_MINUS_DST_ALPHA(9, false),
        CONSTANT_COLOR(10, false),
        ONE_MINUS_CONSTANT_COLOR(11, false),
        CONSTANT_ALPHA(12, false),
        ONE_MINUS_CONSTANT_ALPHA(13, false),
        SRC_ALPHA_SATURATE(14, false),
        SRC1_COLOR(15, true),
        ONE_MINUS_SRC1_COLOR(16, true),
        SRC1_ALPHA(17, true),
        ONE_MINUS_SRC1_ALPHA(18, true);

        private final int vk;
        private final boolean dualSource;

        Factor(int vk, boolean dualSource) {
            this.vk = vk;
            this.dualSource = dualSource;
        }

        /** The {@code VkBlendFactor} enumerant. */
        public int vkBlendFactor() {
            return vk;
        }

        /** Whether this factor reads the second colour output. */
        public boolean isDualSource() {
            return dualSource;
        }

        /** Parses a factor name, tolerating the {@code GL_} prefix packs sometimes write. */
        public static Optional<Factor> parse(String token) {
            String name = token.trim().toUpperCase(Locale.ROOT);
            if (name.startsWith("GL_")) {
                name = name.substring(3);
            }
            // Aliases that appear in real packs.
            name = switch (name) {
                case "SRC_ALPHA_SATURATED" -> "SRC_ALPHA_SATURATE";
                case "ONE_MINUS_SRC1_COLOUR" -> "ONE_MINUS_SRC1_COLOR";
                default -> name;
            };
            try {
                return Optional.of(valueOf(name));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }
}
