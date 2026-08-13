/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * A content-addressed key for a compiled shader or a pipeline cache.
 *
 * <p>Cache keys are a correctness feature, not an optimisation. A key that omits an input
 * returns a stale module after that input changes, and the symptom is a pack that renders
 * with yesterday's settings or, after a driver update, a pipeline the driver can no longer
 * execute. Every input that can change the compiled result is therefore folded in
 * explicitly, and the builder refuses to produce a key until each required category has been
 * supplied.
 *
 * <p>Inputs are sorted by name before hashing so that two builds differing only in the order
 * a caller added them produce the same key.
 */
public final class CacheKey {

    /** Categories every key must carry. Omitting one is a programming error, not a warning. */
    public enum Required {
        /** Retina's own version, so a renderer change invalidates everything. */
        RENDERER_VERSION,
        /** Pack identity and content hash. */
        PACK_CONTENT,
        /** The user's chosen option values. */
        OPTIONS,
        /** GPU vendor, device and driver version. */
        DEVICE,
        /** Vulkan API version and the capability choices Retina made. */
        CAPABILITIES
    }

    private final String hex;
    private final String description;

    private CacheKey(String hex, String description) {
        this.hex = hex;
        this.description = description;
    }

    /** The key as lowercase hex. */
    public String hex() {
        return hex;
    }

    /** The first 16 hex characters, for log lines and file names. */
    public String shortHex() {
        return hex.substring(0, 16);
    }

    /** A human-readable dump of the inputs, for the debug bundle. */
    public String description() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CacheKey other && other.hex.equals(hex);
    }

    @Override
    public int hashCode() {
        return hex.hashCode();
    }

    @Override
    public String toString() {
        return shortHex();
    }

    /** Accumulates inputs and produces a key. */
    public static final class Builder {
        private final TreeMap<String, String> inputs = new TreeMap<>();
        private final List<Required> satisfied = new ArrayList<>();

        /** Adds an input that contributes to the key. */
        public Builder put(String name, String value) {
            inputs.put(name, value == null ? "<null>" : value);
            return this;
        }

        /** Adds an input and records that it satisfies a required category. */
        public Builder put(Required category, String name, String value) {
            if (!satisfied.contains(category)) {
                satisfied.add(category);
            }
            return put(name, value);
        }

        /** Adds a numeric input. */
        public Builder put(String name, long value) {
            return put(name, Long.toString(value));
        }

        /** Adds a boolean input. */
        public Builder put(String name, boolean value) {
            return put(name, Boolean.toString(value));
        }

        /**
         * Produces the key.
         *
         * @throws IllegalStateException when a required category was never supplied
         */
        public CacheKey build() {
            for (Required category : Required.values()) {
                if (!satisfied.contains(category)) {
                    throw new IllegalStateException("cache key is missing the " + category
                        + " input; a key without it would survive a change that alters the"
                        + " compiled result");
                }
            }
            MessageDigest digest = sha256();
            StringBuilder description = new StringBuilder();
            inputs.forEach((name, value) -> {
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                description.append(name).append('=')
                    .append(value.length() > 120 ? value.substring(0, 117) + "..." : value)
                    .append('\n');
            });
            return new CacheKey(HexFormat.of().formatHex(digest.digest()),
                description.toString());
        }

        /**
         * Produces a key without enforcing the required categories.
         *
         * <p>Only for keys that are scoped inside an already-keyed cache, such as one entry
         * per shader stage within a pipeline whose key already covers the device.
         */
        public CacheKey buildDerived() {
            MessageDigest digest = sha256();
            StringBuilder description = new StringBuilder();
            inputs.forEach((name, value) -> {
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                description.append(name).append('=').append(value).append('\n');
            });
            return new CacheKey(HexFormat.of().formatHex(digest.digest()),
                description.toString());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    /** Starts a new key. */
    public static Builder builder() {
        return new Builder();
    }
}
