/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.retina.render.ShaderRuntime;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class DefaultChunkRendererMixin {
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
                + "Ljava/util/function/Supplier;"
                + "Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;"
                + "Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)"
                + "Lcom/mojang/blaze3d/systems/RenderPass;"))
    private RenderPass retina$attachTerrainMrt(CommandEncoder encoder, Supplier<String> label,
                                               GpuTextureView color,
                                               Optional<org.joml.Vector4fc> colorClear,
                                               GpuTextureView depth,
                                               OptionalDouble depthClear,
                                               Operation<RenderPass> original,
                                               @Local(argsOnly = true)
                                               net.caffeinemc.mods.sodium.client.render.chunk
                                                   .terrain.TerrainRenderPass terrainPass) {
        return ShaderRuntime.get().createTerrainRenderPass(encoder, label, color, colorClear,
            depth, depthClear, terrainPass);
    }

    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/systems/RenderPass;setUniform(Ljava/lang/String;"
                + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
    private void retina$bindTerrainUniforms(RenderPass pass, String name, GpuBufferSlice value,
                                            Operation<Void> original,
                                            @Local(argsOnly = true) ChunkRenderMatrices matrices,
                                            @Local(argsOnly = true) CameraTransform camera,
                                            @Local(argsOnly = true) FogParameters fog,
                                            @Local(argsOnly = true) GpuSampler sampler,
                                            @Local(argsOnly = true)
                                            net.caffeinemc.mods.sodium.client.render.chunk
                                                .terrain.TerrainRenderPass terrainPass) {
        original.call(pass, name, value);
        GpuBufferSlice retina = ShaderRuntime.get().prepareUniforms(matrices, camera, fog);
        if (retina != null) {
            pass.setUniform("RetinaUniforms", retina);
            // Terrain's pipeline layout (built in ShaderRuntime.buildPipeline) always includes
            // RETINA_PBR_SAMPLERS, so this is safe unconditionally -- unlike the generic scene
            // path in RenderPassMixin, there is no non-Retina pipeline this could be bound onto.
            ShaderRuntime.get().bindPbrDefaults(pass);
        }
        ShaderRuntime.get().captureTerrainInvocation(matrices, camera, fog, sampler, terrainPass);
    }
}
