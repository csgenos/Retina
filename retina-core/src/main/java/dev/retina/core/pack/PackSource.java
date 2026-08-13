/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.pack;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the contents of one shader pack.
 *
 * <p>A pack is either an ordinary directory or a ZIP archive under {@code shaderpacks/}.
 * Both are exposed through this interface so that everything above it — discovery,
 * preprocessing, option scanning — is storage agnostic and testable against an in-memory
 * implementation.
 *
 * <p>Implementations must be safe against hostile input: no entry may resolve outside the
 * pack root, and no read may exceed the limits in {@link dev.retina.core.RetinaLimits}.
 * Implementations are <em>not</em> required to be thread-safe for {@link #close()}, but
 * concurrent reads must be safe because shader compilation is parallel.
 */
public interface PackSource extends Closeable {

    /** Human-readable pack name as shown in the UI, e.g. {@code "BSL_v8.2.zip"}. */
    String name();

    /** Whether {@code path} exists and is a readable file. */
    boolean exists(PackPath path);

    /**
     * Reads the entire file at {@code path}.
     *
     * @return the bytes, or empty when the file does not exist
     * @throws IOException on an I/O error or when a limit is exceeded
     */
    Optional<byte[]> read(PackPath path) throws IOException;

    /**
     * Reads the file at {@code path} as UTF-8 text with a stripped byte-order mark.
     *
     * <p>Shader sources in the wild are frequently saved with a BOM by Windows editors; a
     * leading {@code U+FEFF} before {@code #version} is a compile error in every GLSL
     * compiler, so it is removed here rather than surfacing as a baffling diagnostic.
     */
    default Optional<String> readText(PackPath path) throws IOException {
        return read(path).map(bytes -> {
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.isEmpty() || text.charAt(0) != '﻿' ? text : text.substring(1);
        });
    }

    /**
     * Lists the immediate children of a directory.
     *
     * @return child paths, sorted, or an empty list when the directory does not exist
     */
    List<PackPath> list(PackPath directory) throws IOException;

    /** Whether {@code path} exists and is a directory. */
    boolean isDirectory(PackPath path);

    /**
     * A stable content hash of the whole pack.
     *
     * <p>Used as an input to compile and pipeline cache keys, so it must change whenever any
     * byte a shader could observe changes.
     */
    String contentHash() throws IOException;

    @Override
    void close() throws IOException;
}
