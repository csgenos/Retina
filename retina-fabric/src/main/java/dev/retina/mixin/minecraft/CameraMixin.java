/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.minecraft;

import dev.retina.render.ShaderRuntime;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mirrors Minecraft's authoritative camera medium for Retina's full-scene composition. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "getFluidInCamera", at = @At("RETURN"))
    private void retina$captureFluidMedium(CallbackInfoReturnable<FogType> cir) {
        ShaderRuntime.get().setCameraMedium(cir.getReturnValue());
    }
}
