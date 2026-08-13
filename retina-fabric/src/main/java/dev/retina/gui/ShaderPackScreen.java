/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.gui;

import dev.retina.RetinaClient;
import dev.retina.config.RetinaConfig;
import dev.retina.pipeline.PackManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The shader-pack selection screen.
 *
 * <p>Selecting a pack in the list does not apply it. Applying is an explicit action, because
 * building a pipeline takes time and because a pack that fails should leave the user looking
 * at a working game with an error message, not at whatever a half-applied pack produces.
 *
 * <p>{@code Shaders: Off} is always the first entry and is the default. It is a real entry
 * rather than a "none" placeholder so that turning shaders off is the same single click as
 * turning them on.
 */
public final class ShaderPackScreen extends Screen {
    private final Screen parent;
    private final PackManager packManager;

    private PackList list;
    private EditBox search;
    private Button applyButton;
    private Button settingsButton;
    private String selected;
    private String status = "";

    public ShaderPackScreen(Screen parent) {
        super(Component.translatable("retina.screen.packs.title"));
        this.parent = parent;
        this.packManager = RetinaClient.packManager();
        this.selected = RetinaClient.config().selectedPack();
    }

    @Override
    protected void init() {
        search = new EditBox(font, width / 2 - 150, 28, 300, 20,
            Component.translatable("retina.screen.packs.search"));
        search.setHint(Component.translatable("retina.screen.packs.search"));
        search.setResponder(text -> list.filter(text));
        addRenderableWidget(search);

        list = new PackList(width, height - 56 - 60, 56, 24);
        addRenderableWidget(list);
        refresh();

        int row = height - 52;
        addRenderableWidget(Button.builder(
                Component.translatable("retina.screen.packs.openFolder"),
                b -> Util.getPlatform().openPath(packManager.shaderpacksDirectory()))
            .bounds(width / 2 - 205, row, 100, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("retina.screen.packs.refresh"), b -> refresh())
            .bounds(width / 2 - 100, row, 100, 20).build());
        settingsButton = addRenderableWidget(Button.builder(
                Component.translatable("retina.screen.packs.settings"), b -> openSettings())
            .bounds(width / 2 + 5, row, 100, 20).build());
        applyButton = addRenderableWidget(Button.builder(
                Component.translatable("retina.screen.packs.apply"), b -> apply())
            .bounds(width / 2 + 110, row, 95, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                b -> onClose())
            .bounds(width / 2 - 100, height - 28, 200, 20).build());

        updateButtons();
    }

    private void refresh() {
        list.reload();
        updateButtons();
    }

    private void updateButtons() {
        boolean packSelected = selected != null && !selected.isEmpty();
        settingsButton.active = packSelected;
        applyButton.active = !java.util.Objects.equals(
            selected, RetinaClient.config().selectedPack());
    }

    private void openSettings() {
        if (selected == null || selected.isEmpty()) {
            return;
        }
        packManager.inspect(selected).ifPresent(details ->
            minecraft.setScreen(new ShaderOptionScreen(this, details)));
    }

    /**
     * Applies the selected pack.
     *
     * <p>Only the configuration is written here. Building the candidate pipeline and swapping
     * it in at a safe frame boundary is the renderer's job; doing it on the UI thread would
     * freeze the screen for the duration of the compile.
     */
    private void apply() {
        RetinaConfig next = RetinaClient.config().withSelectedPack(
            selected == null ? RetinaConfig.SHADERS_OFF : selected);
        RetinaClient.setConfig(next);
        status = next.shadersEnabled()
            ? Component.translatable("retina.status.applied", next.selectedPack()).getString()
            : Component.translatable("retina.status.disabled").getString();
        updateButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        if (!status.isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, height - 66, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** The scrollable list of packs, with {@code Shaders: Off} pinned first. */
    private final class PackList extends ObjectSelectionList<PackList.Entry> {
        private List<PackManager.PackEntry> discovered = List.of();

        PackList(int width, int height, int top, int itemHeight) {
            super(ShaderPackScreen.this.minecraft, width, height, top, itemHeight);
        }

        void reload() {
            try {
                discovered = packManager.list();
            } catch (IOException e) {
                discovered = List.of();
                status = "Could not read shaderpacks/: " + e.getMessage();
            }
            filter(search == null ? "" : search.getValue());
        }

        void filter(String query) {
            clearEntries();
            String needle = query.toLowerCase(Locale.ROOT).trim();
            addEntry(new Entry(null));
            List<PackManager.PackEntry> matching = new ArrayList<>();
            for (PackManager.PackEntry entry : discovered) {
                if (needle.isEmpty()
                    || entry.displayName().toLowerCase(Locale.ROOT).contains(needle)) {
                    matching.add(entry);
                }
            }
            matching.forEach(entry -> addEntry(new Entry(entry)));
        }

        /** One row: {@code null} means {@code Shaders: Off}. */
        final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final PackManager.PackEntry pack;

            Entry(PackManager.PackEntry pack) {
                this.pack = pack;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth,
                               int entryHeight, int mouseX, int mouseY, boolean hovered,
                               float partialTick) {
                String label = pack == null
                    ? Component.translatable("retina.screen.packs.off").getString()
                    : pack.displayName();
                int colour = pack == null || pack.status() == PackManager.PackEntry.Status.READY
                    ? 0xFFFFFF
                    : 0xFF5555;
                graphics.drawString(font, label, x + 4, y + 3, colour);
                if (pack != null && pack.status() != PackManager.PackEntry.Status.READY) {
                    graphics.drawString(font, pack.detail(), x + 4, y + 13, 0x808080);
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                setSelected(this);
                selected = pack == null ? RetinaConfig.SHADERS_OFF : pack.displayName();
                updateButtons();
                return true;
            }

            @Override
            public Component getNarration() {
                return pack == null
                    ? Component.translatable("retina.screen.packs.off")
                    : Component.literal(pack.displayName());
            }
        }
    }
}
