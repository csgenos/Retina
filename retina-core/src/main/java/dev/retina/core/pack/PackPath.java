/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.pack;

import dev.retina.core.RetinaLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A normalised, traversal-safe path inside a shader pack.
 *
 * <p>Every path is rooted at the pack root (the directory that contains {@code shaders/}).
 * A {@code PackPath} can never escape that root: {@code ..} segments are resolved during
 * construction and an attempt to walk above the root is an error rather than a clamp, so a
 * malicious {@code #include "../../../../etc/passwd"} fails loudly instead of silently
 * reading something else.
 *
 * <p>Separators are always {@code /} regardless of host platform, matching both ZIP entry
 * names and the shader-pack source convention.
 */
public final class PackPath implements Comparable<PackPath> {
    private static final PackPath ROOT = new PackPath(List.of());

    private final List<String> segments;
    private final String joined;

    private PackPath(List<String> segments) {
        this.segments = List.copyOf(segments);
        this.joined = String.join("/", this.segments);
    }

    /** The pack root itself. */
    public static PackPath root() {
        return ROOT;
    }

    /**
     * Parses an absolute path relative to the pack root.
     *
     * @throws PackPathException if the path escapes the root or violates a limit
     */
    public static PackPath of(String raw) {
        return ROOT.resolve(raw);
    }

    /**
     * Resolves {@code raw} against this path.
     *
     * <p>A leading {@code /} makes {@code raw} absolute from the pack root, which is the
     * convention shader packs use for {@code #include "/lib/common.glsl"}. Anything else is
     * relative to this path's <em>directory</em> semantics: callers resolve against the
     * including file's parent, not the file itself.
     *
     * @throws PackPathException if the result would escape the pack root
     */
    public PackPath resolve(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.length() > RetinaLimits.MAX_PACK_PATH_LENGTH) {
            throw new PackPathException("path exceeds " + RetinaLimits.MAX_PACK_PATH_LENGTH
                + " characters: " + raw.substring(0, 64) + "...");
        }
        if (raw.indexOf('\0') >= 0) {
            throw new PackPathException("path contains NUL: " + raw);
        }
        // Windows-style separators appear in hand-written packs; treat them as separators
        // rather than as literal characters so traversal checks cannot be bypassed.
        String normalised = raw.replace('\\', '/');
        boolean absolute = normalised.startsWith("/");

        List<String> out = absolute ? new ArrayList<>() : new ArrayList<>(this.segments);
        for (String segment : normalised.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (out.isEmpty()) {
                    throw new PackPathException("path escapes the pack root: " + raw);
                }
                out.removeLast();
                continue;
            }
            if (segment.equals("~") || segment.contains(":")) {
                // A drive letter or home reference can only come from a path that was never
                // meant to be pack-relative.
                throw new PackPathException("path is not pack-relative: " + raw);
            }
            out.add(segment);
        }
        return new PackPath(out);
    }

    /** The directory containing this path, or the root for a top-level entry. */
    public PackPath parent() {
        if (segments.isEmpty()) {
            return ROOT;
        }
        return new PackPath(segments.subList(0, segments.size() - 1));
    }

    /** The final segment, or {@code ""} for the root. */
    public String fileName() {
        return segments.isEmpty() ? "" : segments.getLast();
    }

    /** The lowercase extension without the dot, or {@code ""} when there is none. */
    public String extension() {
        String name = fileName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether this path is the root. */
    public boolean isRoot() {
        return segments.isEmpty();
    }

    /** The immutable segment list. */
    public List<String> segments() {
        return segments;
    }

    /** Whether {@code other} is this path or lies underneath it. */
    public boolean contains(PackPath other) {
        if (other.segments.size() < segments.size()) {
            return false;
        }
        return other.segments.subList(0, segments.size()).equals(segments);
    }

    /** The path as it appears inside a ZIP archive, with no leading slash. */
    public String toEntryName() {
        return joined;
    }

    /** The path as shader packs write it, with a leading slash. */
    public String toAbsoluteString() {
        return "/" + joined;
    }

    @Override
    public String toString() {
        return toAbsoluteString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PackPath p && p.joined.equals(joined);
    }

    @Override
    public int hashCode() {
        return joined.hashCode();
    }

    @Override
    public int compareTo(PackPath o) {
        return joined.compareTo(o.joined);
    }

    /** Thrown when a pack-supplied path is unsafe or malformed. */
    public static final class PackPathException extends RuntimeException {
        public PackPathException(String message) {
            super(message);
        }
    }
}
