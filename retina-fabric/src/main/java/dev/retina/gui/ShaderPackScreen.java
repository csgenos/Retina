/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.gui;

import dev.retina.RetinaClient;
import dev.retina.config.RetinaConfig;
import dev.retina.pipeline.PackManager;
import dev.retina.render.ShaderRuntime;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
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

        // Populates the list and derives button enablement/labels from it. Must run last:
        // it reads settingsButton and applyButton, which this method builds above. Screens
        // rebuild their whole widget tree on every window resize by calling init() again, so
        // this ordering has to hold on every call, not just the first.
        refresh();
    }

    private void refresh() {
        list.reload();
        updateButtons();
    }

    private void updateButtons() {
        boolean packSelected = selected != null && !selected.isEmpty();
        boolean same = java.util.Objects.equals(selected,
            RetinaClient.config().selectedPack());
        settingsButton.active = packSelected;
        applyButton.active = packSelected || !same;
        applyButton.setMessage(Component.translatable(same && packSelected
            ? "retina.screen.packs.reload" : "retina.screen.packs.apply"));
    }

    private void openSettings() {
        if (selected == null || selected.isEmpty()) {
            return;
        }
        packManager.inspect(selected).ifPresent(details ->
            minecraft.setScreenAndShow(new ShaderOptionScreen(this, details)));
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
        // Compilation is asynchronous. Let the renderer's live status replace any prior
        // pack-discovery error instead of claiming success before the GPU pipeline swaps.
        status = "";
        updateButtons();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFF);
        if (!status.isEmpty()) {
            graphics.centeredText(font, status, width / 2, height - 66, 0xA0A0A0);
        } else {
            ShaderRuntime.Status runtime = ShaderRuntime.get().status();
            int color = runtime.state() == ShaderRuntime.State.FAILED
                || runtime.state() == ShaderRuntime.State.WRONG_BACKEND ? 0xFF5555 : 0xA0A0A0;
            graphics.centeredText(font, runtime.detail(), width / 2, height - 66, color);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
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
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       boolean hovered, float partialTick) {
                String label = pack == null
                    ? Component.translatable("retina.screen.packs.off").getString()
                    : pack.displayName();
                int colour = pack == null || pack.status() == PackManager.PackEntry.Status.READY
                    ? 0xFFFFFF
                    : 0xFF5555;
                graphics.text(font, label, getContentX() + 4, getContentY() + 3, colour);
                if (pack != null && pack.status() != PackManager.PackEntry.Status.READY) {
                    graphics.text(font, pack.detail(), getContentX() + 4, getContentY() + 13,
                        0x808080);
                }
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                if (event.button() != 0) {
                    return false;
                }
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
