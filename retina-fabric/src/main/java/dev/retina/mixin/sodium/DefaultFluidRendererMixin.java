/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = DefaultFluidRenderer.class, remap = false)
public abstract class DefaultFluidRendererMixin {
    @WrapOperation(
        method = "writeQuad",
        at = @At(value = "INVOKE", target =
            "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;"
                + "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                + "ChunkVertexEncoder$Vertex;Lnet/caffeinemc/mods/sodium/client/render/chunk/"
                + "terrain/material/Material;)V"))
    private void retina$carryFaceNormal(ChunkMeshBufferBuilder builder,
                                        ChunkVertexEncoder.Vertex[] vertices,
                                        Material material, Operation<Void> original,
                                        @Local(argsOnly = true) ModelQuadView quad,
                                        @Local(argsOnly = true) ModelQuadFacing facing,
                                        @Local(argsOnly = true) boolean flip) {
        int normal = facing.isAligned() ? facing.getPackedAlignedNormal() : quad.getFaceNormal();
        if (flip) {
            normal = NormI8.flipPacked(normal);
        }
        builder.push(vertices, material.bits() | normal << 8);
    }
}
