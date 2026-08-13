/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.blaze3d;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import dev.retina.render.ShaderRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Restores an active pack pipeline after Minecraft clears the Vulkan pipeline cache. */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {
    @Inject(method = "getOrCompilePipeline", at = @At("HEAD"), cancellable = true)
    private void retina$compileFromActivePack(RenderPipeline pipeline,
                                              CallbackInfoReturnable<VulkanRenderPipeline> cir) {
        ShaderSource source = ShaderRuntime.get().customSourceFor(pipeline);
        if (source == null) {
            return;
        }
        VulkanDevice device = (VulkanDevice)(Object)this;
        cir.setReturnValue((VulkanRenderPipeline)device.precompilePipeline(pipeline, source));
    }
}
