/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina;

import dev.retina.compat.CompatibilityReport;
import dev.retina.compat.ModConflicts;
import dev.retina.compat.SodiumCompatibility;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The earliest point at which Retina can refuse to run.
 *
 * <p>Everything Retina needs to decide whether it <em>can</em> run — which other renderer
 * mods are installed, which Sodium version is present — is known from the mod list alone,
 * before any class from Minecraft's render stack is loaded. Checking here means an
 * incompatible install produces a readable message on the Fabric error screen instead of a
 * mixin failure part-way through world load, which is the difference between a user who can
 * fix their install and a user filing a crash report.
 *
 * <p>The one thing that cannot be checked here is which Blaze3D backend Minecraft will
 * actually create, because that is decided later from the user's video settings and the
 * driver. That check lives in {@link dev.retina.vk.VulkanBackend} and runs at the first
 * opportunity after device creation.
 */
public final class RetinaPreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("Retina");

    /**
     * Set when a fatal incompatibility was found, so the client entrypoint can stay inert
     * rather than adding a second failure on top of the first.
     */
    private static volatile CompatibilityReport report = CompatibilityReport.notRunYet();

    @Override
    public void onPreLaunch() {
        FabricLoader loader = FabricLoader.getInstance();
        CompatibilityReport result = CompatibilityReport.of(
            ModConflicts.detect(loader),
            SodiumCompatibility.check(loader));
        report = result;

        LOGGER.info("Retina {} starting up", RetinaVersion.full());
        result.notes().forEach(LOGGER::info);
        result.warnings().forEach(LOGGER::warn);

        if (result.isFatal()) {
            // Thrown rather than logged: Fabric turns this into the pre-launch error screen,
            // which is the only place a user reliably reads the message. Continuing would
            // mean two renderers fighting over the same device and a crash with no
            // explanation.
            throw new IllegalStateException(result.fatalMessage());
        }
    }

    /** The compatibility decision made at pre-launch. */
    public static CompatibilityReport report() {
        return report;
    }
}
