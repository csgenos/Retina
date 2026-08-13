/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina;

import dev.retina.config.RetinaConfig;
import dev.retina.pipeline.PackManager;
import dev.retina.render.ShaderRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Client entrypoint.
 *
 * <p>Deliberately does almost nothing. Everything expensive — scanning {@code shaderpacks/},
 * compiling a pipeline, touching the GPU — happens lazily, because this runs before the
 * window exists and doing real work here delays the first frame with no benefit.
 *
 * <p>Retina starts with no pack selected. That is not a fallback; it is the documented
 * default, and it is what makes "Retina installed" visually identical to "Retina not
 * installed" until the user chooses otherwise.
 */
public final class RetinaClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Retina");

    private static RetinaConfig config = RetinaConfig.defaults();
    private static PackManager packManager;

    @Override
    public void onInitializeClient() {
        if (RetinaPreLaunch.report().isFatal()) {
            // Pre-launch already threw, so reaching here means Fabric is running in a mode
            // that ignored it. Staying inert is strictly better than half-initialising.
            LOGGER.error("Retina is inert: startup compatibility checks failed");
            return;
        }

        Path configDirectory = FabricLoader.getInstance().getConfigDir().resolve("retina");
        Path shaderpacks = FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
        Path cache = FabricLoader.getInstance().getGameDir().resolve("cache").resolve("retina");

        try {
            config = RetinaConfig.load(configDirectory.resolve("retina.json"));
        } catch (IOException e) {
            // A corrupt config must not stop the game from starting; defaults are always
            // valid, and the file is rewritten on the next explicit save.
            LOGGER.warn("Could not read config/retina/retina.json ({}); using defaults",
                e.getMessage());
            config = RetinaConfig.defaults();
        }

        packManager = new PackManager(shaderpacks, configDirectory, cache);
        try {
            packManager.ensureDirectories();
        } catch (IOException e) {
            LOGGER.warn("Could not create the shaderpacks or cache directory: {}",
                e.getMessage());
        }

        ShaderRuntime.get().initialize(packManager, config);

        LOGGER.info("Retina {} initialised; {}", RetinaVersion.full(),
            config.shadersEnabled()
                ? "compiling selected pack " + config.selectedPack()
                : "shaders are off until a pack is selected");
    }

    /** The active configuration. */
    public static RetinaConfig config() {
        return config;
    }

    /** Replaces the configuration and persists it. */
    public static void setConfig(RetinaConfig next) {
        config = next;
        try {
            next.save();
        } catch (IOException e) {
            LOGGER.warn("Could not save config/retina/retina.json: {}", e.getMessage());
        }
        if (packManager != null) {
            ShaderRuntime.get().request(next);
        }
    }

    /** The pack manager, or null when Retina is inert. */
    public static PackManager packManager() {
        return packManager;
    }

    /** Rebuilds the currently selected pack after its option values changed. */
    public static void reloadShaders() {
        if (packManager != null && config.shadersEnabled()) {
            ShaderRuntime.get().request(config);
        }
    }
}
