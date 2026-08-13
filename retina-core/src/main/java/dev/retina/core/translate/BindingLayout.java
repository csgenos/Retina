/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assigns descriptor sets and bindings to the resources a program uses.
 *
 * <p>Assignment is deterministic and derived only from the resource name, never from the
 * order in which declarations happen to appear in a file. This matters for two reasons:
 * pipeline layouts must be compatible across the programs in a pass chain so descriptor sets
 * can be bound once rather than per program, and the pipeline cache key includes the layout,
 * so a nondeterministic assignment would defeat the cache on every load.
 *
 * <p>The set split follows update frequency, which is what descriptor sets are for:
 *
 * <table border="1">
 *   <caption>Descriptor set allocation</caption>
 *   <tr><th>Set</th><th>Contents</th><th>Rebound</th></tr>
 *   <tr><td>0</td><td>Retina uniform block, per-frame scene values</td><td>once per frame</td></tr>
 *   <tr><td>1</td><td>Scene samplers: colortex, depthtex, shadowtex, shadowcolor, noisetex,
 *       atlas, lightmap</td><td>once per pass</td></tr>
 *   <tr><td>2</td><td>Storage images declared by the pack</td><td>once per pass</td></tr>
 *   <tr><td>3</td><td>Pack-declared custom textures and buffers</td><td>once per pipeline</td></tr>
 * </table>
 *
 * <p>Per-draw data does not get a descriptor set at all; it goes in push constants, because
 * a descriptor rebind per draw is exactly the cost Sodium's batching exists to avoid.
 */
public final class BindingLayout {

    /** Descriptor set index for Retina's per-frame uniform block. */
    public static final int SET_UNIFORMS = 0;
    /** Descriptor set index for scene samplers. */
    public static final int SET_SAMPLERS = 1;
    /** Descriptor set index for storage images. */
    public static final int SET_IMAGES = 2;
    /** Descriptor set index for pack-declared custom resources. */
    public static final int SET_CUSTOM = 3;

    /** Binding of the uniform block within {@link #SET_UNIFORMS}. */
    public static final int BINDING_UNIFORM_BLOCK = 0;

    /** Size in bytes of the per-draw push-constant range. */
    public static final int PUSH_CONSTANT_SIZE = 128;

    /** One assigned resource. */
    public record Binding(String name, String glslType, int set, int binding, Role role) {
        /** What the resource is used for, which decides its descriptor type. */
        public enum Role {
            /** A colortex/depthtex/shadowtex/... scene sampler. */
            SCENE_SAMPLER,
            /** A pack-declared custom texture. */
            CUSTOM_SAMPLER,
            /** A storage image the pack reads or writes. */
            STORAGE_IMAGE,
            /** A shader storage buffer. */
            STORAGE_BUFFER
        }

        /** The {@code VkDescriptorType} enumerant for this binding. */
        public int vkDescriptorType() {
            return switch (role) {
                case SCENE_SAMPLER, CUSTOM_SAMPLER -> 1; // COMBINED_IMAGE_SAMPLER
                case STORAGE_IMAGE -> 3;                 // STORAGE_IMAGE
                case STORAGE_BUFFER -> 7;                // STORAGE_BUFFER
            };
        }

        /** The GLSL layout qualifier for this binding. */
        public String layoutQualifier() {
            return "layout(set = " + set + ", binding = " + binding + ")";
        }
    }

    /**
     * Scene sampler names in their fixed binding order.
     *
     * <p>The order is fixed rather than discovered so that every program in a pack shares one
     * descriptor set layout. Names a pack never mentions still occupy their slot; an unused
     * binding costs nothing at draw time and keeps layouts compatible.
     */
    private static final List<String> SCENE_SAMPLERS = buildSceneSamplers();

    private static List<String> buildSceneSamplers() {
        List<String> names = new ArrayList<>();
        // Vanilla-texture samplers, which gbuffer programs read.
        names.add("gtexture");
        names.add("lightmap");
        names.add("normals");
        names.add("specular");
        names.add("noisetex");
        // colortex0..15 plus the legacy aliases for the first eight.
        for (int i = 0; i < 16; i++) {
            names.add("colortex" + i);
        }
        // Depth and shadow.
        names.add("depthtex0");
        names.add("depthtex1");
        names.add("depthtex2");
        names.add("shadowtex0");
        names.add("shadowtex1");
        names.add("shadow");
        for (int i = 0; i < 2; i++) {
            names.add("shadowcolor" + i);
        }
        return List.copyOf(names);
    }

