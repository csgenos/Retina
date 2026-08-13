/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.sodium;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.retina.render.ShaderRuntime;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class ShaderChunkRendererMixin {
    @Shadow protected RenderPipeline activeProgram;

    @Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true)
    private void retina$usePackTerrainPipeline(TerrainRenderPass pass,
                                               CallbackInfoReturnable<RenderPipeline> cir) {
        RenderPipeline pipeline = ShaderRuntime.get().pipelineFor(pass);
        if (pipeline != null) {
            cir.setReturnValue(pipeline);
        }
    }

    @Inject(method = "begin", at = @At("RETURN"))
    private void retina$selectShadowPipeline(TerrainRenderPass pass,
                                              net.caffeinemc.mods.sodium.client.util.FogParameters fog,
                                              com.mojang.blaze3d.textures.GpuSampler sampler,
                                              org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (ShaderRuntime.get().isShadowRendering()) {
            RenderPipeline pipeline = ShaderRuntime.get().pipelineFor(pass);
            if (pipeline != null) {
                activeProgram = pipeline;
            }
        }
    }
}
