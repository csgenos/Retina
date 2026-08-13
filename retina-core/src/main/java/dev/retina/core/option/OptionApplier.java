/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.option;

import dev.retina.core.pack.PackPath;
import dev.retina.core.preprocess.LineRewriter;

/**
 * Applies chosen option values by rewriting the exact lines they were declared on.
 *
 * <p>Rewriting in place rather than injecting {@code #define}s into the prologue is the only
 * approach that is correct for both declaration forms:
 *
 * <ul>
 *   <li>a prologue {@code #define QUALITY 4} is overwritten by the pack's own
 *       {@code #define QUALITY 2} later in the file, silently ignoring the user's choice;
 *   <li>{@code const int shadowMapResolution = 2048;} cannot be influenced by a macro at all.
 * </ul>
 *
 * <p>The rewrite preserves the line's structure — including its indentation and trailing
 * comment — so that error messages and {@code #line} accounting still line up, and so that a
 * pack author reading Retina's dumped source sees something recognisable.
 */
public final class OptionApplier implements LineRewriter {
    private final OptionSet options;
    private final OptionValues values;

    public OptionApplier(OptionSet options, OptionValues values) {
        this.options = options;
        this.values = values;
    }

    @Override
    public String rewrite(PackPath file, int line, String text) {
        if (!options.touches(file)) {
            return text;
        }
        return options.declaredAt(file, line)
            .map(option -> apply(option, text))
            .orElse(text);
    }

    private String apply(PackOption option, String text) {
        String chosen = values.valueOf(option);
        if (chosen.equals(option.defaultValueText())) {
            return text;
        }
        return switch (option) {
            case PackOption.BooleanOption toggle -> applyToggle(toggle, text, chosen);
            case PackOption.ValueOption value -> applyValue(value, text, chosen);
        };
    }

    /**
     * Turns a toggle on by uncommenting its declaration, or off by commenting it out.
     *
     * <p>A {@code const bool} toggle instead has its literal replaced, because commenting out
     * a {@code const} declaration removes a symbol the rest of the shader references and
     * produces a compile error rather than a disabled feature.
     */
    private static String applyToggle(PackOption.BooleanOption option, String text,
                                      String chosen) {
        boolean enable = Boolean.parseBoolean(chosen);
        if (option.declaration().kind() == PackOption.Declaration.Kind.CONST) {
            return replaceFirstToken(text, option.defaultEnabled() ? "true" : "false",
                enable ? "true" : "false");
        }
        int hash = text.indexOf('#');
        if (enable) {
            int commentStart = text.indexOf("//");
            if (commentStart < 0 || hash < 0 || commentStart > hash) {
                return text;
            }
            // Drop the `//` and any whitespace between it and the `#`, keeping indentation.
            return text.substring(0, commentStart) + text.substring(hash);
        }
        if (hash < 0) {
            return text;
        }
        return text.substring(0, hash) + "//" + text.substring(hash);
    }

    /**
     * Replaces a value option's literal with the chosen one.
     *
     * <p>Only the value token is touched: the {@code // [1 2 3 4] Label} annotation is left
     * intact so a re-scan of the rewritten source would find the same option.
     */
    private static String applyValue(PackOption.ValueOption option, String text,
                                     String chosen) {
        return switch (option.declaration().kind()) {
            case DEFINE -> replaceDefineValue(text, option.name(), chosen);
            case CONST -> replaceConstValue(text, chosen);
        };
    }

    private static String replaceDefineValue(String text, String name, String chosen) {
        int nameAt = indexOfToken(text, name);
        if (nameAt < 0) {
            return text;
        }
        int valueStart = nameAt + name.length();
        while (valueStart < text.length() && Character.isWhitespace(text.charAt(valueStart))) {
            valueStart++;
        }
        int valueEnd = endOfValue(text, valueStart);
        if (valueEnd <= valueStart) {
            return text;
        }
        return text.substring(0, valueStart) + chosen + text.substring(valueEnd);
    }

    private static String replaceConstValue(String text, String chosen) {
        int equals = text.indexOf('=');
        int semicolon = text.indexOf(';', equals + 1);
        if (equals < 0 || semicolon < 0) {
            return text;
        }
        return text.substring(0, equals + 1) + " " + chosen + text.substring(semicolon);
    }

    /** The end of the value token, stopping at a comment or end of line. */
    private static int endOfValue(String text, int from) {
        int lineComment = text.indexOf("//", from);
        int blockComment = text.indexOf("/*", from);
        int end = text.length();
        if (lineComment >= 0) {
            end = Math.min(end, lineComment);
        }
        if (blockComment >= 0) {
            end = Math.min(end, blockComment);
        }
        while (end > from && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    /** Index of {@code token} as a whole identifier, or -1. */
    private static int indexOfToken(String text, String token) {
        int from = 0;
        while (true) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return -1;
            }
            boolean leftOk = at == 0 || !isIdentifierChar(text.charAt(at - 1));
            int after = at + token.length();
            boolean rightOk = after >= text.length() || !isIdentifierChar(text.charAt(after));
            if (leftOk && rightOk) {
                return at;
            }
            from = at + 1;
        }
    }

    private static String replaceFirstToken(String text, String token, String replacement) {
        int at = indexOfToken(text, token);
        return at < 0 ? text : text.substring(0, at) + replacement
            + text.substring(at + token.length());
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