    /**
     * Legacy aliases for the first eight colour targets, and for the vanilla atlas.
     *
     * <p>{@code gcolor} and friends are the names the original ShadersMod used; packs still
     * ship them, sometimes alongside the modern names in the same program. Aliasing them onto
     * the same binding — rather than giving each its own sampler — is what makes a pack that
     * reads {@code gcolor} in one include and {@code colortex0} in another see one image.
     */
    private static final Map<String, String> LEGACY_ALIASES = Map.ofEntries(
        Map.entry("gcolor", "colortex0"),
        Map.entry("gdepth", "colortex1"),
        Map.entry("gnormal", "colortex2"),
        Map.entry("composite", "colortex3"),
        Map.entry("gaux1", "colortex4"),
        Map.entry("gaux2", "colortex5"),
        Map.entry("gaux3", "colortex6"),
        Map.entry("gaux4", "colortex7"),
        Map.entry("texture", "gtexture"),
        Map.entry("tex", "gtexture"),
        Map.entry("shadowcolor", "shadowcolor0"),
        Map.entry("watershadow", "shadowtex0"),
        Map.entry("depthtex", "depthtex0")
    );

    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private int nextCustomSampler;
    private int nextStorageImage;
    private int nextStorageBuffer;

    public BindingLayout() {
        for (int i = 0; i < SCENE_SAMPLERS.size(); i++) {
            String name = SCENE_SAMPLERS.get(i);
            bindings.put(name, new Binding(name, null, SET_SAMPLERS, i,
                Binding.Role.SCENE_SAMPLER));
        }
    }

    /**
     * The canonical name for {@code name}, resolving legacy aliases.
     *
     * @return the canonical name, or {@code name} itself when it is not an alias
     */
    public static String canonicalName(String name) {
        return LEGACY_ALIASES.getOrDefault(name, name);
    }

    /** Whether {@code name} is a legacy alias of another sampler. */
    public static boolean isLegacyAlias(String name) {
        return LEGACY_ALIASES.containsKey(name);
    }

    /** Every legacy alias and the sampler it refers to. */
    public static Map<String, String> legacyAliases() {
        return LEGACY_ALIASES;
    }

    /** The binding for a known scene sampler, resolving aliases. */
    public Optional<Binding> sceneSampler(String name) {
        return Optional.ofNullable(bindings.get(canonicalName(name)))
            .filter(b -> b.role() == Binding.Role.SCENE_SAMPLER);
    }

    /**
     * Assigns a binding for a resource the pack declared that is not a scene sampler.
     *
     * <p>Idempotent: declaring the same name twice, which happens when two includes both
     * declare a custom texture, returns the existing binding rather than allocating a second.
     */
    public Binding declare(String name, String glslType) {
        String canonical = canonicalName(name);
        Binding existing = bindings.get(canonical);
        if (existing != null) {
            return existing;
        }
        Binding created;
        if (GlslTypes.isStorageImage(glslType)) {
            created = new Binding(canonical, glslType, SET_IMAGES, nextStorageImage++,
                Binding.Role.STORAGE_IMAGE);
        } else if (GlslTypes.isSampler(glslType)) {
            created = new Binding(canonical, glslType, SET_CUSTOM, nextCustomSampler++,
                Binding.Role.CUSTOM_SAMPLER);
        } else {
            created = new Binding(canonical, glslType, SET_CUSTOM, 128 + nextStorageBuffer++,
                Binding.Role.STORAGE_BUFFER);
        }
        bindings.put(canonical, created);
        return created;
    }

    /** Records the concrete GLSL type a program used for a scene sampler. */
    public Binding withType(String name, String glslType) {
        String canonical = canonicalName(name);
        Binding existing = bindings.get(canonical);
        if (existing == null) {
            return declare(canonical, glslType);
        }
        Binding typed = new Binding(existing.name(), glslType, existing.set(),
            existing.binding(), existing.role());
        bindings.put(canonical, typed);
        return typed;
    }

    /** Every binding assigned so far, in assignment order. */
    public List<Binding> bindings() {
        return List.copyOf(bindings.values());
    }

    /** Bindings that a program actually declared, i.e. those with a known type. */
    public List<Binding> usedBindings() {
        return bindings.values().stream().filter(b -> b.glslType() != null).toList();
    }

    /** The number of scene sampler slots, i.e. the size of set 1. */
    public static int sceneSamplerCount() {
        return SCENE_SAMPLERS.size();
    }

    /** The fixed scene sampler names in binding order. */
    public static List<String> sceneSamplerNames() {
        return SCENE_SAMPLERS;
    }
}
