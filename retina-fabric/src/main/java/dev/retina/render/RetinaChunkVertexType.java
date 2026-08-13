/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.util.Mth;

/**
 * Sodium's compact terrain format plus the face normal required by legacy shader packs.
 *
 * <p>The first twenty bytes are byte-for-byte Sodium's compact format. Keeping those fields
 * and names intact means Sodium's ordinary shader remains valid when packs are disabled. The
 * final four bytes are an RGBA8_SNORM normal; its packed xyz value is carried in the upper
 * twenty-four bits of the encoder's material argument by the two mesh-builder mixins.
 */
public final class RetinaChunkVertexType implements ChunkVertexType {
    public static final RetinaChunkVertexType INSTANCE = new RetinaChunkVertexType();
    public static final int STRIDE = 24;

    public static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
        .addAttribute("a_Position", GpuFormat.RG32_UINT)
        .addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("a_TexCoord", GpuFormat.RG16_UINT)
        .addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT)
        .addAttribute("a_RetinaNormal", GpuFormat.RGBA8_SNORM)
        .build();

    private static final int POSITION_MAX_VALUE = 1 << 20;
    private static final int TEXTURE_MAX_VALUE = 1 << 15;

    private RetinaChunkVertexType() {
    }

    @Override
    public VertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder getEncoder() {
        return RetinaChunkVertexType::write;
    }

    private static long write(long pointer, int materialAndNormal,
                              ChunkVertexEncoder.Vertex[] vertices, int section) {
        float centroidU = 0.0f;
        float centroidV = 0.0f;
        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            centroidU += vertex.u;
            centroidV += vertex.v;
        }
        centroidU *= 0.25f;
        centroidV *= 0.25f;

        int normal = materialAndNormal >>> 8;
        int material = materialAndNormal & 0xff;
        int packedNormal = normal | 0x7f000000;
        for (ChunkVertexEncoder.Vertex vertex : vertices) {
            int x = quantizePosition(vertex.x);
            int y = quantizePosition(vertex.y);
            int z = quantizePosition(vertex.z);
            int u = encodeTexture(centroidU, vertex.u);
            int v = encodeTexture(centroidV, vertex.v);
            int light = encodeLight(vertex.light);

            MemoryIntrinsics.putInt(pointer, packPositionHi(x, y, z));
            MemoryIntrinsics.putInt(pointer + 4L, packPositionLo(x, y, z));
            MemoryIntrinsics.putInt(pointer + 8L, ColorARGB.mulRGB(vertex.color, vertex.ao));
            MemoryIntrinsics.putInt(pointer + 12L, (u & 0xffff) | (v & 0xffff) << 16);
            MemoryIntrinsics.putInt(pointer + 16L,
                (light & 0xffff) | (material & 0xff) << 16 | (section & 0xff) << 24);
            MemoryIntrinsics.putInt(pointer + 20L, packedNormal);
            pointer += STRIDE;
        }
        return pointer;
    }

    private static int quantizePosition(float position) {
        return (int)(((8.0f + position) / 32.0f) * POSITION_MAX_VALUE)
            & (POSITION_MAX_VALUE - 1);
    }

    private static int packPositionHi(int x, int y, int z) {
        return (x >>> 10 & 1023) | (y >>> 10 & 1023) << 10 | (z >>> 10 & 1023) << 20;
    }

    private static int packPositionLo(int x, int y, int z) {
        return (x & 1023) | (y & 1023) << 10 | (z & 1023) << 20;
    }

    private static int encodeTexture(float center, float value) {
        int bias = value < center ? 1 : -1;
        int quantized = Math.round(value * TEXTURE_MAX_VALUE) + bias;
        return quantized & (TEXTURE_MAX_VALUE - 1) | (bias >>> 31) << 15;
    }

    private static int encodeLight(int light) {
        int sky = Mth.clamp((light >>> 16 & 0xff) + 8, 8, 248);
        int block = Mth.clamp((light & 0xff) + 8, 8, 248);
        return block | sky << 8;
    }
}
