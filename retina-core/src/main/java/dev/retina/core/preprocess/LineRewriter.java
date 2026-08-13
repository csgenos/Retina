/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.preprocess;

import dev.retina.core.pack.PackPath;

/**
 * Rewrites individual source lines as they are pulled into a translation unit.
 *
 * <p>Applied by {@link ShaderPreprocessor} before include detection, so a rewriter could in
 * principle change an include target. Implementations must not do that — the include graph
 * has to stay stable across option changes or the cache key would no longer describe the
 * source that was compiled. The one production implementation,
 * {@link dev.retina.core.option.OptionApplier}, only ever edits option declaration lines.
 */
@FunctionalInterface
public interface LineRewriter {

    /** Leaves every line untouched. */
    LineRewriter IDENTITY = (file, line, text) -> text;

    /**
     * @param file the pack file the line came from
     * @param line the 1-based line number within that file
     * @param text the original line, without its terminator
     * @return the replacement line
     */
    String rewrite(PackPath file, int line, String text);
}
