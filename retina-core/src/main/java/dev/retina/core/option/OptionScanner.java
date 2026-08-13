/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.option;

import dev.retina.core.RetinaLimits;
import dev.retina.core.pack.PackPath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers user-adjustable options by scanning shader source lines.
 *
 * <p>The recognised forms, which are the ones packs actually use:
 *
 * <pre>
 *   #define SHADOWS                       // a toggle, on by default
 *   //#define SHADOWS                     // a toggle, off by default
 *   #define QUALITY 2      // [1 2 3 4]   // a cycle/slider with explicit values
 *   #define QUALITY 2      // [1 2 3 4] Shadow quality     // ... plus a comment
 *   const int shadowMapResolution = 2048; // [512 1024 2048 4096]
 * </pre>
 *
 * <p>Two details are easy to get wrong and both change pack behaviour:
 *
 * <ul>
 *   <li>A {@code #define} with a value but <em>no</em> {@code [...]} annotation is not an
 *       option. Packs are full of internal defines with values; exposing them all would fill
 *       the UI with knobs that break the pack when turned. Only annotated declarations, and
 *       bare toggles, are options.
 *   <li>The same option name may be declared in several files. The declarations must agree;
 *       when they do not, the option is dropped and reported, because substituting one
 *       value into a file that declared different allowed values produces a pack that
 *       compiles and renders wrongly.
 * </ul>
 */
public final class OptionScanner {

    /**
     * {@code [ ... ]} annotation followed by optional comment text.
     *
     * <p>Written as an explicit scan rather than one large regex over the whole line so that
     * a value containing a bracket cannot make the match run away.
     */
    private static final Pattern DEFINE = Pattern.compile(
        "^\\s*(//\\s*)?#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(.*)$");

    private static final Pattern CONST = Pattern.compile(
        "^\\s*const\\s+(int|float|bool)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^;]*);\\s*(.*)$");

    /** Accumulated options, keyed by name, plus conflicts found along the way. */
    public static final class Result {
        private final Map<String, PackOption> options = new LinkedHashMap<>();
        private final Map<String, PackOption> rejected = new LinkedHashMap<>();
        private final List<String> problems = new ArrayList<>();

        /** Options that are safe to expose, in discovery order. */
        public Map<String, PackOption> options() {
            return Map.copyOf(options);
        }

        /** Options dropped because declarations disagreed. */
        public Map<String, PackOption> rejected() {
            return Map.copyOf(rejected);
        }

        /** Human-readable descriptions of every conflict or limit hit. */
        public List<String> problems() {
            return List.copyOf(problems);
        }
    }

    private final Result result = new Result();

    /**
     * Scans one file's lines.
     *
     * <p>Call once per source file; the scanner merges declarations across calls.
     */
    public void scan(PackPath file, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (result.options.size() + result.rejected.size() >= RetinaLimits.MAX_OPTIONS) {
                result.problems.add("stopped scanning options at "
                    + RetinaLimits.MAX_OPTIONS + "; this pack declares an unreasonable number");
                return;
            }
            parseLine(file, i + 1, lines.get(i)).ifPresent(this::merge);
        }
    }

    /** The accumulated result. */
    public Result result() {
        return result;
    }

    private void merge(PackOption found) {
        String name = found.name();
        if (result.rejected.containsKey(name)) {
            return;
        }
        PackOption existing = result.options.get(name);
        if (existing == null) {
            result.options.put(name, found);
            return;
        }
        if (compatible(existing, found)) {
            return;
        }
        result.options.remove(name);
        result.rejected.put(name, existing);
        result.problems.add("option '" + name + "' is declared inconsistently in "
            + existing.declaration().file().toAbsoluteString() + ":"
            + existing.declaration().line() + " and "
            + found.declaration().file().toAbsoluteString() + ":"
            + found.declaration().line()
            + "; Retina will not expose it because changing it would desynchronise the"
            + " declarations");
    }

    /**
     * Whether two declarations of the same option can be driven by one UI control.
     *
     * <p>They can when they are the same kind and offer the same choices. Default values are
     * allowed to differ — the first declaration wins — because packs commonly repeat a
     * default in a header and a program without intending two options.
     */
    private static boolean compatible(PackOption a, PackOption b) {
        if (a instanceof PackOption.BooleanOption && b instanceof PackOption.BooleanOption) {
            return true;
        }
        if (a instanceof PackOption.ValueOption va && b instanceof PackOption.ValueOption vb) {
            return va.allowedValues().equals(vb.allowedValues());
        }
        return false;
    }

    /** Parses a single line; visible for testing. */
    static Optional<PackOption> parseLine(PackPath file, int lineNumber, String line) {
        Matcher define = DEFINE.matcher(line);
        if (define.matches()) {
            boolean commentedOut = define.group(1) != null;
            String name = define.group(2);
            String rest = define.group(3);
            return parseDefine(file, lineNumber, commentedOut, name, rest);
        }
        Matcher constant = CONST.matcher(line);
        if (constant.matches()) {
            return parseConst(file, lineNumber, constant.group(1), constant.group(2),
                constant.group(3).trim(), constant.group(4));
        }
        return Optional.empty();
    }

    private static Optional<PackOption> parseDefine(PackPath file, int lineNumber,
                                                    boolean commentedOut, String name,
                                                    String rest) {
        Split split = splitValueAndComment(rest);
        PackOption.Declaration declaration =
            new PackOption.Declaration(file, lineNumber, PackOption.Declaration.Kind.DEFINE);

        if (split.value().isEmpty()) {
            // A bare `#define NAME`, commented out or not: a toggle.
            return Optional.of(new PackOption.BooleanOption(name, declaration,
                split.commentText(), !commentedOut));
        }
        if (split.allowedValues().isEmpty()) {
            // A valued define with no `[...]` annotation is internal, not an option.
            return Optional.empty();
        }
        return Optional.of(buildValueOption(name, declaration, split, split.value()));
    }

    private static Optional<PackOption> parseConst(PackPath file, int lineNumber, String glslType,
                                                   String name, String value, String trailing) {
        Split split = splitValueAndComment(trailing);
        PackOption.Declaration declaration =
            new PackOption.Declaration(file, lineNumber, PackOption.Declaration.Kind.CONST);

        if (glslType.equals("bool")) {
            // `const bool name = true;` is a toggle whose state is the literal, not whether
            // the line is commented out.
            boolean enabled = value.trim().equals("true");
            return Optional.of(new PackOption.BooleanOption(name, declaration,
                split.commentText(), enabled));
        }
        if (split.allowedValues().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(buildValueOption(name, declaration, split, value));
    }

    private static PackOption.ValueOption buildValueOption(String name,
                                                           PackOption.Declaration declaration,
                                                           Split split, String defaultValue) {
        List<String> allowed = new ArrayList<>(split.allowedValues());
        String normalisedDefault = defaultValue.trim();
        if (!allowed.contains(normalisedDefault)) {
            // The source default is authoritative even when it is not listed; a pack that
            // ships `#define X 3 // [1 2 4]` still has to compile with 3.
            allowed.addFirst(normalisedDefault);
        }
        return new PackOption.ValueOption(name, declaration, split.commentText(), allowed,
            normalisedDefault, PackOption.ValueType.infer(allowed));
    }

    /** The three parts of a declaration's tail: value, allowed values, comment. */
    record Split(String value, List<String> allowedValues, Optional<String> commentText) {
    }

    /**
     * Splits the text after a declaration into value, {@code [...]} choices, and comment.
     *
     * <p>The value ends at the first {@code //} or {@code /*}. The annotation, when present,
     * is the first bracketed group inside the comment; anything after it is the label shown
     * in the UI.
     */
    static Split splitValueAndComment(String rest) {
        String text = rest == null ? "" : rest;
        int lineComment = text.indexOf("//");
        int blockComment = text.indexOf("/*");
        int commentStart = -1;
        int commentSkip = 0;
        if (lineComment >= 0 && (blockComment < 0 || lineComment < blockComment)) {
            commentStart = lineComment;
            commentSkip = 2;
        } else if (blockComment >= 0) {
            commentStart = blockComment;
            commentSkip = 2;
        }

        String value = (commentStart < 0 ? text : text.substring(0, commentStart)).trim();
        if (commentStart < 0) {
            return new Split(value, List.of(), Optional.empty());
        }

        String comment = text.substring(commentStart + commentSkip);
        int blockEnd = comment.indexOf("*/");
        if (blockComment >= 0 && commentStart == blockComment && blockEnd >= 0) {
            comment = comment.substring(0, blockEnd);
        }
        comment = comment.strip();

        List<String> allowed = List.of();
        String label = comment;
        if (comment.startsWith("[")) {
            int close = comment.indexOf(']');
            if (close > 0) {
                String inside = comment.substring(1, close).trim();
                allowed = inside.isEmpty() ? List.of() : List.of(inside.split("\\s+"));
                label = comment.substring(close + 1).strip();
            }
        }
        return new Split(value, allowed, label.isEmpty() ? Optional.empty() : Optional.of(label));
    }
}
