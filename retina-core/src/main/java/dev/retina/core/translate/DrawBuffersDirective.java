/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.translate;

import dev.retina.core.RetinaLimits;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The set of colour attachments a fragment program writes.
 *
 * <p>Packs declare this in a comment rather than in GLSL, in one of two forms:
 *
 * <pre>
 *   /* DRAWBUFFERS:0231 *&#47;      // one digit per target, so only colortex0..9
 *   /* RENDERTARGETS: 0,2,3,11 *&#47; // comma-separated, so colortex10+ is reachable
 * </pre>
 *
 * <p>The position in the list is the fragment output location; the value is the colortex
 * index. {@code DRAWBUFFERS:0231} means output 0 writes colortex0, output 1 writes
 * colortex2, output 2 writes colortex3, and output 3 writes colortex1. Getting this mapping
 * backwards produces a pack that renders, and renders wrong, which is why it is parsed
 * explicitly rather than inferred.
 *
 * <p>When neither directive is present the program writes colortex0 only, matching the
 * format's default.
 */
public record DrawBuffersDirective(List<Integer> targets, Form form, int line) {

    /** Which spelling the pack used. */
    public enum Form {
        /** {@code DRAWBUFFERS:0231}. */
        DRAWBUFFERS,
        /** {@code RENDERTARGETS: 0,2,3,11}. */
        RENDERTARGETS,
        /** Neither was present; colortex0 only. */
        DEFAULT
    }

    public DrawBuffersDirective {
        targets = List.copyOf(targets);
    }

    /** The implicit default: fragment output 0 writes colortex0. */
    public static DrawBuffersDirective defaultTargets() {
        return new DrawBuffersDirective(List.of(0), Form.DEFAULT, -1);
    }

    /** The colortex index written by fragment output {@code location}. */
    public Optional<Integer> targetForLocation(int location) {
        return location >= 0 && location < targets.size()
            ? Optional.of(targets.get(location))
            : Optional.empty();
    }

    /** How many colour attachments the pipeline needs. */
    public int attachmentCount() {
        return targets.size();
    }

    private static final Pattern DRAWBUFFERS =
        Pattern.compile("DRAWBUFFERS\\s*:\\s*([0-9]+)");
    private static final Pattern RENDERTARGETS =
        Pattern.compile("RENDERTARGETS\\s*:\\s*([0-9]+(?:\\s*,\\s*[0-9]+)*)");

    /**
     * Finds the directive in a fragment shader.
     *
     * <p>Only comment tokens are searched. A pack may legitimately contain the string
     * {@code RENDERTARGETS} in code or in a disabled preprocessor branch, and treating that
     * as a directive would silently change which attachments the program writes.
     *
     * <p>When both forms appear, {@code RENDERTARGETS} wins, because a pack that ships both
     * is targeting a loader that understands the newer form and keeps the older one for
     * compatibility with loaders that do not.
     */
    public static Result find(List<GlslLexer.Token> tokens) {
        DrawBuffersDirective drawBuffers = null;
        DrawBuffersDirective renderTargets = null;
        List<String> problems = new ArrayList<>();

        for (GlslLexer.Token token : tokens) {
            if (token.kind() != GlslLexer.Kind.BLOCK_COMMENT
                && token.kind() != GlslLexer.Kind.LINE_COMMENT) {
                continue;
            }
            String text = token.text();

            Matcher rt = RENDERTARGETS.matcher(text);
            if (rt.find() && renderTargets == null) {
                Parsed parsed = parseList(rt.group(1).split("\\s*,\\s*"), token.line());
                problems.addAll(parsed.problems());
                if (parsed.directive() != null) {
                    renderTargets = new DrawBuffersDirective(parsed.directive(),
                        Form.RENDERTARGETS, token.line());
                }
                continue;
            }

            Matcher db = DRAWBUFFERS.matcher(text);
            if (db.find() && drawBuffers == null) {
                String digits = db.group(1);
                String[] parts = new String[digits.length()];
                for (int i = 0; i < digits.length(); i++) {
                    parts[i] = String.valueOf(digits.charAt(i));
                }
                Parsed parsed = parseList(parts, token.line());
                problems.addAll(parsed.problems());
                if (parsed.directive() != null) {
                    drawBuffers = new DrawBuffersDirective(parsed.directive(),
                        Form.DRAWBUFFERS, token.line());
                }
            }
        }

        DrawBuffersDirective chosen = renderTargets != null ? renderTargets : drawBuffers;
        if (chosen == null) {
            return new Result(defaultTargets(), problems);
        }
        return new Result(chosen, problems);
    }

    private record Parsed(List<Integer> directive, List<String> problems) {
    }

    private static Parsed parseList(String[] parts, int line) {
        List<String> problems = new ArrayList<>();
        List<Integer> targets = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (String part : parts) {
            int index;
            try {
                index = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                problems.add("line " + line + ": '" + part + "' is not a render target index");
                return new Parsed(null, problems);
            }
            if (index < 0 || index >= RetinaLimits.MAX_COLOR_TARGETS) {
                problems.add("line " + line + ": colortex" + index
                    + " is outside the supported range colortex0.."
                    + (RetinaLimits.MAX_COLOR_TARGETS - 1));
                return new Parsed(null, problems);
            }
            if (!seen.add(index)) {
                // Writing the same attachment from two outputs is undefined; refusing is
                // better than picking one arbitrarily.
                problems.add("line " + line + ": colortex" + index
                    + " is listed more than once, which would bind one image to two"
                    + " fragment outputs");
                return new Parsed(null, problems);
            }
            targets.add(index);
        }
        if (targets.isEmpty()) {
            problems.add("line " + line + ": the directive lists no render targets");
            return new Parsed(null, problems);
        }
        return new Parsed(targets, problems);
    }

    /** The directive plus any problems found while parsing it. */
    public record Result(DrawBuffersDirective directive, List<String> problems) {
        public Result {
            problems = List.copyOf(problems);
        }
    }
}
