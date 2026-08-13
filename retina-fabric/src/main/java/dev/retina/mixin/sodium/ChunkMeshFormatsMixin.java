/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.sodium;

import dev.retina.render.RetinaChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkMeshFormats.class, remap = false)
public abstract class ChunkMeshFormatsMixin {
    @Inject(method = "getCurrent", at = @At("HEAD"), cancellable = true)
    private static void retina$useNormalAwareFormat(CallbackInfoReturnable<ChunkVertexType> cir) {
        // Always use the extended stride. That makes pack toggles safe without rebuilding or
        // invalidating already-uploaded terrain meshes.
        cir.setReturnValue(RetinaChunkVertexType.INSTANCE);
    }
}
