/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.uniform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The scene values Retina supplies to shader packs, their types, and their update frequency.
 *
 * <p>The schema is fixed and shared by every program in a pack. That is what allows the
 * per-frame uniform block to be written once and bound once for the whole frame instead of
 * per program, which is the difference between one buffer update and several hundred.
 *
 * <p>Update frequency drives placement:
 *
 * <ul>
 *   <li>{@link Frequency#FRAME} and {@link Frequency#VIEW} values live in the std140 uniform
 *       block at set 0, written once into a ring-buffered mapped allocation;
 *   <li>{@link Frequency#DRAW} values live in push constants, because a descriptor or buffer
 *       rebind per draw would undo Sodium's batching.
 * </ul>
 *
 * <p>Coordinate conventions are stated per entry and are part of the contract: a pack that
 * reconstructs world position from depth will produce visibly wrong results if
 * {@code gbufferProjectionInverse} is handed a matrix in a different clip-space convention
 * than the one the pack expects.
 */
public final class UniformSchema {

    /** How often a value changes, and therefore where it is stored. */
    public enum Frequency {
        /** Changes at most once per frame. */
        FRAME,
        /** Changes per view; the shadow pass is a second view within one frame. */
        VIEW,
        /** Changes per draw call. */
        DRAW
    }

    /**
     * One supplied value.
     *
     * @param name the GLSL name a pack writes
     * @param glslType the declared type
     * @param frequency where the value is stored
     * @param notes the coordinate space and timing contract, shown in the compatibility matrix
     */
    public record Entry(String name, String glslType, Frequency frequency, String notes) {
    }

    private final Map<String, Entry> entries;

    private UniformSchema(Map<String, Entry> entries) {
        // A LinkedHashMap copy, not Map.copyOf: the declaration order is the std140 member
        // order of the generated uniform block, and Map.copyOf randomises iteration order per
        // JVM run. That would give the same pack a different block layout on every launch and
        // invalidate the pipeline cache every time.
        this.entries = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** The standard schema. */
    public static UniformSchema standard() {
        Map<String, Entry> map = new LinkedHashMap<>();
        List<Entry> all = new ArrayList<>();

        // ---- Matrices ----
        all.add(new Entry("gbufferModelView", "mat4", Frequency.VIEW,
            "world space (camera at origin) to eye space, current frame"));
        all.add(new Entry("gbufferModelViewInverse", "mat4", Frequency.VIEW,
            "eye space to world space (camera at origin), current frame"));
        all.add(new Entry("gbufferProjection", "mat4", Frequency.VIEW,
            "eye space to clip space; Vulkan clip z is [0,1] and y points down, and Retina"
                + " compensates with a negative-height viewport so packs see the OpenGL"
                + " convention they were written for"));
        all.add(new Entry("gbufferProjectionInverse", "mat4", Frequency.VIEW,
            "inverse of gbufferProjection, same convention"));
        all.add(new Entry("gbufferPreviousModelView", "mat4", Frequency.FRAME,
            "gbufferModelView from the previous frame; identity on the first frame after a"
                + " camera cut so motion vectors are zero rather than garbage"));
        all.add(new Entry("gbufferPreviousProjection", "mat4", Frequency.FRAME,
            "gbufferProjection from the previous frame"));
        all.add(new Entry("shadowModelView", "mat4", Frequency.FRAME,
            "world space to shadow eye space"));
        all.add(new Entry("shadowModelViewInverse", "mat4", Frequency.FRAME,
            "inverse of shadowModelView"));
        all.add(new Entry("shadowProjection", "mat4", Frequency.FRAME,
            "shadow eye space to shadow clip space"));
        all.add(new Entry("shadowProjectionInverse", "mat4", Frequency.FRAME,
            "inverse of shadowProjection"));

        // ---- Camera ----
        all.add(new Entry("cameraPosition", "vec3", Frequency.VIEW,
            "camera position in world space, shifted by the same origin as the terrain"));
        all.add(new Entry("previousCameraPosition", "vec3", Frequency.FRAME,
            "cameraPosition from the previous frame"));
        all.add(new Entry("eyeAltitude", "float", Frequency.VIEW, "camera Y in world space"));

        // ---- Time ----
        all.add(new Entry("frameCounter", "int", Frequency.FRAME,
            "frames since the pipeline was created, wrapping at 720720 as packs expect"));
        all.add(new Entry("frameTime", "float", Frequency.FRAME,
            "seconds the previous frame took"));
        all.add(new Entry("frameTimeCounter", "float", Frequency.FRAME,
            "seconds since the pipeline was created, wrapping at 3600"));
        all.add(new Entry("worldTime", "int", Frequency.FRAME, "level time of day in ticks"));
        all.add(new Entry("worldDay", "int", Frequency.FRAME, "level day count"));
        all.add(new Entry("moonPhase", "int", Frequency.FRAME, "0-7"));
        all.add(new Entry("sunAngle", "float", Frequency.FRAME, "0-1 over a full day"));
        all.add(new Entry("shadowAngle", "float", Frequency.FRAME,
            "sunAngle folded so it always describes the shadow-casting body"));
        all.add(new Entry("celestialAngle", "float", Frequency.FRAME, "0-1"));

        // ---- Celestial ----
        all.add(new Entry("sunPosition", "vec3", Frequency.VIEW, "sun direction in eye space"));
        all.add(new Entry("moonPosition", "vec3", Frequency.VIEW, "moon direction in eye space"));
        all.add(new Entry("shadowLightPosition", "vec3", Frequency.VIEW,
            "direction of the current shadow-casting body in eye space"));
        all.add(new Entry("upPosition", "vec3", Frequency.VIEW, "world up in eye space"));

        // ---- Weather and world state ----
        all.add(new Entry("rainStrength", "float", Frequency.FRAME, "0-1"));
        all.add(new Entry("wetness", "float", Frequency.FRAME,
            "rainStrength smoothed with wetnessHalflife"));
        all.add(new Entry("isEyeInWater", "int", Frequency.VIEW,
            "0 none, 1 water, 2 lava, 3 powder snow"));
        all.add(new Entry("eyeBrightness", "ivec2", Frequency.VIEW, "block and sky, 0-240"));
        all.add(new Entry("eyeBrightnessSmooth", "ivec2", Frequency.VIEW,
            "eyeBrightness smoothed with eyeBrightnessHalflife"));
        all.add(new Entry("blindness", "float", Frequency.FRAME, "0-1"));
        all.add(new Entry("nightVision", "float", Frequency.FRAME, "0-1"));
        all.add(new Entry("darknessFactor", "float", Frequency.FRAME, "0-1"));
        all.add(new Entry("darknessLightFactor", "float", Frequency.FRAME, "0-1"));
        all.add(new Entry("screenBrightness", "float", Frequency.FRAME,
            "the video-settings brightness slider, 0-1"));
        all.add(new Entry("playerMood", "float", Frequency.FRAME, "0-1"));
        all.add(new Entry("hideGUI", "int", Frequency.FRAME, "0 or 1"));

        // ---- Fog and sky ----
        all.add(new Entry("fogColor", "vec3", Frequency.VIEW, "linear RGB"));
        all.add(new Entry("skyColor", "vec3", Frequency.VIEW, "linear RGB"));
        all.add(new Entry("fogDensity", "float", Frequency.VIEW, "0-1"));
        all.add(new Entry("fogStart", "float", Frequency.VIEW, "eye-space distance"));
        all.add(new Entry("fogEnd", "float", Frequency.VIEW, "eye-space distance"));
        all.add(new Entry("fogMode", "int", Frequency.VIEW,
            "0 off, 1 linear, 2 exp, 3 exp2; reported as a value rather than emulating"
                + " fixed-function fog state that Vulkan does not have"));

        // ---- Viewport ----
        all.add(new Entry("viewWidth", "float", Frequency.VIEW, "pixels"));
        all.add(new Entry("viewHeight", "float", Frequency.VIEW, "pixels"));
        all.add(new Entry("aspectRatio", "float", Frequency.VIEW, "viewWidth / viewHeight"));
        all.add(new Entry("near", "float", Frequency.VIEW, "near clip distance"));
        all.add(new Entry("far", "float", Frequency.VIEW,
            "far clip distance, i.e. render distance in blocks"));

        // ---- Biome ----
        all.add(new Entry("biome", "int", Frequency.FRAME,
            "biome id from the pack's block.properties-style mapping; stable across reloads"));
        all.add(new Entry("biome_category", "int", Frequency.FRAME, "coarse biome grouping"));
        all.add(new Entry("biome_precipitation", "int", Frequency.FRAME,
            "0 none, 1 rain, 2 snow"));
        all.add(new Entry("temperature", "float", Frequency.FRAME, "biome temperature"));
        all.add(new Entry("rainfall", "float", Frequency.FRAME, "biome downfall"));

        // ---- Held items ----
        all.add(new Entry("heldItemId", "int", Frequency.FRAME, "main hand, from item.properties"));
        all.add(new Entry("heldItemId2", "int", Frequency.FRAME, "off hand"));
        all.add(new Entry("heldBlockLightValue", "int", Frequency.FRAME, "main hand, 0-15"));
        all.add(new Entry("heldBlockLightValue2", "int", Frequency.FRAME, "off hand, 0-15"));

        // ---- Smoothed ----
        all.add(new Entry("centerDepthSmooth", "float", Frequency.FRAME,
            "depth at the screen centre, smoothed with centerDepthHalflife; computed with a"
                + " one-frame-late GPU readback so the steady frame loop never stalls"));

        // ---- Atlas ----
        all.add(new Entry("atlasSize", "ivec2", Frequency.FRAME, "block atlas dimensions"));

        // ---- Per-draw (push constants) ----
        all.add(new Entry("entityColor", "vec4", Frequency.DRAW,
            "hurt/overlay tint for the entity being drawn"));
        all.add(new Entry("entityId", "int", Frequency.DRAW, "from entity.properties"));
        all.add(new Entry("blockEntityId", "int", Frequency.DRAW, "from block.properties"));
        all.add(new Entry("currentRenderedItemId", "int", Frequency.DRAW,
            "from item.properties"));
        all.add(new Entry("renderStage", "int", Frequency.DRAW,
            "which vanilla render stage is executing"));
        all.add(new Entry("chunkOffset", "vec3", Frequency.DRAW,
            "terrain section origin relative to the camera, supplied by the Sodium bridge"));
        all.add(new Entry("alphaTestRef", "float", Frequency.DRAW,
            "the alpha test threshold, also compiled into the shader as a discard"));

        for (Entry entry : all) {
            map.put(entry.name(), entry);
        }
        return new UniformSchema(map);
    }

    /** Every entry. */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** One entry by GLSL name. */
    public Optional<Entry> byName(String name) {
        return Optional.ofNullable(entries.get(name));
    }

    /** Entries that live in the per-frame uniform block. */
    public List<Entry> alwaysPresent() {
        return entries.values().stream()
            .filter(e -> e.frequency() != Frequency.DRAW)
            .toList();
    }

    /** Entries that live in push constants. */
    public List<Entry> pushConstants() {
        return entries.values().stream()
            .filter(e -> e.frequency() == Frequency.DRAW)
            .toList();
    }

    /**
     * Bytes the push-constant block occupies under std430 rules.
     *
     * <p>Checked against the device limit at pipeline creation; the guaranteed Vulkan minimum
     * is 128 bytes, so exceeding it would silently exclude conformant hardware.
     */
    public int pushConstantSize() {
        int offset = 0;
        for (Entry entry : pushConstants()) {
            int size = sizeOf(entry.glslType());
            int alignment = alignmentOf(entry.glslType());
            offset = (offset + alignment - 1) / alignment * alignment;
            offset += size;
        }
        return offset;
    }

    private static int sizeOf(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> 4;
            case "vec2", "ivec2", "uvec2" -> 8;
            case "vec3", "ivec3", "uvec3" -> 12;
            case "vec4", "ivec4", "uvec4" -> 16;
            case "mat3" -> 48;
            case "mat4" -> 64;
            default -> 16;
        };
    }

    private static int alignmentOf(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> 4;
            case "vec2", "ivec2", "uvec2" -> 8;
            default -> 16;
        };
    }
}
