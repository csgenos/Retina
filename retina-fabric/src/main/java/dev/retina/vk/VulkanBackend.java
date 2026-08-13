/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.vk;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Retina's connection to the Vulkan device Minecraft already owns.
 *
 * <p>Minecraft 26.2's Blaze3D is backend-abstracted: {@code GpuDevice} is implemented by both
 * {@code com.mojang.blaze3d.opengl.GlDevice} and {@code com.mojang.blaze3d.vulkan.VulkanDevice},
 * and the game creates one of them at startup. Sodium 0.9.1 already relies on this — it
 * reaches through {@code VulkanRenderPass} to the raw {@code VkCommandBuffer} and patches the
 * pipeline layout to add its own push-constant range.
 *
 * <p>Retina therefore does <em>not</em> create a Vulkan instance, device, surface or
 * swapchain of its own. Doing so would mean two Vulkan devices contending for one window and
 * would make Sodium integration impossible, because Sodium's terrain buffers live on
 * Minecraft's device. Retina attaches to the existing device and records into the existing
 * command buffers. That is a genuine native Vulkan path — there is no OpenGL, no translation
 * layer, and no second presentation engine — but the instance is Minecraft's, not Retina's.
 *
 * <p>The consequence is that Retina cannot run when Minecraft chose the OpenGL backend.
 * That is checked here, once, and reported as a setting the user can change rather than as a
 * crash.
 *
 * <h2>Verification status</h2>
 * <p>The Blaze3D Vulkan class and method names used here were read out of the compiled
 * Sodium 0.9.1 jar's constant pool, which is authoritative for what Sodium calls. They have
 * <em>not</em> been compiled against Minecraft 26.2, because that requires artefacts this
 * build environment cannot reach. See {@code docs/ARCHITECTURE_AUDIT.md}.
 */
public final class VulkanBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("Retina/Vulkan");

    /** Why Retina cannot attach to the current backend, when it cannot. */
    public sealed interface Attachment {
        /** Retina is attached to Minecraft's Vulkan device. */
        record Attached(String deviceName, String driverInfo, String apiVersion)
            implements Attachment {
        }

        /** Minecraft created an OpenGL device, so Retina cannot run. */
        record WrongBackend(String backendName) implements Attachment {
            /** The message shown to the user. */
            public String message() {
                return "Retina renders through Minecraft's Vulkan backend, but this session"
                    + " started with the " + backendName + " backend.\n\n"
                    + "Switch Minecraft's graphics backend to Vulkan in Video Settings and"
                    + " restart the game. If Vulkan is not offered, this system's GPU driver"
                    + " does not expose a Vulkan 1.3 device that Minecraft accepts.";
            }
        }

        /** The backend could not be identified. */
        record Unknown(String detail) implements Attachment {
        }
    }

    private static volatile Attachment attachment = new Attachment.Unknown("not yet checked");

    private VulkanBackend() {
    }

    /**
     * Identifies the active Blaze3D backend.
     *
     * <p>Called once, after the device exists and before Retina touches any render state.
     * Identification is by class name rather than by {@code instanceof}, so that a build of
     * Retina running against a Minecraft version whose Vulkan classes moved reports "unknown
     * backend" instead of failing to load.
     */
    public static Attachment attach() {
        GpuDevice device;
        try {
            device = RenderSystem.getDevice();
        } catch (RuntimeException e) {
            attachment = new Attachment.Unknown(
                "RenderSystem.getDevice() failed: " + e.getMessage());
            return attachment;
        }
        if (device == null) {
            attachment = new Attachment.Unknown("no GpuDevice has been created yet");
            return attachment;
        }

        String className = device.getClass().getName();
        if (className.startsWith("com.mojang.blaze3d.opengl.")) {
            attachment = new Attachment.WrongBackend("OpenGL");
            LOGGER.error("Retina requires the Vulkan backend; Minecraft created {}", className);
            return attachment;
        }
        if (!className.startsWith("com.mojang.blaze3d.vulkan.")) {
            attachment = new Attachment.Unknown("unrecognised GpuDevice implementation "
                + className);
            LOGGER.error("Retina does not recognise the graphics backend {}", className);
            return attachment;
        }

        // `DeviceInfo.backendName()` and `DeviceInfo.features()` are the two accessors
        // Sodium 0.9.1 itself calls; using the same pair keeps Retina on the surface that is
        // known to exist rather than on one inferred from a newer or older Minecraft.
        DeviceInfo info;
        try {
            info = device.getDeviceInfo();
        } catch (RuntimeException e) {
            attachment = new Attachment.Unknown(
                "GpuDevice.getDeviceInfo() failed: " + e.getMessage());
            return attachment;
        }
        Attachment.Attached attached = new Attachment.Attached(
            className, describeFeatures(info), safe(info::backendName));
        attachment = attached;
        logStartupBanner(attached, device, info);
        return attached;
    }

    /**
     * Writes the device banner the startup log must contain.
     *
     * <p>This is the evidence a user or a bug report needs to establish which GPU was
     * selected and what Retina decided to use, and it is the first thing to check when a pack
     * behaves differently on two machines.
     */
    private static void logStartupBanner(Attachment.Attached attached, GpuDevice device,
                                         DeviceInfo info) {
        LOGGER.info("=== Retina Vulkan backend ===");
        LOGGER.info("  Blaze3D backend : {}", attached.apiVersion());
        LOGGER.info("  GpuDevice class : {}", attached.deviceName());
        LOGGER.info("  Device features : {}", attached.driverInfo());
        LOGGER.info("  Retina attaches to Minecraft's Vulkan device; it does not create its"
            + " own instance, device or swapchain.");
        LOGGER.info("=============================");
    }

    /** A one-line summary of the Blaze3D feature bits Retina and Sodium both depend on. */
    private static String describeFeatures(DeviceInfo info) {
        try {
            var features = info.features();
            return "multiDrawIndirect=" + features.multiDrawIndirect()
                + " multiDrawDirectInterleaved=" + features.multiDrawDirectInterleaved()
                + " persistentMapping=" + features.persistentMapping();
        } catch (RuntimeException e) {
            return "<unavailable: " + e.getClass().getSimpleName() + ">";
        }
    }

    private static String safe(java.util.function.Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null ? "<unknown>" : value;
        } catch (RuntimeException e) {
            return "<unavailable: " + e.getClass().getSimpleName() + ">";
        }
    }

    /** The current attachment state. */
    public static Attachment attachment() {
        return attachment;
    }

    /** Whether Retina is attached and may render. */
    public static boolean isAttached() {
        return attachment instanceof Attachment.Attached;
    }

    /** The attached device description, for the debug bundle. */
    public static Optional<Attachment.Attached> attached() {
        return attachment instanceof Attachment.Attached a ? Optional.of(a) : Optional.empty();
    }
}
