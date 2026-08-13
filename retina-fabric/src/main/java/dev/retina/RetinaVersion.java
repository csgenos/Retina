/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Retina's own version, read from the mod metadata.
 *
 * <p>Read rather than hard-coded because it is a cache-key input: a hard-coded constant that
 * drifts from the jar's real version would let a new build reuse the previous build's
 * compiled shaders.
 */
public final class RetinaVersion {
    private static final String VERSION = FabricLoader.getInstance()
        .getModContainer("retina")
        .map(container -> container.getMetadata().getVersion().getFriendlyString())
        .orElse("0.0.0+unknown");

    private RetinaVersion() {
    }

    /** The full version string, e.g. {@code 0.1.0+mc26.2}. */
    public static String full() {
        return VERSION;
    }
}
