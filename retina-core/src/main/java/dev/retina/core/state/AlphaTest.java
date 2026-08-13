/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.state;

import java.util.Locale;
import java.util.Optional;

/**
 * An {@code alphaTest.<program>} override.
 *
 * <p>Fixed-function alpha testing does not exist in Vulkan and does not exist in core
 * OpenGL either, so this is not a state bit: it is compiled into the fragment shader as a
 * {@code discard}. The translation layer injects the comparison at the end of {@code main},
 * which means the alpha test participates in early-fragment-test suppression exactly as it
 * does under Iris, and the reference value has to be a specialisation input rather than a
 * pipeline field.
 *
 * @param function the comparison applied to the output alpha
 * @param reference the value compared against
 */
public record AlphaTest(Function function, float reference) {

    /** No alpha test: every fragment survives. */
    public static final AlphaTest ALWAYS = new AlphaTest(Function.ALWAYS, 0.0f);

    /** The vanilla cutout threshold used for {@code gbuffers_terrain} cutout passes. */
    public static final AlphaTest VANILLA_CUTOUT = new AlphaTest(Function.GREATER, 0.1f);

    /** Whether this test can ever discard a fragment. */
    public boolean discards() {
        return function != Function.ALWAYS;
    }

    /**
     * The GLSL condition under which a fragment must be discarded.
     *
     * @param alphaExpression the expression yielding the fragment's alpha
     */
    public String discardCondition(String alphaExpression) {
        return switch (function) {
            case ALWAYS -> "false";
            case NEVER -> "true";
            case LESS -> "!(" + alphaExpression + " < " + glslFloat(reference) + ")";
            case LEQUAL -> "!(" + alphaExpression + " <= " + glslFloat(reference) + ")";
            case EQUAL -> "!(" + alphaExpression + " == " + glslFloat(reference) + ")";
            case GEQUAL -> "!(" + alphaExpression + " >= " + glslFloat(reference) + ")";
            case GREATER -> "!(" + alphaExpression + " > " + glslFloat(reference) + ")";
            case NOTEQUAL -> "!(" + alphaExpression + " != " + glslFloat(reference) + ")";
        };
    }

    private static String glslFloat(float value) {
        // Always emit a decimal point so the literal is a float, never an int.
        String text = Float.toString(value);
        return text.contains(".") || text.contains("e") || text.contains("E") ? text : text + ".0";
    }

    /**
     * Parses an {@code alphaTest} value such as {@code GREATER 0.1} or {@code off}.
     */
    public static Parsed parse(String value) {
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("off") || trimmed.equalsIgnoreCase("false")) {
            return new Parsed(Optional.of(ALWAYS), null);
        }
        String[] parts = trimmed.split("[\\s,]+");
        if (parts.length == 0 || parts.length > 2) {
            return new Parsed(Optional.empty(),
                "expected 'off' or '<function> <reference>' but found '" + trimmed + "'");
        }
        Optional<Function> function = Function.parse(parts[0]);
        if (function.isEmpty()) {
            return new Parsed(Optional.empty(), "unknown alpha test function '" + parts[0] + "'");
        }
        float reference = 0.0f;
        if (parts.length == 2) {
            try {
                reference = Float.parseFloat(parts[1]);
            } catch (NumberFormatException e) {
                return new Parsed(Optional.empty(),
                    "alpha test reference '" + parts[1] + "' is not a number");
            }
        }
        return new Parsed(Optional.of(new AlphaTest(function.get(), reference)), null);
    }

    /** Parse outcome: either a test or a precise reason it was refused. */
    public record Parsed(Optional<AlphaTest> test, String error) {
        public boolean ok() {
            return test.isPresent();
        }
    }

    /** The comparison functions the pack format allows. */
    public enum Function {
        NEVER, LESS, EQUAL, LEQUAL, GREATER, NOTEQUAL, GEQUAL, ALWAYS;

        /** Parses a function name, tolerating {@code GL_} prefixes. */
        public static Optional<Function> parse(String token) {
            String name = token.trim().toUpperCase(Locale.ROOT);
            if (name.startsWith("GL_")) {
                name = name.substring(3);
            }
            name = switch (name) {
                case "LESS_OR_EQUAL", "LESSEQUAL" -> "LEQUAL";
                case "GREATER_OR_EQUAL", "GREATEREQUAL" -> "GEQUAL";
                case "NOT_EQUAL" -> "NOTEQUAL";
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
