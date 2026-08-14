/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.pipeline;

import dev.retina.render.TerrainUniformLayout;
import dev.retina.core.target.RenderTargetDirectives;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** CPU-compiled, validated pack state that is safe to activate transactionally. */
public record PreparedTerrainPack(String name, String contentHash,
                                  Map<PassKind, Program> programs,
                                  EntityProgram entityProgram,
                                  ParticleProgram particleProgram,
                                  ShadowProgram shadowProgram,
                                  List<PostProgram> preparePrograms,
                                  List<PostProgram> deferredPrograms,
                                  List<PostProgram> compositePrograms,
                                  PostProgram finalProgram,
                                  Map<Integer, TargetPlan> targets,
                                  TerrainUniformLayout uniforms,
                                  List<String> diagnostics) {
    public enum PassKind {
        SOLID, CUTOUT, TRANSLUCENT
    }

    public record Program(String sourceName, String vertexSource, String fragmentSource,
                          List<Integer> drawTargets, boolean cull) {
        public Program {
            drawTargets = List.copyOf(drawTargets);
        }
    }

    /** A standard Minecraft entity-format program, supplied by {@code gbuffers_entities}. */
    public record EntityProgram(String sourceName, String vertexSource, String fragmentSource,
                                boolean cull) {
    }

    /** Minecraft's particle quad format, supplied by {@code gbuffers_particles}. */
    public record ParticleProgram(String sourceName, String vertexSource, String fragmentSource,
                                  boolean cull) {
    }

    /** Terrain-only shadow program and its pack-selected map parameters. */
    public record ShadowProgram(String sourceName, String vertexSource, String fragmentSource,
                                List<Integer> drawTargets,
                                Map<Integer, RenderTargetDirectives.TargetSettings> colorTargets,
                                int resolution, float distance, boolean cull) {
        public ShadowProgram {
            drawTargets = List.copyOf(drawTargets);
            colorTargets = Map.copyOf(colorTargets);
        }
    }

    /** One screen-filling post program, in execution order. */
    public record PostProgram(String sourceName, String vertexSource, String fragmentSource,
                              List<Integer> drawTargets, List<String> samplers,
                              Map<String, String> samplerTypes,
                              RenderTargetDirectives.PassScale scale,
                              Map<Integer, Boolean> flips, boolean internal) {
        public PostProgram {
            drawTargets = List.copyOf(drawTargets);
            samplers = List.copyOf(samplers);
            samplerTypes = Map.copyOf(samplerTypes);
            flips = Map.copyOf(flips);
        }
    }

    /** Allocation and per-frame lifecycle for one colortex pair. */
    public record TargetPlan(RenderTargetDirectives.TargetSettings settings,
                             float width, float height, boolean relativeSize) {
        public int pixelWidth(int windowWidth) {
            return relativeSize ? Math.max(1, Math.round(windowWidth * width))
                : Math.max(1, Math.round(width));
        }

        public int pixelHeight(int windowHeight) {
            return relativeSize ? Math.max(1, Math.round(windowHeight * height))
                : Math.max(1, Math.round(height));
        }
    }

    public PreparedTerrainPack {
        programs = Map.copyOf(new EnumMap<>(programs));
        preparePrograms = List.copyOf(preparePrograms);
        deferredPrograms = List.copyOf(deferredPrograms);
        compositePrograms = List.copyOf(compositePrograms);
        targets = Map.copyOf(targets);
        diagnostics = List.copyOf(diagnostics);
    }

    /** Whether terrain must render into Retina-owned attachments before the post chain. */
    public boolean usesOffscreenTargets() {
        return !preparePrograms.isEmpty() || !deferredPrograms.isEmpty()
            || !compositePrograms.isEmpty() || finalProgram != null
            || programs.values().stream().anyMatch(p -> !p.drawTargets().equals(List.of(0)));
    }

    /** Whether this pack supplies a terrain shadow map program. */
    public boolean usesShadows() {
        return shadowProgram != null;
    }
}
