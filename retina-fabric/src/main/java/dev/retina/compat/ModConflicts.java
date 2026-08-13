/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detects other mods that own the same window, device or render pipeline as Retina.
 *
 * <p>The {@code breaks} block in {@code fabric.mod.json} already stops most of these, but it
 * only produces Fabric's generic dependency screen. Detecting them here as well lets Retina
 * say <em>why</em> the combination cannot work and what to remove, which is the difference
 * between a user who fixes their install in one step and one who removes mods at random.
 *
 * <p>Nothing here modifies, disables or deletes another mod's files. Retina reports and
 * refuses to start; what to uninstall is the user's decision.
 */
public final class ModConflicts {
    private ModConflicts() {
    }

    /** A conflicting mod and why it conflicts. */
    public record Conflict(String modId, String displayName, String version, String reason,
                           Severity severity) {

        /** How badly the conflict breaks things. */
        public enum Severity {
            /** Retina cannot run at all alongside this mod. */
            FATAL,
            /** Retina can run, but some behaviour is untested or degraded. */
            WARNING
        }
    }

    /**
     * Mods that take ownership of the render pipeline Retina needs.
     *
     * <p>Each entry states the specific resource that is contested, not just "incompatible".
     */
    private static final Map<String, String> FATAL_CONFLICTS = Map.of(
        "iris", "Iris is an OpenGL shader-pack loader and installs its own render pipeline"
            + " over Minecraft's OpenGL backend. Retina renders through Minecraft's Vulkan"
            + " backend. Only one shader loader can own the pipeline; remove one of them.",
        "optifabric", "OptiFabric patches Minecraft's renderer at class-load time and is"
            + " incompatible with both Sodium and Retina.",
        "canvas", "Canvas replaces Minecraft's terrain renderer and its shader system, which"
            + " Retina and Sodium also do.",
        "vulkanmod", "VulkanMod creates and owns its own Vulkan instance, device and"
            + " swapchain. Two Vulkan renderers cannot present to the same window.",
        "sulkan", "Sulkan is an earlier experimental Vulkan renderer that owns the same"
            + " device and swapchain as Retina.",
        "embeddium", "Embeddium is a fork of Sodium and conflicts with the Sodium version"
            + " Retina integrates with."
    );

    /** Mods that change rendering enough that Retina's behaviour with them is untested. */
    private static final Map<String, String> WARNINGS = Map.of(
        "distanthorizons", "Distant Horizons support is optional and version-gated. Retina"
            + " will run, but its dh_* shader programs are only used when a compatible"
            + " Distant Horizons API is detected.",
        "immediatelyfast", "ImmediatelyFast batches immediate-mode draws. Retina has not been"
            + " tested against it; report visual differences rather than assuming they are"
            + " pack bugs.",
        "indium", "Sodium 0.9 provides the Fabric Rendering API itself, so Indium is"
            + " redundant and may conflict with Sodium's own implementation."
    );

    /** Scans the mod list. */
    public static List<Conflict> detect(FabricLoader loader) {
        List<Conflict> conflicts = new ArrayList<>();
        FATAL_CONFLICTS.forEach((modId, reason) ->
            find(loader, modId).ifPresent(container -> conflicts.add(new Conflict(
                modId, displayName(container), version(container), reason,
                Conflict.Severity.FATAL))));
        WARNINGS.forEach((modId, reason) ->
            find(loader, modId).ifPresent(container -> conflicts.add(new Conflict(
                modId, displayName(container), version(container), reason,
                Conflict.Severity.WARNING))));
        // Sort so the message is stable between launches; fatal first.
        conflicts.sort((a, b) -> {
            int bySeverity = a.severity().compareTo(b.severity());
            return bySeverity != 0 ? bySeverity : a.modId().compareTo(b.modId());
        });
        return List.copyOf(conflicts);
    }

    private static Optional<ModContainer> find(FabricLoader loader, String modId) {
        return loader.getModContainer(modId);
    }

    private static String displayName(ModContainer container) {
        return container.getMetadata().getName();
    }

    private static String version(ModContainer container) {
        return container.getMetadata().getVersion().getFriendlyString();
    }
}
