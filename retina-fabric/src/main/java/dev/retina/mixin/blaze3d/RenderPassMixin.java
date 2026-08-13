/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.blaze3d;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import dev.retina.render.ShaderRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Switches only compatible vanilla entity draws to the active pack's entity pipeline. */
@Mixin(RenderPass.class)
public abstract class RenderPassMixin {
    @ModifyArg(method = "setPipeline", index = 0, at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;setPipeline("
            + "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private RenderPipeline retina$replaceEntityPipeline(RenderPipeline original) {
        return ShaderRuntime.get().entityPipelineFor(original);
    }
}
