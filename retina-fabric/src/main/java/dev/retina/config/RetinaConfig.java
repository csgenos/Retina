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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Retina's persisted settings, in {@code config/retina/retina.json}.
 *
 * <p>Every setting here is one the renderer actually reads. Settings that describe intentions
 * rather than behaviour — a VRAM budget nothing enforces, an adapter override that cannot apply
 * because Retina attaches to the device Minecraft already chose — are deliberately absent: a
 * control that does nothing is worse than a missing one, because it makes a user believe they
 * have tried something.
 *
 * <p>Renderer profiles change engine overhead only. None of them alters what a shader pack
 * asked for: a pack's resolution, sample count, effect list and target formats come from the
 * pack's own options and are never reduced behind the user's back. That restriction is what
 * makes the profiles safe to switch between without the user wondering whether their pack still
 * looks right.
 */
public record RetinaConfig(String selectedPack, RendererProfile profile, Path file) {

    /** JSON shape kept separate so the runtime-only config path is never persisted. */
    private record PersistedConfig(String selectedPack, RendererProfile profile) {
        private RetinaConfig bind(Path path) {
            return new RetinaConfig(selectedPack, profile, path);
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
        return new RetinaConfig(SHADERS_OFF, RendererProfile.BALANCED, null);
    }

    /** Whether a shader pack is selected. */
    public boolean shadersEnabled() {
        return selectedPack != null && !selectedPack.isEmpty();
    }

    /** A copy with a different selected pack. */
    public RetinaConfig withSelectedPack(String pack) {
        return new RetinaConfig(pack, profile, file);
    }

    /** A copy with a different renderer profile. */
    public RetinaConfig withProfile(RendererProfile next) {
        return new RetinaConfig(selectedPack, next, file);
    }

    /** A copy bound to {@code path} for saving. */
    public RetinaConfig withFile(Path path) {
        return new RetinaConfig(selectedPack, profile, path);
    }

    /**
     * Reads the config, returning defaults when the file does not exist.
     *
     * <p>Unknown keys are ignored, so a file written by an older build that carried settings
     * since removed still loads.
     */
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

    /** Replaces any missing value with its default. */
    private RetinaConfig sanitised() {
        return new RetinaConfig(
            selectedPack == null ? SHADERS_OFF : selectedPack,
            profile == null ? RendererProfile.BALANCED : profile,
            file);
    }

    /**
     * Writes the config atomically.
     *
     * <p>Written to a temporary file and moved into place, so a crash mid-write leaves the
     * previous config intact rather than a truncated file that fails to parse on the next
     * launch. The move asks for {@link StandardCopyOption#ATOMIC_MOVE} and falls back to a
     * plain replace only where the filesystem cannot provide it — without asking, the "atomic"
     * in this contract is a claim rather than a guarantee.
     */
    public void save() throws IOException {
        if (file == null) {
            throw new IOException("this config is not bound to a file");
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        PersistedConfig persisted = new PersistedConfig(selectedPack, profile);
        Files.writeString(temporary, GSON.toJson(persisted), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
