/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.retina.core.target.RenderTargetDirectives;
import dev.retina.pipeline.PreparedTerrainPack;
import org.joml.Vector4f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Depth map and optional shadowcolor attachments for one terrain shadow pass. */
public final class ShadowFramebuffer implements AutoCloseable {
    private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING
        | GpuTexture.USAGE_RENDER_ATTACHMENT;

    private final PreparedTerrainPack.ShadowProgram plan;
    private final Map<Integer, GpuTexture> colors = new LinkedHashMap<>();
    private final Map<Integer, GpuTextureView> colorViews = new LinkedHashMap<>();
    private GpuTexture depth;
    private GpuTextureView depthView;
    private final GpuSampler comparisonSampler;

    public ShadowFramebuffer(PreparedTerrainPack.ShadowProgram plan) {
        this.plan = plan;
        this.comparisonSampler = ShadowSampler.create();
        allocate();
    }

    private void allocate() {
        int size = plan.resolution();
        depth = RenderSystem.getDevice().createTexture("Retina shadow depth", USAGE,
            GpuFormat.D32_FLOAT, size, size, 1, 1);
        depthView = RenderSystem.getDevice().createTextureView(depth);
        for (Map.Entry<Integer, RenderTargetDirectives.TargetSettings> entry
            : plan.colorTargets().entrySet()) {
            int index = entry.getKey();
            GpuTexture texture = RenderSystem.getDevice().createTexture("Retina shadowcolor"
                + index, USAGE, ColortexFramebuffer.gpuFormat(entry.getValue().format()),
                size, size, 1, 1);
            colors.put(index, texture);
            colorViews.put(index, RenderSystem.getDevice().createTextureView(texture));
        }
    }

    /** Clears depth for reversed-Z rendering and clear-enabled shadow colour outputs. */
    public void clear(CommandEncoder encoder) {
        encoder.clearDepthTexture(depth, 0.0f);
        for (Map.Entry<Integer, GpuTexture> entry : colors.entrySet()) {
            RenderTargetDirectives.TargetSettings settings = plan.colorTargets().get(entry.getKey());
            if (!settings.clear()) {
                continue;
            }
            float[] clear = settings.clearColorCopy();
            encoder.clearColorTexture(entry.getValue(), new Vector4f(clear[0], clear[1],
                clear[2], clear[3]));
        }
    }

    public GpuTextureView depthView() {
        return depthView;
    }

    public GpuTextureView colorView(int index) {
        GpuTextureView view = colorViews.get(index);
        if (view == null) {
            throw new IllegalArgumentException("shadowcolor" + index + " was not allocated");
        }
        return view;
    }

    public List<GpuTextureView> colorAttachments() {
        return plan.drawTargets().stream().map(colorViews::get).toList();
    }

    public int size() {
        return plan.resolution();
    }

    public GpuSampler comparisonSampler() {
        return comparisonSampler;
    }

    @Override
    public void close() {
        for (GpuTextureView view : colorViews.values()) {
            view.close();
        }
        colorViews.clear();
        for (GpuTexture texture : colors.values()) {
            texture.close();
        }
        colors.clear();
        if (depthView != null) {
            depthView.close();
            depthView = null;
        }
        if (depth != null) {
            depth.close();
            depth = null;
        }
        comparisonSampler.close();
    }
}
