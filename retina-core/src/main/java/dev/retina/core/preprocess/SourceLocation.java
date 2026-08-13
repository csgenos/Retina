/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.preprocess;

import dev.retina.core.pack.PackPath;

import java.util.List;

/**
 * A position in original pack source, recovered from a position in preprocessed source.
 *
 * @param file the pack file the line came from
 * @param line the 1-based line within that file
 * @param includeChain the files that led here, outermost first, excluding {@code file}
 */
public record SourceLocation(PackPath file, int line, List<PackPath> includeChain) {

    public SourceLocation {
        includeChain = List.copyOf(includeChain);
    }

    /**
     * A compiler-style rendering of this location including the include chain.
     *
     * <p>The chain matters: a pack author who sees only {@code common.glsl:212} has no way to
     * tell which of eleven programs pulled it in, and the error is frequently caused by the
     * includer's defines rather than by the included file.
     */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append(file.toAbsoluteString()).append(':').append(line);
        if (!includeChain.isEmpty()) {
            out.append("\n  included from ");
            for (int i = includeChain.size() - 1; i >= 0; i--) {
                out.append(includeChain.get(i).toAbsoluteString());
                if (i > 0) {
                    out.append("\n              < ");
                }
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return file.toAbsoluteString() + ":" + line;
    }
}
