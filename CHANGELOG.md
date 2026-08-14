# Changelog

## 0.1.0+mc26.2 — prerelease

Initial public preview for Minecraft 26.2, Fabric, Sodium 0.9.x, Java 25, and Minecraft's Vulkan
backend.

- Legacy GLSL terrain translation with solid, cutout, and translucent passes.
- Colortex targets, prepare/deferred/composite/final chains, terrain shadows, and shadowcomp.
- Standard entity, particle, and weather shader paths with distance fog.
- Bundled `retina_lighting_reference` diagnostic shader pack.

This is an early preview, not Iris compatibility. Use the included reference pack for validation;
third-party shader packs may fail to load or render incorrectly.
