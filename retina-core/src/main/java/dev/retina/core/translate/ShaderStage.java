/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.translate;

import java.util.Locale;
import java.util.Optional;

/** A programmable pipeline stage, keyed by the file extension packs use for it. */
public enum ShaderStage {
    // The second value is VkShaderStageFlagBits; the third is shaderc_shader_kind. The two
    // orderings differ, and conflating them silently compiles a compute shader as a vertex
    // shader, so both are written out explicitly against their own specification.
    VERTEX("vsh", 0x00000001, 0),
    TESS_CONTROL("tcs", 0x00000002, 4),
    TESS_EVALUATION("tes", 0x00000004, 5),
    GEOMETRY("gsh", 0x00000008, 3),
    FRAGMENT("fsh", 0x00000010, 1),
    COMPUTE("csh", 0x00000020, 2);

    private final String extension;
    private final int vkStageFlag;
    private final int shadercKind;

    ShaderStage(String extension, int vkStageFlag, int shadercKind) {
        this.extension = extension;
        this.vkStageFlag = vkStageFlag;
        this.shadercKind = shadercKind;
    }

    /** The pack file extension, without a dot. */
    public String extension() {
        return extension;
    }

    /** The {@code VkShaderStageFlagBits} value. */
    public int vkStageFlag() {
        return vkStageFlag;
    }

    /** The {@code shaderc_shader_kind} value. */
    public int shadercKind() {
        return shadercKind;
    }

    /** Whether this stage may write colour attachments. */
    public boolean writesFragmentOutputs() {
        return this == FRAGMENT;
    }

    /** Whether this stage consumes vertex attributes. */
    public boolean readsVertexAttributes() {
        return this == VERTEX;
    }

    /**
     * The GLSL keyword {@code varying} means for this stage.
     *
     * <p>{@code varying} was a single keyword whose direction depended on the stage; Vulkan
     * GLSL requires the direction to be explicit, so it has to be resolved here.
     */
    public String varyingDirection() {
        return this == VERTEX || this == TESS_CONTROL || this == TESS_EVALUATION
            || this == GEOMETRY ? "out" : "in";
    }

    /** Looks up a stage by pack file extension. */
    public static Optional<ShaderStage> byExtension(String extension) {
        String ext = extension.trim().toLowerCase(Locale.ROOT);
        for (ShaderStage stage : values()) {
            if (stage.extension.equals(ext)) {
                return Optional.of(stage);
            }
        }
        return Optional.empty();
    }
}
