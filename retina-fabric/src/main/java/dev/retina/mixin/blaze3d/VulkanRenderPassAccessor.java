/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.blaze3d;

import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the command buffer and pipeline of a Blaze3D Vulkan render pass.
 *
 * <p>This is the whole basis of Retina's claim to be a native Vulkan renderer: the pack
 * pipeline records into the command buffer Minecraft is already recording into, on the device
 * Minecraft already created. No second instance, no second swapchain, no translation layer.
 *
 * <p>Sodium 0.9.1 declares the same two accessors on the same class for the same reason —
 * it needs the raw command buffer to issue {@code vkCmdDrawMultiIndexedEXT} and
 * {@code vkCmdDrawIndexedIndirect} that Blaze3D's {@code RenderPass} does not expose. That
 * two independent mods need this is a sign it is the intended extension point, but it is
 * still an internal surface: {@code compatibilityLevel} and the Sodium version gate exist
 * because it can move between Minecraft versions without notice.
 *
 * <p>Accessor names are prefixed {@code retina$} so they cannot collide with Sodium's
 * {@code sodium$} accessors on the same target class.
 */
@Mixin(VulkanRenderPass.class)
public interface VulkanRenderPassAccessor {

    /** The Vulkan command buffer this pass is recording into. */
    @Accessor("commandBuffer")
    VkCommandBuffer retina$getCommandBuffer();

    /** The pipeline currently bound to this pass. */
    @Accessor("pipeline")
    VulkanRenderPipeline retina$getPipeline();
}
