/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * See LICENSE and NOTICE in the source distribution.
 */
package dev.retina.core;

/**
 * Hard resource limits applied to untrusted shader-pack input.
 *
 * <p>Shader packs are arbitrary local files that the user may have obtained from anywhere.
 * Every limit here exists because the corresponding unbounded operation is reachable from
 * pack content: include bombs, quadratic expansion, absurd texture allocations, or integer
 * overflow in size arithmetic. Limits are deliberately generous relative to real packs and
 * deliberately finite.
 *
 * <p>Values are constants rather than configuration because raising them is a security
 * decision, not a preference.
 */
public final class RetinaLimits {
    private RetinaLimits() {
    }

    /** Maximum nesting depth of {@code #include} directives. */
    public static final int MAX_INCLUDE_DEPTH = 64;

    /** Maximum number of {@code #include} expansions performed for one translation unit. */
    public static final int MAX_INCLUDE_EXPANSIONS = 4096;

    /** Maximum size in bytes of a single source file read from a pack. */
    public static final int MAX_SOURCE_FILE_BYTES = 8 * 1024 * 1024;

    /** Maximum total size in bytes of one fully preprocessed translation unit. */
    public static final int MAX_PREPROCESSED_BYTES = 32 * 1024 * 1024;

    /** Maximum number of entries read from a ZIP shader pack. */
    public static final int MAX_ZIP_ENTRIES = 65_536;

    /** Maximum uncompressed bytes read from a ZIP shader pack in total. */
    public static final long MAX_ZIP_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;

    /** Maximum compression ratio tolerated for a single ZIP entry before it is refused. */
    public static final int MAX_ZIP_COMPRESSION_RATIO = 200;

    /** Maximum path length, in characters, of an entry inside a pack. */
    public static final int MAX_PACK_PATH_LENGTH = 512;

    /** Maximum width or height of a pack-declared texture or render target. */
    public static final int MAX_TEXTURE_DIMENSION = 16_384;

    /** Maximum depth (layers) of a pack-declared 3D or array texture. */
    public static final int MAX_TEXTURE_DEPTH = 2048;

    /** Maximum mip levels honoured for a pack-declared texture. */
    public static final int MAX_MIP_LEVELS = 16;

    /** Maximum number of {@code colortex} render targets. */
    public static final int MAX_COLOR_TARGETS = 16;

    /** Maximum number of {@code shadowcolor} render targets. */
    public static final int MAX_SHADOW_COLOR_TARGETS = 8;

    /** Maximum number of custom images a pack may declare. */
    public static final int MAX_CUSTOM_IMAGES = 64;

    /** Maximum number of options a pack may declare. */
    public static final int MAX_OPTIONS = 4096;

    /** Maximum recursion depth of a pack option/conditional expression. */
    public static final int MAX_EXPRESSION_DEPTH = 32;

    /** Maximum number of tokens in a single pack expression. */
    public static final int MAX_EXPRESSION_TOKENS = 1024;

    /** Maximum number of composite/deferred passes in one chain. */
    public static final int MAX_COMPOSITE_PASSES = 100;

    /**
     * Multiplies two non-negative sizes, throwing rather than overflowing.
     *
     * @throws PackLimitException if either operand is negative or the product overflows
     */
    public static long mulExact(String what, long a, long b) {
        if (a < 0 || b < 0) {
            throw new PackLimitException(what + ": negative size (" + a + " x " + b + ")");
        }
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw new PackLimitException(what + ": size overflow (" + a + " x " + b + ")");
        }
    }

    /** Throws unless {@code value} is in {@code [1, max]}. */
    public static int requireInRange(String what, int value, int max) {
        if (value < 1 || value > max) {
            throw new PackLimitException(what + ": " + value + " is outside 1.." + max);
        }
        return value;
    }

    /** Thrown when untrusted pack input exceeds a hard limit. */
    public static final class PackLimitException extends RuntimeException {
        public PackLimitException(String message) {
            super(message);
        }
    }
}
