/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.minecraft;

import dev.retina.render.ShaderRuntime;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void retina$beginWorldFrame(CallbackInfo ci) {
        ShaderRuntime.get().beginWorldFrame();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void retina$renderPostChain(CallbackInfo ci) {
        ShaderRuntime.get().renderShadowPass();
        ShaderRuntime.get().restoreMainColorTarget();
        ShaderRuntime.get().renderPostChain();
    }

    @Inject(method = "endFrame", at = @At("RETURN"))
    private void retina$endFrame(CallbackInfo ci) {
        ShaderRuntime.get().endFrame();
    }
}
