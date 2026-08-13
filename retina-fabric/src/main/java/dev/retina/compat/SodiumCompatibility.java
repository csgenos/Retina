/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.List;
import java.util.Optional;

/**
 * Gates Retina on the exact Sodium version it was built against.
 *
 * <p>Retina reaches into Sodium's terrain pipeline through mixins. Sodium's internals are not
 * a public API and change between minor versions; a mixin that silently fails to apply
 * produces a client that starts, loads a world, and then renders nothing or crashes deep in
 * a render pass. Refusing to start on an unrecognised version, with both version numbers in
 * the message, is the only outcome a user can act on.
 *
 * <p>The check is a fail-closed allowlist rather than a range: "0.9.x" is asserted from
 * having been tested, not from a guess about what a future 0.9.5 will contain.
 */
public final class SodiumCompatibility {
    private SodiumCompatibility() {
    }

    /** The Sodium version Retina is built and tested against. */
    public static final String SUPPORTED_VERSION = "0.9.1+mc26.2";

    /**
     * Versions accepted at runtime.
     *
     * <p>Only the exact tested build is listed. Adding a version here is a claim that the
     * integration was run against it, so the list is data rather than a pattern match.
     */
    private static final List<String> ACCEPTED_VERSIONS = List.of(
        "0.9.1+mc26.2"
    );

    /** The outcome of the check. */
    public sealed interface Result {
        /** Sodium is present at a tested version. */
        record Ok(String version) implements Result {
        }

        /** Sodium is present but at an untested version. */
        record UnsupportedVersion(String installed, String supported) implements Result {
            /** The message shown to the user. */
            public String message() {
                return "Retina " + dev.retina.RetinaVersion.full()
                    + " integrates with Sodium's terrain pipeline through version-specific"
                    + " hooks, and supports Sodium " + supported + ".\n"
                    + "The installed version is Sodium " + installed + ".\n\n"
                    + "Install Sodium " + supported + ", or a Retina build that lists the"
                    + " installed Sodium version as supported. Retina will not start against"
                    + " an untested Sodium version because its hooks would attach to the"
                    + " wrong code and fail during world rendering rather than now.";
            }
        }

        /** Sodium is not installed. */
        record Missing(String supported) implements Result {
            /** The message shown to the user. */
            public String message() {
                return "Retina requires Sodium " + supported + ", which is not installed."
                    + " Retina is a shader loader built on Sodium's terrain pipeline, not a"
                    + " replacement for it.";
            }
        }
    }

    /** Checks the installed Sodium against the supported version. */
    public static Result check(FabricLoader loader) {
        Optional<ModContainer> sodium = loader.getModContainer("sodium");
        if (sodium.isEmpty()) {
            return new Result.Missing(SUPPORTED_VERSION);
        }
        String installed = sodium.get().getMetadata().getVersion().getFriendlyString();
        if (ACCEPTED_VERSIONS.contains(installed)) {
            return new Result.Ok(installed);
        }
        return new Result.UnsupportedVersion(installed, SUPPORTED_VERSION);
    }
}
