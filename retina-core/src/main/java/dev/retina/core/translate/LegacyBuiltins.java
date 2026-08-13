/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.translate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The compatibility-profile builtins Retina supplies, and what it supplies them as.
 *
 * <p>Shader packs are overwhelmingly written against GLSL 120's compatibility profile, where
 * the vertex attributes and matrices are fixed-function state. None of that exists in Vulkan
 * GLSL, so each name has to be re-provided: attributes as explicit vertex inputs at fixed
 * locations, matrices as members of Retina's uniform block.
 *
 * <p>The attribute locations are fixed and must match the vertex formats the Minecraft/Sodium
 * bridge binds. They are listed here, in one place, precisely so that the shader side and the
 * pipeline side cannot drift apart.
 */
public final class LegacyBuiltins {
    private LegacyBuiltins() {
    }

    /**
     * A legacy vertex attribute.
     *
     * @param glslName the name Retina declares it under
     * @param glslType the declared type
     * @param location the fixed {@code layout(location=)} index
     */
    public record Attribute(String glslName, String glslType, int location) {
    }

    /**
     * Legacy {@code gl_*} vertex attributes.
     *
     * <p>Locations 0..7 mirror the historical fixed-function attribute slots, which keeps the
     * mapping predictable for anyone reading Retina's dumped GLSL next to a pack's original.
     */
    public static final Map<String, Attribute> VERTEX_ATTRIBUTES = buildAttributes();

    private static Map<String, Attribute> buildAttributes() {
        Map<String, Attribute> map = new LinkedHashMap<>();
        map.put("gl_Vertex", new Attribute("retina_Vertex", "vec4", 0));
        map.put("gl_Color", new Attribute("retina_Color", "vec4", 1));
        map.put("gl_Normal", new Attribute("retina_Normal", "vec3", 2));
        map.put("gl_MultiTexCoord0", new Attribute("retina_MultiTexCoord0", "vec4", 3));
        // gl_MultiTexCoord1 and gl_MultiTexCoord2 are both the lightmap coordinate in the
        // fixed-function pipeline Minecraft emulated, and packs use them interchangeably.
        // They share one attribute: declaring two inputs at the same location is illegal, and
        // giving them separate locations would leave one of them unfed.
        map.put("gl_MultiTexCoord1", new Attribute("retina_MultiTexCoord1", "vec4", 4));
        map.put("gl_MultiTexCoord2", new Attribute("retina_MultiTexCoord1", "vec4", 4));
        map.put("gl_MultiTexCoord3", new Attribute("retina_MultiTexCoord3", "vec4", 5));
        map.put("gl_MultiTexCoord4", new Attribute("retina_MultiTexCoord4", "vec4", 6));
        map.put("gl_MultiTexCoord5", new Attribute("retina_MultiTexCoord5", "vec4", 7));
        return Map.copyOf(map);
    }

    /**
     * A legacy matrix uniform and the block member that replaces it.
     *
     * @param glslName the generated member name
     * @param glslType the member's type; {@code gl_NormalMatrix} is a {@code mat3}, not a
     *                 {@code mat4}, and emitting it as the wrong size silently corrupts every
     *                 normal a pack transforms
     */
    public record MatrixUniform(String glslName, String glslType) {
    }

    /**
     * Legacy matrix uniforms and the block members that replace them.
     *
     * <p>{@code gl_TextureMatrix} is deliberately absent: it is an array indexed at runtime by
     * some packs, and supplying only the two entries Minecraft ever set would silently give
     * wrong results for any other index. It is reported as unsupported instead.
     */
    public static final Map<String, MatrixUniform> MATRIX_UNIFORMS = Map.ofEntries(
        Map.entry("gl_ModelViewMatrix",
            new MatrixUniform("retina_ModelViewMatrix", "mat4")),
        Map.entry("gl_ModelViewMatrixInverse",
            new MatrixUniform("retina_ModelViewMatrixInverse", "mat4")),
        Map.entry("gl_ProjectionMatrix",
            new MatrixUniform("retina_ProjectionMatrix", "mat4")),
        Map.entry("gl_ProjectionMatrixInverse",
            new MatrixUniform("retina_ProjectionMatrixInverse", "mat4")),
        Map.entry("gl_ModelViewProjectionMatrix",
            new MatrixUniform("retina_ModelViewProjectionMatrix", "mat4")),
        Map.entry("gl_ModelViewProjectionMatrixInverse",
            new MatrixUniform("retina_ModelViewProjectionMatrixInverse", "mat4")),
        Map.entry("gl_NormalMatrix",
            new MatrixUniform("retina_NormalMatrix", "mat3"))
    );
}
