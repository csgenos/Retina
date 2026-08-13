/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.blaze3d;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.retina.render.ShaderRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Switches only compatible vanilla entity draws to the active pack's entity pipeline. */
@Mixin(RenderPass.class)
public abstract class RenderPassMixin {
    @Inject(method = "setPipeline", at = @At("HEAD"))
    private void retina$trackEntityPipeline(RenderPipeline pipeline, CallbackInfo ci) {
        ShaderRuntime.get().trackEntityPipeline((RenderPass)(Object)this, pipeline);
    }

    @Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
        at = @At("HEAD"))
    private void retina$trackEntityUniform(String name, GpuBufferSlice value, CallbackInfo ci) {
        ShaderRuntime.get().trackEntityUniform((RenderPass)(Object)this, name, value);
    }

    @Inject(method = "bindTexture", at = @At("HEAD"))
    private void retina$trackEntityTexture(String name, GpuTextureView view, GpuSampler sampler,
                                           CallbackInfo ci) {
        ShaderRuntime.get().trackEntityTexture((RenderPass)(Object)this, name, view, sampler);
    }

    @Inject(method = "setVertexBuffer", at = @At("HEAD"))
    private void retina$trackEntityVertexBuffer(int slot, GpuBufferSlice value, CallbackInfo ci) {
        ShaderRuntime.get().trackEntityVertexBuffer((RenderPass)(Object)this, slot, value);
    }

    @Inject(method = "setIndexBuffer", at = @At("HEAD"))
    private void retina$trackEntityIndexBuffer(GpuBuffer buffer, IndexType type, CallbackInfo ci) {
        ShaderRuntime.get().trackEntityIndexBuffer((RenderPass)(Object)this, buffer, type);
    }

    @Inject(method = "drawIndexed", at = @At("HEAD"))
    private void retina$captureEntityDraw(int indexCount, int instanceCount, int firstIndex,
                                          int vertexOffset, int firstInstance, CallbackInfo ci) {
        ShaderRuntime.get().captureEntityDraw((RenderPass)(Object)this, indexCount, instanceCount,
            firstIndex, vertexOffset, firstInstance);
    }

    @ModifyArg(method = "setPipeline", index = 0, at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;setPipeline("
            + "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private RenderPipeline retina$replaceEntityPipeline(RenderPipeline original) {
        return ShaderRuntime.get().entityPipelineFor(original);
    }
}
