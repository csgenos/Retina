/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.blaze3d;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Temporarily points Minecraft's frame-graph main target at Retina's colortex0 texture. */
@Mixin(RenderTarget.class)
public interface RenderTargetColorAccessor {
    @Accessor("colorTexture")
    GpuTexture retina$colorTexture();

    @Mutable
    @Accessor("colorTexture")
    void retina$colorTexture(GpuTexture texture);

    @Accessor("colorTextureView")
    GpuTextureView retina$colorTextureView();

    @Mutable
    @Accessor("colorTextureView")
    void retina$colorTextureView(GpuTextureView view);
}
