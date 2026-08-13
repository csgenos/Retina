/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.preprocess;

import dev.retina.core.RetinaLimits;
import dev.retina.core.pack.PackPath;
import dev.retina.core.pack.PackSource;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Expands {@code #include} directives and injects Retina's define prologue.
 *
 * <p>This stage deliberately does <em>not</em> evaluate {@code #if}/{@code #ifdef}. Those are
 * left for the GLSL compiler, which already implements them correctly, and folding them here
 * would mean reimplementing the whole C preprocessor expression grammar with subtly different
 * semantics. What must happen here is the part GLSL compilers do not do:
 *
 * <ul>
 *   <li>{@code #include} is not part of core GLSL and is not enabled in shaderc by default
 *       for pack-style relative paths, so includes are resolved against the pack;
 *   <li>the include chain has to be recorded for error provenance;
 *   <li>include cycles and include bombs must be stopped before the compiler sees them.
 * </ul>
 *
 * <p>Include semantics follow the shader-pack contract rather than C: a path beginning with
 * {@code /} is resolved from the {@code shaders/} root, and anything else is resolved
 * relative to the directory of the file containing the directive. Both quoted and
 * angle-bracket forms behave identically, because packs use them interchangeably.
 */
public final class ShaderPreprocessor {
    private final PackSource source;
    private final PackPath shadersRoot;

    public ShaderPreprocessor(PackSource source, PackPath shadersRoot) {
        this.source = source;
        this.shadersRoot = shadersRoot;
    }

    /** Thrown when preprocessing cannot continue; the message is shown to the user. */
    public static final class PreprocessException extends RuntimeException {
        private final SourceLocation location;

        public PreprocessException(String message, SourceLocation location) {
            super(message);
            this.location = location;
        }

        /** Where the failure occurred, or {@code null} when not attributable. */
        public SourceLocation location() {
            return location;
        }

        @Override
        public String getMessage() {
            return location == null
                ? super.getMessage()
                : location.describe() + ": " + super.getMessage();
        }
    }

    /**
     * Preprocesses {@code entryPoint}.
     *
     * @param defines macros injected after the {@code #version} line, in iteration order
     * @throws PreprocessException on a cycle, a missing include, or a limit breach
     */
    public PreprocessedSource process(PackPath entryPoint, Map<String, String> defines)
        throws IOException {
        return process(entryPoint, defines, LineRewriter.IDENTITY);
    }

    /**
     * Preprocesses {@code entryPoint}, rewriting each line through {@code rewriter}.
     *
     * <p>The rewriter is how chosen option values reach the source; see
     * {@link dev.retina.core.option.OptionApplier}.
     *
     * @throws PreprocessException on a cycle, a missing include, or a limit breach
     */
    public PreprocessedSource process(PackPath entryPoint, Map<String, String> defines,
                                      LineRewriter rewriter) throws IOException {
        String rootText = source.readText(entryPoint)
            .orElseThrow(() -> new PreprocessException(
                "shader source not found", new SourceLocation(entryPoint, 1, List.of())));

        PreprocessedSource.Builder out = new PreprocessedSource.Builder();
        Set<PackPath> included = new LinkedHashSet<>();
        included.add(entryPoint);
        Expansion expansion = new Expansion(out, included, new ArrayDeque<>(), rewriter,
            new int[]{RetinaLimits.MAX_INCLUDE_EXPANSIONS});

        // The `#version` directive must be the first non-comment, non-blank token in a GLSL
        // translation unit, so the prologue can only be injected after it. Packs are not
        // consistent about where they put it, so it is located rather than assumed.
        List<String> rootLines = splitLines(rootText);
        int versionLine = findVersionLine(rootLines);

        if (versionLine < 0) {
            // No `#version` at all. Emit the prologue first; the translation layer supplies a
            // version directive, and a pack with no version gets the format's implicit 110.
            emitPrologue(out, defines);
            expandRange(expansion, entryPoint, rootLines, 0, rootLines.size());
        } else {
            expandRange(expansion, entryPoint, rootLines, 0, versionLine + 1);
            emitPrologue(out, defines);
            expandRange(expansion, entryPoint, rootLines, versionLine + 1, rootLines.size());
        }

        PreprocessedSource result = out.build(List.copyOf(included));
        if (result.text().length() > RetinaLimits.MAX_PREPROCESSED_BYTES) {
            throw new PreprocessException("preprocessed source is "
                + result.text().length() + " bytes, over the "
                + RetinaLimits.MAX_PREPROCESSED_BYTES + " byte limit",
                new SourceLocation(entryPoint, 1, List.of()));
        }
        return result;
    }

    private void emitPrologue(PreprocessedSource.Builder out, Map<String, String> defines) {
        out.appendSynthetic("// --- Retina generated defines ---");
        for (Map.Entry<String, String> define : defines.entrySet()) {
            String value = define.getValue();
            out.appendSynthetic(value == null || value.isEmpty()
                ? "#define " + define.getKey()
                : "#define " + define.getKey() + " " + value);
        }
        out.appendSynthetic("// --- end Retina generated defines ---");
    }

    /** Mutable state threaded through one {@link #process} call. */
    private record Expansion(PreprocessedSource.Builder out, Set<PackPath> included,
                             Deque<PackPath> chain, LineRewriter rewriter, int[] budget) {
    }

    private void expandRange(Expansion state, PackPath file, List<String> lines,
                             int from, int to) throws IOException {
        PreprocessedSource.Builder out = state.out();
        Deque<PackPath> chain = state.chain();
        int[] budget = state.budget();
        List<PackPath> chainSnapshot = List.copyOf(chain);
        for (int i = from; i < to; i++) {
            String line = state.rewriter().rewrite(file, i + 1, lines.get(i));
            Optional<String> includeTarget = parseInclude(line);
            if (includeTarget.isEmpty()) {
                out.append(line, file, i + 1, chainSnapshot);
                continue;
            }
            SourceLocation here = new SourceLocation(file, i + 1, chainSnapshot);

            if (chain.size() >= RetinaLimits.MAX_INCLUDE_DEPTH) {
                throw new PreprocessException("#include nesting exceeds "
                    + RetinaLimits.MAX_INCLUDE_DEPTH + " levels", here);
            }
            if (--budget[0] < 0) {
                throw new PreprocessException("more than "
                    + RetinaLimits.MAX_INCLUDE_EXPANSIONS
                    + " #include expansions in one shader; this usually means an include"
                    + " fan-out bomb", here);
            }

            PackPath target;
            try {
                target = resolveInclude(file, includeTarget.get());
            } catch (RuntimeException e) {
                throw new PreprocessException("#include \"" + includeTarget.get() + "\": "
                    + e.getMessage(), here);
            }

            if (chain.contains(target) || target.equals(file)) {
                throw new PreprocessException("#include cycle: "
                    + describeCycle(chain, file, target), here);
            }

            Optional<String> includedText = source.readText(target);
            if (includedText.isEmpty()) {
                throw new PreprocessException("#include \"" + includeTarget.get()
                    + "\" resolved to " + target.toAbsoluteString()
                    + ", which does not exist in this pack", here);
            }

            state.included().add(target);
            chain.push(file);
            // Deliberately no `#line` directive. Retina maps diagnostics through its own run
            // list, and `#line` would make the compiler report positions in a coordinate
            // space the run list does not describe. GLSL implementations also disagree about
            // whether `#line n` numbers the directive's line or the one after it, so relying
            // on it would make error provenance driver-dependent.
            out.appendSynthetic("// >>> " + target.toAbsoluteString());
            List<String> includedLines = splitLines(includedText.get());
            expandRange(state, target, includedLines, 0, includedLines.size());
            chain.pop();
            out.appendSynthetic("// <<< " + file.toAbsoluteString());
        }
    }

    private static String describeCycle(Deque<PackPath> chain, PackPath current,
                                        PackPath target) {
        List<PackPath> path = new ArrayList<>(chain);
        java.util.Collections.reverse(path);
        path.add(current);
        path.add(target);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                out.append(" -> ");
            }
            out.append(path.get(i).toAbsoluteString());
        }
        return out.toString();
    }

    /**
     * Resolves an include target against the including file.
     *
     * <p>Absolute ({@code /lib/x.glsl}) is relative to {@code shaders/}, not to the pack root
     * and not to the filesystem root.
     */
    PackPath resolveInclude(PackPath includingFile, String raw) {
        String target = raw.trim();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("empty include path");
        }
        if (target.startsWith("/")) {
            return shadersRoot.resolve(target.substring(1));
        }
        return includingFile.parent().resolve(target);
    }

    /**
     * Extracts the path from an {@code #include} line.
     *
     * <p>Returns empty for anything that is not an include directive, including an
     * {@code #include} that appears inside a line comment.
     */
    static Optional<String> parseInclude(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("#")) {
            return Optional.empty();
        }
        String afterHash = trimmed.substring(1).stripLeading();
        if (!afterHash.startsWith("include")) {
            return Optional.empty();
        }
        String rest = afterHash.substring("include".length());
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))
            && rest.charAt(0) != '"' && rest.charAt(0) != '<') {
            // e.g. `#includeme`, which is not a directive.
            return Optional.empty();
        }
        rest = rest.strip();
        if (rest.length() >= 2 && rest.charAt(0) == '"') {
            int end = rest.indexOf('"', 1);
            return end < 0 ? Optional.empty() : Optional.of(rest.substring(1, end));
        }
        if (rest.length() >= 2 && rest.charAt(0) == '<') {
            int end = rest.indexOf('>', 1);
            return end < 0 ? Optional.empty() : Optional.of(rest.substring(1, end));
        }
        // Unquoted form; take the first whitespace-delimited token.
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        int space = 0;
        while (space < rest.length() && !Character.isWhitespace(rest.charAt(space))) {
            space++;
        }
        return Optional.of(rest.substring(0, space));
    }

    /**
     * Finds the {@code #version} line, ignoring comments and blank lines before it.
     *
     * @return the 0-based index, or -1 when there is none
     */
    static int findVersionLine(List<String> lines) {
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String scan = line;
            if (inBlockComment) {
                int end = scan.indexOf("*/");
                if (end < 0) {
                    continue;
                }
                scan = scan.substring(end + 2);
                inBlockComment = false;
            }
            String stripped = stripComments(scan);
            if (scan.contains("/*") && !scan.substring(scan.indexOf("/*")).contains("*/")) {
                inBlockComment = true;
            }
            if (stripped.isBlank()) {
                continue;
            }
            if (stripped.strip().startsWith("#")
                && stripped.strip().substring(1).stripLeading().startsWith("version")) {
                return i;
            }
            // The first meaningful token is not `#version`, so there is no version directive
            // in a legal position.
            return -1;
        }
        return -1;
    }

    private static String stripComments(String line) {
        int lineComment = line.indexOf("//");
        String result = lineComment < 0 ? line : line.substring(0, lineComment);
        int block = result.indexOf("/*");
        if (block >= 0) {
            int end = result.indexOf("*/", block + 2);
            result = end < 0 ? result.substring(0, block)
                : result.substring(0, block) + result.substring(end + 2);
        }
        return result;
    }

    /** Splits on any line terminator, keeping empty trailing lines out of the result. */
    static List<String> splitLines(String text) {
        // `String.lines()` handles \n, \r\n and \r, and drops a single trailing terminator,
        // which is exactly the behaviour needed for line numbering.
        return text.lines().toList();
    }
}
