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
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("retina", "icon");
    private static final Identifier PROFILE = Identifier.fromNamespaceAndPath("retina", "profile");
    private static final Identifier DEBUG_OVERLAY =
        Identifier.fromNamespaceAndPath("retina", "debug_overlay");
    private static final Identifier PARALLEL_COMPILATION =
        Identifier.fromNamespaceAndPath("retina", "parallel_compilation");

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
                        () -> RetinaClient.config().profile()))
                .addOption(builder.createBooleanOption(DEBUG_OVERLAY)
                    .setName(Component.translatable("retina.sodium.option.debugOverlay"))
                    .setTooltip(Component.translatable("retina.sodium.option.debugOverlay.tooltip"))
                    .setImpact(OptionImpact.LOW)
                    .setDefaultValue(false)
                    .setBinding(enabled -> RetinaClient.setConfig(
                            RetinaClient.config().withDebugOverlay(enabled)),
                        () -> RetinaClient.config().debugOverlay()))
                .addOption(builder.createBooleanOption(PARALLEL_COMPILATION)
                    .setName(Component.translatable("retina.sodium.option.parallelCompilation"))
                    .setTooltip(Component.translatable(
                        "retina.sodium.option.parallelCompilation.tooltip"))
                    .setImpact(OptionImpact.MEDIUM)
                    .setDefaultValue(true)
                    .setBinding(enabled -> RetinaClient.setConfig(
                            RetinaClient.config().withParallelCompilation(enabled)),
                        () -> RetinaClient.config().parallelCompilation()))));
    }
}
