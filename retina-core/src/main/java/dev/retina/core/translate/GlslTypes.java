/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.translate;

import java.util.Map;
import java.util.Set;

/** Facts about GLSL type names that the translator needs in several places. */
public final class GlslTypes {
    private GlslTypes() {
    }

    /** Type qualifiers that may precede a type in a declaration. */
    public static final Set<String> QUALIFIERS = Set.of(
        "lowp", "mediump", "highp", "precise", "invariant", "flat", "smooth", "noperspective",
        "centroid", "sample", "patch", "coherent", "volatile", "restrict", "readonly",
        "writeonly", "const"
    );

    /**
     * Whether {@code type} is an opaque (resource) type.
     *
     * <p>Opaque uniforms must stay standalone declarations with an explicit
     * {@code set}/{@code binding}; they cannot be members of a uniform block. Everything else
     * moves into Retina's generated uniform block.
     */
    public static boolean isOpaque(String type) {
        return type.startsWith("sampler") || type.startsWith("isampler")
            || type.startsWith("usampler") || type.startsWith("image")
            || type.startsWith("iimage") || type.startsWith("uimage")
            || type.startsWith("texture") || type.startsWith("itexture")
            || type.startsWith("utexture") || type.equals("atomic_uint")
            || type.startsWith("subpassInput") || type.equals("sampler")
            || type.equals("samplerShadow");
    }

    /** Whether {@code type} is a sampler (combined image-sampler in Vulkan terms). */
    public static boolean isSampler(String type) {
        return type.startsWith("sampler") || type.startsWith("isampler")
            || type.startsWith("usampler");
    }

    /** Whether {@code type} is a storage image. */
    public static boolean isStorageImage(String type) {
        return type.startsWith("image") || type.startsWith("iimage")
            || type.startsWith("uimage");
    }

    /** Whether {@code type} is a shadow sampler, which needs a comparison sampler in Vulkan. */
    public static boolean isShadowSampler(String type) {
        return type.startsWith("sampler") && type.endsWith("Shadow");
    }

    /** Scalar and vector/matrix types that may appear in a uniform block. */
    public static final Set<String> VALUE_TYPES = Set.of(
        "float", "double", "int", "uint", "bool",
        "vec2", "vec3", "vec4", "dvec2", "dvec3", "dvec4",
        "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
        "bvec2", "bvec3", "bvec4",
        "mat2", "mat3", "mat4",
        "mat2x2", "mat2x3", "mat2x4",
        "mat3x2", "mat3x3", "mat3x4",
        "mat4x2", "mat4x3", "mat4x4",
        "dmat2", "dmat3", "dmat4"
    );

    /** Whether {@code type} may be a uniform block member. */
    public static boolean isValueType(String type) {
        return VALUE_TYPES.contains(type);
    }

    /**
     * Legacy texture functions and their core-profile replacements.
     *
     * <p>These were removed in GLSL 140/150 and do not exist in Vulkan GLSL at all. The
     * overload set is resolved by argument count and sampler type in core GLSL, so a plain
     * rename is exact for every case except the {@code shadow2D*} family, which returned a
     * {@code vec4} in the compatibility profile but returns a {@code float} from
     * {@code texture()} on a shadow sampler. That difference is handled by the translator,
     * not here.
     */
    public static final Map<String, String> LEGACY_TEXTURE_FUNCTIONS = Map.ofEntries(
        Map.entry("texture1D", "texture"),
        Map.entry("texture1DLod", "textureLod"),
        Map.entry("texture1DProj", "textureProj"),
        Map.entry("texture1DProjLod", "textureProjLod"),
        Map.entry("texture2D", "texture"),
        Map.entry("texture2DLod", "textureLod"),
        Map.entry("texture2DProj", "textureProj"),
        Map.entry("texture2DProjLod", "textureProjLod"),
        Map.entry("texture2DGrad", "textureGrad"),
        Map.entry("texture2DGradARB", "textureGrad"),
        Map.entry("texture2DProjGrad", "textureProjGrad"),
        Map.entry("texture2DRect", "texture"),
        Map.entry("texture2DRectProj", "textureProj"),
        Map.entry("texture3D", "texture"),
        Map.entry("texture3DLod", "textureLod"),
        Map.entry("texture3DProj", "textureProj"),
        Map.entry("texture3DProjLod", "textureProjLod"),
        Map.entry("texture3DGrad", "textureGrad"),
        Map.entry("textureCube", "texture"),
        Map.entry("textureCubeLod", "textureLod"),
        Map.entry("textureCubeGrad", "textureGrad"),
        Map.entry("shadow2DLod", "textureLod"),
        Map.entry("shadow2DProjLod", "textureProjLod"),
        Map.entry("shadow1D", "texture"),
        Map.entry("shadow1DLod", "textureLod")
    );

    /**
     * Shadow lookups whose return type changes from {@code vec4} to {@code float}.
     *
     * <p>Wrapped rather than renamed so that pack code doing {@code shadow2D(...).r} keeps
     * compiling; see {@code RETINA_SHADOW_COMPAT} in the generated prologue.
     */
    public static final Set<String> SHADOW_VEC4_FUNCTIONS = Set.of(
        "shadow2D", "shadow2DProj"
    );
}
