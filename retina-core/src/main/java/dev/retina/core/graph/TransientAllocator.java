/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes lifetimes for non-persistent graph resources and aliases their memory.
 *
 * <p>A composite chain of twelve passes may declare a dozen full-screen intermediates that
 * are each live for two passes. Allocating them all costs several hundred megabytes at 4K;
 * aliasing the ones whose lifetimes do not overlap costs a fraction of that, and it is safe
 * because the graph already knows exactly when each resource is last read.
 *
 * <p>Aliasing is only applied to resources marked non-persistent. A history resource — one a
 * pack reads from the previous frame — is never aliased, because reusing its memory is
 * exactly the bug that makes temporal effects flicker.
 */
public final class TransientAllocator {
    private TransientAllocator() {
    }

    /**
     * The first and last scheduled pass index at which a resource is used.
     *
     * @param first index of the first pass that touches the resource
     * @param last index of the last pass that touches it
     */
    public record Lifetime(String resource, int first, int last, long bytes) {
        /** Whether this lifetime overlaps {@code other}. */
        public boolean overlaps(Lifetime other) {
            return first <= other.last && other.first <= last;
        }
    }

    /** Computes lifetimes for every transient resource in {@code graph}. */
    public static List<Lifetime> lifetimes(RenderGraph graph) {
        Map<String, int[]> spans = new LinkedHashMap<>();
        List<RenderGraph.ScheduledPass> schedule = graph.schedule();
        for (int index = 0; index < schedule.size(); index++) {
            final int passIndex = index;
            for (RenderGraph.ResourceUse use : schedule.get(index).pass().uses()) {
                spans.compute(use.resource(), (name, span) -> span == null
                    ? new int[]{passIndex, passIndex}
                    : new int[]{Math.min(span[0], passIndex), Math.max(span[1], passIndex)});
            }
        }
        List<Lifetime> out = new ArrayList<>();
        spans.forEach((name, span) -> graph.resource(name).ifPresent(resource -> {
            if (!resource.persistent() && resource.kind() != RenderGraph.ResourceKind.EXTERNAL) {
                out.add(new Lifetime(name, span[0], span[1], RenderGraph.bytesOf(resource)));
            }
        }));
        return out;
    }

    /** One block of memory shared by several non-overlapping resources. */
    public record Bucket(long bytes, List<String> resources) {
        public Bucket {
            resources = List.copyOf(resources);
        }
    }

    /**
     * Packs transient resources into shared buckets.
     *
     * <p>Uses first-fit over lifetimes sorted by descending size. Optimal packing is
     * NP-hard and pointless here: the resource count is in the dozens, and first-fit
     * descending is within a few percent of optimal for this shape of input while being
     * deterministic, which matters because the allocation plan is part of the pipeline cache
     * key.
     */
    public static List<Bucket> pack(RenderGraph graph) {
        List<Lifetime> lifetimes = new ArrayList<>(lifetimes(graph));
        lifetimes.sort((a, b) -> {
            int bySize = Long.compare(b.bytes(), a.bytes());
            // Tie-break by name so the plan does not depend on map iteration order.
            return bySize != 0 ? bySize : a.resource().compareTo(b.resource());
        });

        List<List<Lifetime>> buckets = new ArrayList<>();
        for (Lifetime lifetime : lifetimes) {
            boolean placed = false;
            for (List<Lifetime> bucket : buckets) {
                if (bucket.stream().noneMatch(existing -> existing.overlaps(lifetime))) {
                    bucket.add(lifetime);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                List<Lifetime> bucket = new ArrayList<>();
                bucket.add(lifetime);
                buckets.add(bucket);
            }
        }

        List<Bucket> out = new ArrayList<>();
        for (List<Lifetime> bucket : buckets) {
            long size = bucket.stream().mapToLong(Lifetime::bytes).max().orElse(0L);
            out.add(new Bucket(size, bucket.stream().map(Lifetime::resource).toList()));
        }
        return out;
    }

    /** Peak transient bytes after aliasing. */
    public static long peakBytes(RenderGraph graph) {
        return pack(graph).stream().mapToLong(Bucket::bytes).sum();
    }

    /** Bytes that would be needed without aliasing, for reporting the saving to the user. */
    public static long unaliasedBytes(RenderGraph graph) {
        return lifetimes(graph).stream().mapToLong(Lifetime::bytes).sum();
    }
}
