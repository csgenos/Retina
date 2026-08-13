/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A declared, validated frame: passes, the resources they touch, and the barriers between.
 *
 * <p>The graph is built once when a pack is loaded or reloaded and then executed unchanged
 * every frame. Nothing here runs per frame — that is the point. Deriving barriers, image
 * layouts and resource lifetimes at load time is what keeps the steady frame loop free of
 * per-frame allocation and analysis.
 *
 * <p>The graph is also where the two hardest correctness properties of a shader pipeline are
 * enforced, both of which are undefined behaviour in Vulkan rather than merely slow:
 *
 * <ul>
 *   <li>a pass may not sample an image it is simultaneously writing as an attachment. The
 *       shader-pack format's ping-pong ("flip") rules exist precisely to avoid this, and
 *       modelling both halves of the pair as distinct resources makes a violation a build
 *       error instead of a driver-specific artefact;
 *   <li>every read must be preceded by a write in the same frame, or be explicitly declared
 *       as history from the previous frame. Reading an image that nothing has written yields
 *       whatever the allocator handed back, which on some drivers is the previous frame and
 *       on others is garbage.
 * </ul>
 */
public final class RenderGraph {

    /** How a pass uses a resource. */
    public enum Access {
        /** Sampled in a shader. */
        SAMPLE,
        /** Written as a colour attachment. */
        COLOR_ATTACHMENT,
        /** Written as the depth attachment. */
        DEPTH_ATTACHMENT,
        /** Read as the depth attachment without writing. */
        DEPTH_READ,
        /** Read through {@code imageLoad}. */
        IMAGE_READ,
        /** Written through {@code imageStore}. */
        IMAGE_WRITE,
        /** Both loaded and stored. */
        IMAGE_READ_WRITE;

        /** Whether this access writes. */
        public boolean writes() {
            return this == COLOR_ATTACHMENT || this == DEPTH_ATTACHMENT
                || this == IMAGE_WRITE || this == IMAGE_READ_WRITE;
        }

        /** Whether this access reads. */
        public boolean reads() {
            return this == SAMPLE || this == DEPTH_READ || this == IMAGE_READ
                || this == IMAGE_READ_WRITE;
        }

        /** The {@code VkImageLayout} this access requires. */
        public int vkImageLayout() {
            return switch (this) {
                case SAMPLE -> 5;                    // SHADER_READ_ONLY_OPTIMAL
                case COLOR_ATTACHMENT -> 2;          // COLOR_ATTACHMENT_OPTIMAL
                case DEPTH_ATTACHMENT -> 3;          // DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                case DEPTH_READ -> 4;                // DEPTH_STENCIL_READ_ONLY_OPTIMAL
                case IMAGE_READ, IMAGE_WRITE, IMAGE_READ_WRITE -> 1; // GENERAL
            };
        }
    }

    /** One resource the graph manages. */
    public record Resource(String name, ResourceKind kind, int width, int height,
                           int vkFormat, boolean persistent) {

        /** Whether the resource survives between frames. */
        public boolean isHistory() {
            return persistent;
        }
    }

    /** What a resource is. */
    public enum ResourceKind {
        /** A colour render target, i.e. one half of a colortex ping-pong pair. */
        COLOR_TARGET,
        /** A depth target. */
        DEPTH_TARGET,
        /** A shadow map. */
        SHADOW_TARGET,
        /** A pack-declared storage image. */
        STORAGE_IMAGE,
        /** A texture supplied from outside the graph, such as the block atlas. */
        EXTERNAL
    }

    /** One use of a resource by a pass. */
    public record ResourceUse(String resource, Access access) {
    }

    /** One pass. */
    public record Pass(String name, PassKind kind, List<ResourceUse> uses, boolean enabled) {
        public Pass {
            uses = List.copyOf(uses);
        }

        /** Resources this pass reads. */
        public List<String> reads() {
            return uses.stream().filter(u -> u.access().reads())
                .map(ResourceUse::resource).toList();
        }

        /** Resources this pass writes. */
        public List<String> writes() {
            return uses.stream().filter(u -> u.access().writes())
                .map(ResourceUse::resource).toList();
        }
    }

    /** What kind of work a pass performs. */
    public enum PassKind {
        /** Clears targets at the start of a frame. */
        CLEAR,
        /** Draws world geometry. */
        GEOMETRY,
        /** Draws the shadow map. */
        SHADOW,
        /** A screen-filling raster pass. */
        FULLSCREEN,
        /** A compute dispatch. */
        COMPUTE,
        /** Presents to the swapchain. */
        PRESENT
    }

    /** A barrier the executor must insert before a pass. */
    public record Barrier(String resource, Access from, Access to, int fromLayout, int toLayout) {
    }

    /** A pass together with the barriers it needs. */
    public record ScheduledPass(Pass pass, List<Barrier> barriers) {
        public ScheduledPass {
            barriers = List.copyOf(barriers);
        }
    }

    private final Map<String, Resource> resources;
    private final List<ScheduledPass> schedule;
    private final List<String> problems;

    private RenderGraph(Map<String, Resource> resources, List<ScheduledPass> schedule,
                        List<String> problems) {
        this.resources = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        this.schedule = List.copyOf(schedule);
        this.problems = List.copyOf(problems);
    }

