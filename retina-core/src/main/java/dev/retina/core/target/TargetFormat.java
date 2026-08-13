/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.target;

import java.util.Locale;
import java.util.Optional;

/**
 * A render-target or image format declared by a pack.
 *
 * <p>Packs name formats with OpenGL sized internal format tokens ({@code RGBA16F},
 * {@code R11F_G11F_B10F}, ...). Each maps to exactly one {@code VkFormat}, so no precision
 * is invented or lost in translation. Formats with no Vulkan equivalent are represented here
 * so they can be <em>reported</em> precisely rather than silently substituted; see
 * {@link #vkFormat()} returning empty.
 *
 * <p>The {@code vk} constants are numeric {@code VkFormat} enumerants, written literally so
 * this enum is usable without loading Vulkan natives.
 */
public enum TargetFormat {
    // 8-bit normalised
    R8("R8", 9, 1, 1, Kind.FLOAT),
    RG8("RG8", 16, 2, 1, Kind.FLOAT),
    RGB8("RGB8", 23, 3, 1, Kind.FLOAT),
    RGBA8("RGBA8", 37, 4, 1, Kind.FLOAT),
    RGBA8_SNORM("RGBA8_SNORM", 38, 4, 1, Kind.FLOAT),
    SRGB8_ALPHA8("SRGB8_ALPHA8", 43, 4, 1, Kind.FLOAT),

    // 16-bit normalised
    R16("R16", 70, 1, 2, Kind.FLOAT),
    RG16("RG16", 77, 2, 2, Kind.FLOAT),
    RGBA16("RGBA16", 91, 4, 2, Kind.FLOAT),
    R16_SNORM("R16_SNORM", 71, 1, 2, Kind.FLOAT),
    RG16_SNORM("RG16_SNORM", 78, 2, 2, Kind.FLOAT),
    RGBA16_SNORM("RGBA16_SNORM", 92, 4, 2, Kind.FLOAT),

    // Floating point
    R16F("R16F", 76, 1, 2, Kind.FLOAT),
    RG16F("RG16F", 83, 2, 2, Kind.FLOAT),
    RGB16F("RGB16F", 90, 3, 2, Kind.FLOAT),
    RGBA16F("RGBA16F", 97, 4, 2, Kind.FLOAT),
    R32F("R32F", 100, 1, 4, Kind.FLOAT),
    RG32F("RG32F", 103, 2, 4, Kind.FLOAT),
    RGB32F("RGB32F", 106, 3, 4, Kind.FLOAT),
    RGBA32F("RGBA32F", 109, 4, 4, Kind.FLOAT),
    R11F_G11F_B10F("R11F_G11F_B10F", 122, 3, 0, Kind.FLOAT),

    // Packed
    RGB10_A2("RGB10_A2", 64, 4, 0, Kind.FLOAT),
    RGB10_A2UI("RGB10_A2UI", 68, 4, 0, Kind.UINT),
    RGB5_A1("RGB5_A1", 6, 4, 0, Kind.FLOAT),
    RGB565("RGB565", 4, 3, 0, Kind.FLOAT),
    RGBA4("RGBA4", 2, 4, 0, Kind.FLOAT),

    // Integer
    R8I("R8I", 13, 1, 1, Kind.SINT),
    R8UI("R8UI", 12, 1, 1, Kind.UINT),
    RG8I("RG8I", 20, 2, 1, Kind.SINT),
    RG8UI("RG8UI", 19, 2, 1, Kind.UINT),
    RGBA8I("RGBA8I", 41, 4, 1, Kind.SINT),
    RGBA8UI("RGBA8UI", 40, 4, 1, Kind.UINT),
    R16I("R16I", 75, 1, 2, Kind.SINT),
    R16UI("R16UI", 74, 1, 2, Kind.UINT),
    RG16I("RG16I", 82, 2, 2, Kind.SINT),
    RG16UI("RG16UI", 81, 2, 2, Kind.UINT),
    RGBA16I("RGBA16I", 96, 4, 2, Kind.SINT),
    RGBA16UI("RGBA16UI", 95, 4, 2, Kind.UINT),
    R32I("R32I", 99, 1, 4, Kind.SINT),
    R32UI("R32UI", 98, 1, 4, Kind.UINT),
    RG32I("RG32I", 102, 2, 4, Kind.SINT),
    RG32UI("RG32UI", 101, 2, 4, Kind.UINT),
    RGBA32I("RGBA32I", 108, 4, 4, Kind.SINT),
    RGBA32UI("RGBA32UI", 107, 4, 4, Kind.UINT),

    // Formats packs may name that have no direct Vulkan equivalent.
    RGB9_E5("RGB9_E5", 123, 3, 0, Kind.FLOAT),
    RGB16("RGB16", 84, 3, 2, Kind.FLOAT);

    /** How a format's texels are interpreted by a sampler. */
    public enum Kind { FLOAT, SINT, UINT }

    private final String glName;
    private final int vkFormat;
    private final int components;
    private final int bytesPerComponent;
    private final Kind kind;

    TargetFormat(String glName, int vkFormat, int components, int bytesPerComponent, Kind kind) {
        this.glName = glName;
        this.vkFormat = vkFormat;
        this.components = components;
        this.bytesPerComponent = bytesPerComponent;
        this.kind = kind;
    }

    /** The OpenGL sized internal format name as written in a pack. */
    public String glName() {
        return glName;
    }

    /** The {@code VkFormat} enumerant, or empty when there is no equivalent. */
    public Optional<Integer> vkFormat() {
        return vkFormat == 0 ? Optional.empty() : Optional.of(vkFormat);
    }

    /** How samplers interpret this format. */
    public Kind kind() {
        return kind;
    }

    /** The GLSL sampler prefix a shader must use: {@code ""}, {@code "i"} or {@code "u"}. */
    public String samplerPrefix() {
        return switch (kind) {
            case FLOAT -> "";
            case SINT -> "i";
            case UINT -> "u";
        };
    }

    /**
     * Bytes occupied by one texel.
     *
     * <p>Packed formats report their true packed size rather than
     * {@code components * bytesPerComponent}, which is why the packed cases are listed
     * explicitly instead of computed.
     */
    public int bytesPerTexel() {
        return switch (this) {
            case R11F_G11F_B10F, RGB10_A2, RGB10_A2UI, RGB9_E5 -> 4;
            case RGB5_A1, RGB565, RGBA4 -> 2;
            // Three-component formats are almost universally padded to four by drivers;
            // budgeting for the padded size avoids under-reporting VRAM to the user.
            default -> (components == 3 ? 4 : components) * bytesPerComponent;
        };
    }

    /** Whether this format can be used with {@code imageLoad}/{@code imageStore}. */
    public boolean supportsImageStore() {
        return switch (this) {
            case RGB8, RGB16, RGB16F, RGB32F, RGB565, RGBA4, RGB5_A1, RGB9_E5, SRGB8_ALPHA8 ->
                false;
            default -> true;
        };
    }

    /** The GLSL {@code layout(...)} qualifier used when this format backs a storage image. */
    public String glslImageFormatQualifier() {
        return glName.toLowerCase(Locale.ROOT);
    }

    /** Parses a pack-written format token. */
    public static Optional<TargetFormat> parse(String token) {
        String name = token.trim().toUpperCase(Locale.ROOT);
        if (name.startsWith("GL_")) {
            name = name.substring(3);
        }
        for (TargetFormat format : values()) {
            if (format.glName.equals(name)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
