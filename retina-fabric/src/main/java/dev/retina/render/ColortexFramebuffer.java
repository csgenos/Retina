/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.retina.core.target.TargetFormat;
import dev.retina.mixin.blaze3d.CommandEncoderAccessor;
import dev.retina.mixin.blaze3d.VulkanCommandEncoderAccessor;
import dev.retina.pipeline.PreparedTerrainPack;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns both halves of every live colortex target and their frame-to-frame flip state. */
public final class ColortexFramebuffer implements AutoCloseable {
    private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
        | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

    private static final class Pair {
        final PreparedTerrainPack.TargetPlan plan;
        final GpuTexture[] textures = new GpuTexture[2];
        final GpuTextureView[] views = new GpuTextureView[2];
        int main;

        Pair(PreparedTerrainPack.TargetPlan plan) {
            this.plan = plan;
        }

        GpuTexture texture() {
            return textures[main];
        }

        GpuTextureView view() {
            return views[main];
        }

        GpuTextureView alternateView() {
            return views[1 - main];
        }

        void flip() {
            main = 1 - main;
        }
    }

    private final Map<Integer, Pair> targets = new LinkedHashMap<>();
    private int width;
    private int height;

    public ColortexFramebuffer(Map<Integer, PreparedTerrainPack.TargetPlan> plans,
                               int width, int height) {
        plans.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> targets.put(entry.getKey(), new Pair(entry.getValue())));
        resize(width, height);
    }

    public void resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height && !targets.isEmpty()
            && targets.values().stream().allMatch(p -> p.textures[0] != null)) {
            return;
        }
        destroyTextures();
        width = newWidth;
        height = newHeight;
        for (Map.Entry<Integer, Pair> entry : targets.entrySet()) {
            int index = entry.getKey();
            Pair pair = entry.getValue();
            int targetWidth = pair.plan.pixelWidth(width);
            int targetHeight = pair.plan.pixelHeight(height);
            int mipLevels = pair.plan.settings().mipmap()
                ? 32 - Integer.numberOfLeadingZeros(Math.max(targetWidth, targetHeight)) : 1;
            GpuFormat format = gpuFormat(pair.plan.settings().format());
            for (int side = 0; side < 2; side++) {
                pair.textures[side] = RenderSystem.getDevice().createTexture(
                    "Retina colortex" + index + (side == 0 ? " main" : " alt"), USAGE,
                    format, targetWidth, targetHeight, 1, mipLevels);
                pair.views[side] = RenderSystem.getDevice().createTextureView(
                    pair.textures[side]);
            }
            pair.main = 0;
        }
    }

    /** Clears the current main half of every clear-enabled target at frame start. */
    public void clearFrame(CommandEncoder encoder) {
        for (Pair pair : targets.values()) {
            if (!pair.plan.settings().clear()) {
                continue;
            }
            float[] clear = pair.plan.settings().clearColorCopy();
            encoder.clearColorTexture(pair.texture(),
                new Vector4f(clear[0], clear[1], clear[2], clear[3]));
        }
    }

    public GpuTextureView mainView(int index) {
        return require(index).view();
    }

    /** The live texture behind {@link #mainView(int)}, for temporary main-target routing. */
    public GpuTexture mainTexture(int index) {
        return require(index).texture();
    }

    public GpuTextureView alternateView(int index) {
        return require(index).alternateView();
    }

    public List<GpuTextureView> terrainAttachments(List<Integer> indices) {
        return indices.stream().map(this::mainView).toList();
    }

    public List<GpuTextureView> postAttachments(List<Integer> indices) {
        return indices.stream().map(this::alternateView).toList();
    }

    public int targetWidth(int index) {
        return require(index).plan.pixelWidth(width);
    }

    public int targetHeight(int index) {
        return require(index).plan.pixelHeight(height);
    }

    public GpuFormat format(int index) {
        return require(index).textures[0].getFormat();
    }

    public void finishPost(PreparedTerrainPack.PostProgram program) {
        for (int index : program.drawTargets()) {
            if (program.flips().getOrDefault(index, true)) {
                require(index).flip();
            }
        }
    }

    /** Generates the full mip chain for the target's current main half in command order. */
    public void generateMipmaps(CommandEncoder encoder, int index) {
        Pair pair = require(index);
        GpuTexture texture = pair.texture();
        if (!pair.plan.settings().mipmap() || texture.getMipLevels() <= 1) {
            return;
        }
        Object backend = ((CommandEncoderAccessor)(Object)encoder).retina$backend();
        if (!(backend instanceof VulkanCommandEncoder vulkan)
            || !(texture instanceof VulkanGpuTexture image)) {
            throw new IllegalStateException("colortex mipmaps require Blaze3D Vulkan");
        }
        VkCommandBuffer commandBuffer =
            ((VulkanCommandEncoderAccessor)(Object)vulkan).retina$commandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
            int sourceWidth = texture.getWidth(0);
            int sourceHeight = texture.getHeight(0);
            int filter = isInteger(texture.getFormat()) ? VK10.VK_FILTER_NEAREST
                : VK10.VK_FILTER_LINEAR;
            for (int level = 1; level < texture.getMipLevels(); level++) {
                int targetWidth = Math.max(1, sourceWidth / 2);
                int targetHeight = Math.max(1, sourceHeight / 2);
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level - 1).baseArrayLayer(0).layerCount(1);
                blit.srcOffsets(0).set(0, 0, 0);
                blit.srcOffsets(1).set(sourceWidth, sourceHeight, 1);
                blit.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level).baseArrayLayer(0).layerCount(1);
                blit.dstOffsets(0).set(0, 0, 0);
                blit.dstOffsets(1).set(targetWidth, targetHeight, 1);
                VK10.vkCmdBlitImage(commandBuffer, image.vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                    image.vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, blit, filter);
                VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
                sourceWidth = targetWidth;
                sourceHeight = targetHeight;
            }
        }
    }

    private Pair require(int index) {
        Pair pair = targets.get(index);
        if (pair == null) {
            throw new IllegalArgumentException("colortex" + index + " was not allocated");
        }
        return pair;
    }

    private static boolean isInteger(GpuFormat format) {
        String type = format.componentType().name();
        return type.startsWith("UINT") || type.startsWith("SINT");
    }

    /** Exact pack-format to Blaze3D format mapping; unsupported substitutions are forbidden. */
    public static GpuFormat gpuFormat(TargetFormat format) {
        return switch (format) {
            case R8 -> GpuFormat.R8_UNORM;
            case RG8 -> GpuFormat.RG8_UNORM;
            case RGB8 -> GpuFormat.RGB8_UNORM;
            case RGBA8 -> GpuFormat.RGBA8_UNORM;
            case RGBA8_SNORM -> GpuFormat.RGBA8_SNORM;
            case R16 -> GpuFormat.R16_UNORM;
            case RG16 -> GpuFormat.RG16_UNORM;
            case RGBA16 -> GpuFormat.RGBA16_UNORM;
            case R16_SNORM -> GpuFormat.R16_SNORM;
            case RG16_SNORM -> GpuFormat.RG16_SNORM;
            case RGBA16_SNORM -> GpuFormat.RGBA16_SNORM;
            case R16F -> GpuFormat.R16_FLOAT;
            case RG16F -> GpuFormat.RG16_FLOAT;
            case RGB16F -> GpuFormat.RGB16_FLOAT;
            case RGBA16F -> GpuFormat.RGBA16_FLOAT;
            case R32F -> GpuFormat.R32_FLOAT;
            case RG32F -> GpuFormat.RG32_FLOAT;
            case RGB32F -> GpuFormat.RGB32_FLOAT;
            case RGBA32F -> GpuFormat.RGBA32_FLOAT;
            case R11F_G11F_B10F -> GpuFormat.RG11B10_FLOAT;
            case RGB10_A2 -> GpuFormat.RGB10A2_UNORM;
            case RGB10_A2UI -> GpuFormat.RGB10A2_UINT;
            case R8I -> GpuFormat.R8_SINT;
            case R8UI -> GpuFormat.R8_UINT;
            case RG8I -> GpuFormat.RG8_SINT;
            case RG8UI -> GpuFormat.RG8_UINT;
            case RGBA8I -> GpuFormat.RGBA8_SINT;
            case RGBA8UI -> GpuFormat.RGBA8_UINT;
            case R16I -> GpuFormat.R16_SINT;
            case R16UI -> GpuFormat.R16_UINT;
            case RG16I -> GpuFormat.RG16_SINT;
            case RG16UI -> GpuFormat.RG16_UINT;
            case RGBA16I -> GpuFormat.RGBA16_SINT;
            case RGBA16UI -> GpuFormat.RGBA16_UINT;
            case R32I -> GpuFormat.R32_SINT;
            case R32UI -> GpuFormat.R32_UINT;
            case RG32I -> GpuFormat.RG32_SINT;
            case RG32UI -> GpuFormat.RG32_UINT;
            case RGBA32I -> GpuFormat.RGBA32_SINT;
            case RGBA32UI -> GpuFormat.RGBA32_UINT;
            case SRGB8_ALPHA8, RGB5_A1, RGB565, RGBA4, RGB9_E5, RGB16 ->
                throw new IllegalArgumentException(format.glName()
                    + " has no exact Blaze3D 26.2 render-target format");
        };
    }

    private void destroyTextures() {
        for (Pair pair : targets.values()) {
            for (int side = 0; side < 2; side++) {
                if (pair.views[side] != null) {
                    pair.views[side].close();
                    pair.views[side] = null;
                }
                if (pair.textures[side] != null) {
                    pair.textures[side].close();
                    pair.textures[side] = null;
                }
            }
        }
    }

    @Override
    public void close() {
        destroyTextures();
    }
}