    /** Every declared resource. */
    public Map<String, Resource> resources() {
        return resources;
    }

    /** The passes in execution order, each with its barriers. */
    public List<ScheduledPass> schedule() {
        return schedule;
    }

    /** Validation problems; a non-empty list means the graph must not be executed. */
    public List<String> problems() {
        return problems;
    }

    /** Whether the graph is safe to execute. */
    public boolean isValid() {
        return problems.isEmpty();
    }

    /**
     * Persistent bytes of VRAM the graph's own resources require.
     *
     * <p>Transient resources are excluded because they are aliased; see
     * {@link #transientBytes()}.
     */
    public long persistentBytes() {
        return resources.values().stream()
            .filter(Resource::persistent)
            .mapToLong(RenderGraph::bytesOf)
            .sum();
    }

    /** Peak bytes required by transient resources after aliasing. */
    public long transientBytes() {
        return TransientAllocator.peakBytes(this);
    }

    static long bytesOf(Resource resource) {
        // Four bytes per texel is the conservative assumption for an unknown format; callers
        // that know the real format supply it through the target directives instead.
        return (long) resource.width() * resource.height() * 4L;
    }

    /** Builds and validates a graph. */
    public static final class Builder {
        private final Map<String, Resource> resources = new LinkedHashMap<>();
        private final List<Pass> passes = new ArrayList<>();

        /** Declares a resource. */
        public Builder resource(Resource resource) {
            resources.put(resource.name(), resource);
            return this;
        }

        /** Adds a pass. Disabled passes are recorded but skipped entirely at execution. */
        public Builder pass(Pass pass) {
            passes.add(pass);
            return this;
        }

        /**
         * Validates the declarations and computes barriers.
         *
         * <p>Never throws: a pack with a broken pipeline must produce a readable error list
         * for the UI, not an exception during world load.
         */
        public RenderGraph build() {
            List<String> problems = new ArrayList<>();
            List<ScheduledPass> schedule = new ArrayList<>();
            Map<String, Access> lastAccess = new LinkedHashMap<>();
            Set<String> writtenThisFrame = new LinkedHashSet<>();

            for (Pass pass : passes) {
                if (!pass.enabled()) {
                    continue;
                }
                validateUses(pass, problems);

                List<Barrier> barriers = new ArrayList<>();
                Set<String> seen = new LinkedHashSet<>();
                for (ResourceUse use : pass.uses()) {
                    Resource resource = resources.get(use.resource());
                    if (resource == null) {
                        problems.add("pass '" + pass.name() + "' uses undeclared resource '"
                            + use.resource() + "'");
                        continue;
                    }
                    if (!seen.add(use.resource())) {
                        continue;
                    }
                    if (use.access().reads() && !writtenThisFrame.contains(use.resource())
                        && !resource.persistent()
                        && resource.kind() != ResourceKind.EXTERNAL) {
                        problems.add("pass '" + pass.name() + "' reads '" + use.resource()
                            + "' before anything has written it this frame, and it is not"
                            + " declared as a history resource; the contents would be"
                            + " undefined");
                    }
                    Access previous = lastAccess.get(use.resource());
                    if (previous != null && needsBarrier(previous, use.access())) {
                        barriers.add(new Barrier(use.resource(), previous, use.access(),
                            previous.vkImageLayout(), use.access().vkImageLayout()));
                    }
                    lastAccess.put(use.resource(), use.access());
                    if (use.access().writes()) {
                        writtenThisFrame.add(use.resource());
                    }
                }
                schedule.add(new ScheduledPass(pass, barriers));
            }
            return new RenderGraph(resources, schedule, problems);
        }

        /**
         * Rejects a pass that both samples and writes the same image.
         *
         * <p>This is the read-write hazard the ping-pong rules exist to prevent. It is
         * undefined behaviour in Vulkan and produces different garbage on every vendor, so it
         * is refused at build time rather than left to a validation layer the user does not
         * have enabled.
         */
        private static void validateUses(Pass pass, List<String> problems) {
            Map<String, List<Access>> byResource = new LinkedHashMap<>();
            for (ResourceUse use : pass.uses()) {
                byResource.computeIfAbsent(use.resource(), k -> new ArrayList<>())
                    .add(use.access());
            }
            byResource.forEach((resource, accesses) -> {
                boolean samples = accesses.contains(Access.SAMPLE);
                boolean attaches = accesses.contains(Access.COLOR_ATTACHMENT)
                    || accesses.contains(Access.DEPTH_ATTACHMENT);
                if (samples && attaches) {
                    problems.add("pass '" + pass.name() + "' samples '" + resource
                        + "' while also writing it as an attachment; the pack must read the"
                        + " other half of the ping-pong pair instead");
                }
            });
        }

        /** Whether moving from {@code from} to {@code to} needs a barrier. */
        static boolean needsBarrier(Access from, Access to) {
            if (from.writes() || to.writes()) {
                return true;
            }
            // Two reads in the same layout need nothing.
            return from.vkImageLayout() != to.vkImageLayout();
        }
    }

    /** The resource named {@code name}, if declared. */
    public Optional<Resource> resource(String name) {
        return Optional.ofNullable(resources.get(name));
    }
}
