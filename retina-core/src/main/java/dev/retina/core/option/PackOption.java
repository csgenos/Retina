/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.option;

import dev.retina.core.pack.PackPath;

import java.util.List;
import java.util.Optional;

/**
 * One user-adjustable option declared in a pack's shader source.
 *
 * <p>Options are not declared in {@code shaders.properties}; they are declared as annotated
 * {@code #define} and {@code const} lines inside shader source, and {@code shaders.properties}
 * only lays them out in the UI. That means option discovery has to scan source files, and it
 * has to preserve the exact textual form of every value, because the value is substituted
 * back into GLSL verbatim. Reformatting {@code 1.0} into {@code 1.0f} or {@code 1} changes
 * the meaning of the pack's arithmetic.
 */
public sealed interface PackOption permits PackOption.BooleanOption, PackOption.ValueOption {

    /** The macro or constant name, e.g. {@code SHADOW_QUALITY}. */
    String name();

    /** Where the option was declared. */
    Declaration declaration();

    /** The untranslated comment text following the declaration, if any. */
    Optional<String> comment();

    /** The value used when the user has not chosen one. */
    String defaultValueText();

    /** Where an option was found, for diagnostics and for deduplication across files. */
    record Declaration(PackPath file, int line, Kind kind) {
        /** How the option was written in source. */
        public enum Kind {
            /** {@code #define NAME} or {@code #define NAME value}. */
            DEFINE,
            /** {@code const int NAME = value;}. */
            CONST
        }
    }

    /**
     * A toggle, declared as a bare {@code #define NAME} that may be commented out.
     *
     * <p>The on state is the macro being defined; the off state is the line being commented
     * out. Retina rewrites the line rather than appending {@code #undef}, because a pack may
     * legitimately {@code #define} the same name again later and an appended {@code #undef}
     * would change which definition wins.
     *
     * @param defaultEnabled whether the declaration was uncommented in source
     */
    record BooleanOption(String name, Declaration declaration, Optional<String> comment,
                         boolean defaultEnabled) implements PackOption {
        @Override
        public String defaultValueText() {
            return Boolean.toString(defaultEnabled);
        }
    }

    /**
     * A multi-valued option: a slider or a cycle through explicit choices.
     *
     * @param allowedValues the values from the {@code [...]} annotation, in declared order
     * @param defaultValueText the value present in source, always included in
     *                         {@code allowedValues} after normalisation
     */
    record ValueOption(String name, Declaration declaration, Optional<String> comment,
                       List<String> allowedValues, String defaultValueText, ValueType type)
        implements PackOption {

        public ValueOption {
            allowedValues = List.copyOf(allowedValues);
        }

        /** Whether the option should be drawn as a slider rather than a cycle button. */
        public boolean sliderCapable() {
            return allowedValues.size() > 2 && type != ValueType.STRING;
        }

        /** The index of {@code value} among the allowed values, or -1. */
        public int indexOf(String value) {
            return allowedValues.indexOf(value);
        }
    }

    /** How a value option's text should be interpreted for sorting and validation. */
    enum ValueType {
        INT, FLOAT, STRING;

        /** Infers a type from a set of literal value texts. */
        public static ValueType infer(List<String> values) {
            boolean allInt = !values.isEmpty();
            boolean allFloat = !values.isEmpty();
            for (String value : values) {
                String v = value.trim();
                if (v.isEmpty()) {
                    return STRING;
                }
                try {
                    Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    allInt = false;
                }
                try {
                    // Reject the `f` suffix here: GLSL accepts it but `Float.parseFloat`
                    // accepts things GLSL does not, such as `0x1p3` and `NaN`.
                    if (!v.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?f?")) {
                        allFloat = false;
                    }
                } catch (RuntimeException e) {
                    allFloat = false;
                }
            }
            if (allInt) {
                return INT;
            }
            return allFloat ? FLOAT : STRING;
        }
    }
}
