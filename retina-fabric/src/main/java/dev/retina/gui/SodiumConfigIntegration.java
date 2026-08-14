/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.gui;

import dev.retina.RetinaClient;
import dev.retina.RetinaVersion;
import dev.retina.config.RetinaConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.EnumSet;

/**
 * Registers Retina with Sodium's public configuration API.
 *
 * <p>Using Sodium's host screen gives Lunar Client and normal Fabric installs the exact same
 * navigation, search, apply/reset behavior, typography, and layout as the Sodium settings page
 * without copying Sodium's private GUI implementation.
 */
public final class SodiumConfigIntegration implements ConfigEntryPoint {
    // Sodium resolves this through Minecraft.getTextureManager().getTexture(Identifier), which
    // looks up the literal path with no "textures/" prefix or ".png" suffix added -- confirmed
    // against Sodium's own icon (Identifier.fromNamespaceAndPath("sodium",
    // "textures/gui/config-icon.png")). The bare "icon" this used before pointed at a resource
    // that doesn't exist, so this page has never actually shown an icon.
    private static final Identifier ICON =
        Identifier.fromNamespaceAndPath("retina", "textures/gui/icon.png");
    private static final Identifier PROFILE = Identifier.fromNamespaceAndPath("retina", "profile");

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        ModOptionsBuilder retina = builder.registerOwnModOptions()
            .setName("Retina")
            .setVersion(RetinaVersion.full())
            .setNonTintedIcon(ICON);

        // This is a native Sodium page entry. The pack browser remains Retina-specific because
        // it needs async compilation status, shaderpack discovery, and pack option scanning.
        retina.addPage(builder.createExternalPage()
            .setName(Component.translatable("retina.sodium.page.shaderPacks"))
            .setScreenConsumer(parent -> Minecraft.getInstance().setScreenAndShow(
                new ShaderPackScreen(parent))));

        // Only the profile selector appears here, because it is the only renderer-level setting
        // that currently changes what Retina does. A debug overlay and a parallel-compilation
        // pool are both wanted, and neither exists yet; listing a control for them would let a
        // user toggle it, see no change, and reasonably conclude Retina is broken.
        retina.addPage(builder.createOptionPage()
            .setName(Component.translatable("retina.sodium.page.renderer"))
            .addOptionGroup(builder.createOptionGroup()
                .setName(Component.translatable("retina.sodium.group.renderer"))
                .addOption(builder.createEnumOption(PROFILE, RetinaConfig.RendererProfile.class)
                    .setName(Component.translatable("retina.sodium.option.profile"))
                    .setTooltip(Component.translatable("retina.sodium.option.profile.tooltip"))
                    .setImpact(OptionImpact.MEDIUM)
                    .setDefaultValue(RetinaConfig.RendererProfile.BALANCED)
                    .setAllowedValues(EnumSet.allOf(RetinaConfig.RendererProfile.class))
                    .setElementNameProvider(profile -> Component.translatable(
                        "retina.sodium.profile." + profile.name().toLowerCase(java.util.Locale.ROOT)))
                    .setBinding(profile -> RetinaClient.setConfig(
                            RetinaClient.config().withProfile(profile)),
                        () -> RetinaClient.config().profile())
                    // Every stateful Sodium option requires a storage handler, called once its
                    // binding has been saved. RetinaConfig.save() already runs synchronously
                    // inside setConfig() above, so there is nothing left to flush here.
                    .setStorageHandler(() -> { }))));
    }
}
