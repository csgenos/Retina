/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.gui;

import dev.retina.RetinaClient;
import dev.retina.core.option.OptionValues;
import dev.retina.core.option.PackOption;
import dev.retina.pipeline.PackManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The per-pack settings screen.
 *
 * <p>Layout follows the pack's own {@code screen} directive when it has one, so a pack that
 * organised its options into pages and columns is presented the way its author intended.
 * A pack with no layout falls back to every option in declaration order, which is the only
 * ordering that is stable and meaningful without author input.
 *
 * <p>Changes are staged in a candidate {@link OptionValues} and written when the screen is
 * closed. Applying each keystroke would trigger a recompile per click.
 */
public final class ShaderOptionScreen extends Screen {
    private final Screen parent;
    private final PackManager.PackDetails details;
    private final List<String> layout;

    private OptionValues values;
    private int page;

    public ShaderOptionScreen(Screen parent, PackManager.PackDetails details) {
        super(Component.translatable("retina.screen.options.title", details.name()));
        this.parent = parent;
        this.details = details;
        this.values = RetinaClient.packManager().loadOptions(details.name(), details.options());
        this.layout = resolveLayout();
    }

    /**
     * The option names to show, in order.
     *
     * <p>{@code <empty>} entries in a pack's {@code screen} directive are layout spacers and
     * are kept, because removing them collapses a deliberately spaced two-column layout into
     * a jumble.
     */
    private List<String> resolveLayout() {
        List<String> declared = details.properties().screenLayout();
        if (!declared.isEmpty()) {
            return declared;
        }
        return new ArrayList<>(details.options().names());
    }

    @Override
    protected void init() {
        int columns = details.properties().screenColumns().getOrDefault("", 2);
        int columnWidth = 150;
        int startX = width / 2 - (columns * columnWidth) / 2;
        int y = 40;

        int index = 0;
        for (String name : layout) {
            if (name.equals("<empty>")) {
                index++;
                continue;
            }
            int column = index % columns;
            int rowY = y + (index / columns) * 22;
            if (rowY > height - 80) {
                break;
            }
            details.options().byName(stripBrackets(name)).ifPresent(option ->
                addRenderableWidget(buildControl(option, startX + column * columnWidth, rowY,
                    columnWidth - 4)));
            index++;
        }

        addRenderableWidget(Button.builder(
                Component.translatable("retina.screen.options.resetAll"),
                b -> {
                    values = OptionValues.defaults();
                    rebuildWidgets();
                })
            .bounds(width / 2 - 100, height - 52, 95, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(width / 2 + 5, height - 52, 95, 20).build());
    }

    private static String stripBrackets(String name) {
        // `[SUBSCREEN]` entries reference a sub-screen rather than an option.
        return name.startsWith("[") && name.endsWith("]")
            ? name.substring(1, name.length() - 1)
            : name;
    }

    /** Builds the control for one option: a toggle for booleans, a cycle for value options. */
    private Button buildControl(PackOption option, int x, int y, int controlWidth) {
        String current = values.valueOf(option);
        Component label = Component.literal(displayName(option) + ": " + current);
        return Button.builder(label, button -> {
            values = cycle(option);
            rebuildWidgets();
        }).bounds(x, y, controlWidth, 20).build();
    }

    /** The label shown for an option: its comment when it has one, otherwise its name. */
    private static String displayName(PackOption option) {
        return option.comment().orElse(option.name());
    }

    /** Advances an option to its next value, wrapping. */
    private OptionValues cycle(PackOption option) {
        if (option instanceof PackOption.BooleanOption toggle) {
            return values.with(toggle.name(),
                Boolean.toString(!values.isEnabled(toggle)));
        }
        if (option instanceof PackOption.ValueOption value) {
            List<String> allowed = value.allowedValues();
            int current = allowed.indexOf(values.valueOf(value));
            String next = allowed.get((current + 1 + allowed.size()) % allowed.size());
            return values.with(value.name(), next);
        }
        return values;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        // See ShaderPackScreen.extractRenderState: GuiGraphicsExtractor.text/centeredText
        // silently drops any draw whose color has alpha 0, which every bare 6-digit literal
        // does.
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        if (!details.diagnostics().isEmpty()) {
            graphics.centeredText(font,
                Component.translatable("retina.warning.unknownDirective",
                    details.diagnostics().size()),
                width / 2, height - 68, 0xFFFFAA00);
        }
    }

    @Override
    public void onClose() {
        try {
            RetinaClient.packManager().saveOptions(details.name(), values);
        } catch (IOException e) {
            // Losing a settings change is preferable to losing the session; the failure is
            // logged by the caller's handler and the screen still closes.
            org.slf4j.LoggerFactory.getLogger("Retina/Packs")
                .warn("Could not save options for {}: {}", details.name(), e.getMessage());
        }
        if (details.name().equals(RetinaClient.config().selectedPack())) {
            RetinaClient.reloadShaders();
        }
        minecraft.setScreenAndShow(parent);
    }
}
