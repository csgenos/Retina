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

/**
 * Switches only ABI-validated vanilla scene stages to their active pack pipelines.
 *
 * <p>These are the hottest injection points Retina owns: every draw call the game makes, from
 * every subsystem, passes through them whether or not a shader pack is loaded. Each capture
 * hook therefore reads {@link ShaderRuntime#isCapturingDrawState()} — one static volatile field
 * — before touching anything else, so with shaders off the whole group costs a predictable
 * branch rather than six virtual calls and six map lookups per draw.
 */
@Mixin(RenderPass.class)
public abstract class RenderPassMixin {
    @Inject(method = "setPipeline", at = @At("HEAD"))
    private void retina$trackEntityPipeline(RenderPipeline pipeline, CallbackInfo ci) {
        if (!ShaderRuntime.isCapturingDrawState()) {
            return;
        }
        ShaderRuntime.get().trackEntityPipeline((RenderPass)(Object)this, pipeline);
    }

    @Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
        at = @At("HEAD"))
    private void retina$trackEntityUniform(String name, GpuBufferSlice value, CallbackInfo ci) {
        if (!ShaderRuntime.isCapturingDrawState()) {
            return;
        }
        ShaderRuntime.get().trackEntityUniform((RenderPass)(Object)this, name, value);
    }

    @Inject(method = "bindTexture", at = @At("HEAD"))
    private void retina$trackEntityTexture(String name, GpuTextureView view, GpuSampler sampler,
                                           CallbackInfo ci) {
        if (!ShaderRuntime.isCapturingDrawState()) {
            return;
        }
        ShaderRuntime.get().trackEntityTexture((RenderPass)(Object)this, name, view, sampler);
    }

    @Inject(method = "setVertexBuffer", at = @At("HEAD"))
    private void retina$trackEntityVertexBuffer(int slot, GpuBufferSlice value, CallbackInfo ci) {
        if (!ShaderRuntime.isCapturingDrawState()) {
            return;
        }
        ShaderRuntime.get().trackEntityVertexBuffer((RenderPass)(Object)this, slot, value);
    }

    @Inject(method = "setIndexBuffer", at = @At("HEAD"))
    private void retina$trackEntityIndexBuffer(GpuBuffer buffer, IndexType type, CallbackInfo ci) {
        if (!ShaderRuntime.isCapturingDrawState()) {
            return;
        }
        ShaderRuntime.get().trackEntityIndexBuffer((RenderPass)(Object)this, buffer, type);
    }

    @Inject(method = "drawIndexed", at = @At("HEAD"))
    private void retina$captureEntityDraw(int indexCount, int instanceCount, int firstIndex,
                                          int vertexOffset, int firstInstance, CallbackInfo ci) {
        if (!ShaderRuntime.isCapturingDrawState()) {
            return;
        }
        ShaderRuntime.get().captureEntityDraw((RenderPass)(Object)this, indexCount, instanceCount,
            firstIndex, vertexOffset, firstInstance);
    }

    // Separate from retina$captureEntityDraw above: that one only records vanilla entity draws
    // for shadow replay, but the normals/specular fallback belongs on every substituted scene
    // pipeline (entities, particles, weather), not just the entity-shadow-casting subset.
    @Inject(method = "drawIndexed", at = @At("HEAD"))
    private void retina$bindScenePbrDefaults(int indexCount, int instanceCount, int firstIndex,
                                             int vertexOffset, int firstInstance, CallbackInfo ci) {
        if (!ShaderRuntime.isCapturingDrawState()) {
            return;
        }
        ShaderRuntime.get().bindScenePbrDefaults((RenderPass)(Object)this);
    }

    @ModifyArg(method = "setPipeline", index = 0, at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderPassBackend;setPipeline("
            + "Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private RenderPipeline retina$replaceEntityPipeline(RenderPipeline original) {
        RenderPipeline substituted = ShaderRuntime.get().scenePipelineFor(original);
        ShaderRuntime.get().trackScenePbrPass((RenderPass)(Object)this, substituted != original);
        return substituted;
    }
}
