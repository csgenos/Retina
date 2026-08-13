/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.mixin.minecraft;

import dev.retina.gui.ShaderPackScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds Retina's pack selector to the ordinary Video Settings list. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Inject(method = "addOptions", at = @At("RETURN"))
    private void retina$addShaderPackButton(CallbackInfo ci) {
        OptionsList list = ((OptionsSubScreenAccessor)this).retina$getOptionsList();
        if (list == null) {
            return;
        }
        Screen current = (Screen)(Object)this;
        list.addBig(Button.builder(Component.translatable("options.retina.shaderPacks"),
            button -> Minecraft.getInstance().setScreenAndShow(new ShaderPackScreen(current)))
            .width(310).build());
    }
}
