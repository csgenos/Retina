/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import dev.retina.mixin.blaze3d.VulkanGpuSamplerAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.OptionalDouble;

/** Creates the comparison sampler Blaze3D 26.2 does not expose in its public sampler facade. */
final class ShadowSampler {
    private ShadowSampler() {
    }

    static GpuSampler create() {
        GpuSampler base = RenderSystem.getDevice().createSampler(
            AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR,
            FilterMode.LINEAR, 1, OptionalDouble.of(0.0));
        if (!(base instanceof VulkanGpuSampler sampler)) {
            base.close();
            throw new IllegalStateException("shadow comparison sampling requires Blaze3D Vulkan");
        }
        VulkanGpuSamplerAccessor access = (VulkanGpuSamplerAccessor)(Object)sampler;
        var device = access.retina$device();
        long old = access.retina$vkSampler();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default();
            info.magFilter(VK10.VK_FILTER_LINEAR);
            info.minFilter(VK10.VK_FILTER_LINEAR);
            info.mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST);
            info.addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            info.addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            info.addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            info.maxLod(0.0f);
            info.compareEnable(true);
            info.compareOp(VK10.VK_COMPARE_OP_GREATER_OR_EQUAL);
            LongBuffer handle = stack.callocLong(1);
            int result = VK10.vkCreateSampler(device.vkDevice(), info, null, handle);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSampler for shadow comparison failed: "
                    + result);
            }
            VK10.vkDestroySampler(device.vkDevice(), old, null);
            access.retina$vkSampler(handle.get(0));
        }
        return sampler;
    }
}
