/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin {
    @WrapOperation(
        method = "bufferQuad",
        at = @At(value = "INVOKE", target =
            "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;"
                + "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/"
                + "ChunkVertexEncoder$Vertex;I)V"))
    private void retina$carryFaceNormal(ChunkMeshBufferBuilder builder,
                                        ChunkVertexEncoder.Vertex[] vertices,
                                        int materialBits, Operation<Void> original,
                                        @Local(argsOnly = true) MutableQuadViewImpl quad) {
        int normal = quad.getFaceNormal();
        original.call(builder, vertices, materialBits | normal << 8);
    }
}
