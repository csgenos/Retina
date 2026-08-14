/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Retina's persisted settings, in {@code config/retina/retina.json}.
 *
 * <p>Renderer profiles change engine overhead only. None of them alters what a shader pack
 * asked for: a pack's resolution, sample count, effect list and target formats come from the
 * pack's own options and are never reduced behind the user's back. That restriction is what
 * makes the profiles safe to switch between without the user wondering whether their pack
 * still looks right.
 */
public record RetinaConfig(String selectedPack, RendererProfile profile, boolean debugOverlay,
                           boolean validationLayers, String adapterOverride,
                           long vramBudgetMegabytes, boolean parallelCompilation,
                           int compileThreads, Path file) {

    /** JSON shape kept separate so the runtime-only config path is never persisted. */
    private record PersistedConfig(String selectedPack, RendererProfile profile,
                                   boolean debugOverlay, boolean validationLayers,
                                   String adapterOverride, long vramBudgetMegabytes,
                                   boolean parallelCompilation, int compileThreads) {
        private RetinaConfig bind(Path path) {
            return new RetinaConfig(selectedPack, profile, debugOverlay, validationLayers,
                adapterOverride, vramBudgetMegabytes, parallelCompilation, compileThreads,
                path);
        }
    }

    /** Renderer-level profiles. They change overhead, never pack semantics. */
    public enum RendererProfile {
        /**
         * Conservative feature use and the widest driver tolerance. Disables optional Vulkan
         * paths even where the device advertises them.
         */
        COMPATIBILITY,
        /** The default: safe optimisations that are exercised on all three vendors. */
        BALANCED,
        /**
         * More aggressive batching and caching, and reduced diagnostic overhead. Does not
         * change a pack's requested visual semantics.
         */
        PERFORMANCE,
        /** Validation layers, debug labels, pass and resource inspection. */
        DEBUG
    }

    /** The name used in the UI when no pack is selected. */
    public static final String SHADERS_OFF = "";

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /** The defaults a fresh install starts with. */
    public static RetinaConfig defaults() {
        return new RetinaConfig(SHADERS_OFF, RendererProfile.BALANCED, false, false, "",
            0L, true, 0, null);
    }

    /** Whether a shader pack is selected. */
    public boolean shadersEnabled() {
        return selectedPack != null && !selectedPack.isEmpty();
    }

    /**
     * The compile worker count, resolving 0 to a bounded default.
     *
     * <p>Bounded below the core count on purpose: shader compilation competes with Sodium's
     * chunk build threads, and saturating every core makes pack loading faster while making
     * the world around the player load visibly slower.
     */
    public int resolvedCompileThreads() {
        if (compileThreads > 0) {
            return Math.min(compileThreads, 32);
        }
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    }

    /** A copy with a different selected pack. */
    public RetinaConfig withSelectedPack(String pack) {
        return new RetinaConfig(pack, profile, debugOverlay, validationLayers, adapterOverride,
            vramBudgetMegabytes, parallelCompilation, compileThreads, file);
    }

    /** A copy with a different renderer profile. */
    public RetinaConfig withProfile(RendererProfile next) {
        return new RetinaConfig(selectedPack, next, debugOverlay, validationLayers,
            adapterOverride, vramBudgetMegabytes, parallelCompilation, compileThreads, file);
    }

    /** A copy with the in-game pass timing overlay enabled or disabled. */
    public RetinaConfig withDebugOverlay(boolean next) {
        return new RetinaConfig(selectedPack, profile, next, validationLayers,
            adapterOverride, vramBudgetMegabytes, parallelCompilation, compileThreads, file);
    }

    /** A copy with asynchronous shader compilation enabled or disabled. */
    public RetinaConfig withParallelCompilation(boolean next) {
        return new RetinaConfig(selectedPack, profile, debugOverlay, validationLayers,
            adapterOverride, vramBudgetMegabytes, next, compileThreads, file);
    }

    /** A copy bound to {@code path} for saving. */
    public RetinaConfig withFile(Path path) {
        return new RetinaConfig(selectedPack, profile, debugOverlay, validationLayers,
            adapterOverride, vramBudgetMegabytes, parallelCompilation, compileThreads, path);
    }

    /** Reads the config, returning defaults when the file does not exist. */
    public static RetinaConfig load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return defaults().withFile(path);
        }
        String json = Files.readString(path, StandardCharsets.UTF_8);
        try {
            PersistedConfig parsed = GSON.fromJson(json, PersistedConfig.class);
            return parsed == null ? defaults().withFile(path) : parsed.bind(path).sanitised();
        } catch (JsonSyntaxException e) {
            throw new IOException("config/retina/retina.json is not valid JSON", e);
        }
    }

    /** Replaces any out-of-range value with its default. */
    private RetinaConfig sanitised() {
        return new RetinaConfig(
            selectedPack == null ? SHADERS_OFF : selectedPack,
            profile == null ? RendererProfile.BALANCED : profile,
            debugOverlay,
            validationLayers,
            adapterOverride == null ? "" : adapterOverride,
            Math.max(0L, vramBudgetMegabytes),
            parallelCompilation,
            Math.max(0, compileThreads),
            file);
    }

    /**
     * Writes the config atomically.
     *
     * <p>Written to a temporary file and moved into place so that a crash mid-write leaves
     * the previous config intact rather than a truncated file that fails to parse on the next
     * launch.
     */
    public void save() throws IOException {
        if (file == null) {
            throw new IOException("this config is not bound to a file");
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        PersistedConfig persisted = new PersistedConfig(selectedPack, profile, debugOverlay,
            validationLayers, adapterOverride, vramBudgetMegabytes, parallelCompilation,
            compileThreads);
        Files.writeString(temporary, GSON.toJson(persisted), StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
