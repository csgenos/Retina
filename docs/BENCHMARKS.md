# Retina benchmark protocol

This protocol measures regressions, not a claim that Retina is faster than another renderer.
Every release gate records a new row using the same environment and scene. Do not compare a
different resolution, render distance, driver, or camera position with a previous row.

## Fixed scene

- World: `shaderer test` (Creative), fullscreen at the display's native resolution.
- Camera: stand at `26.5 63.0 40.5`, facing the birch shoreline across the water.
- Commands before each run:

```mcfunction
/gamemode creative
/time set noon
/weather clear
/gamerule doDaylightCycle false
/gamerule doWeatherCycle false
/tp @s 26.5 63 40.5 180 0
```

Wait 30 seconds for chunks and shaders to settle. Do not move the camera during a sample.

## Runs

Record three 60-second samples using the debug overlay or a frame-time capture tool:

1. `shaders-off`: Retina shaders disabled.
2. `shadow-probe`: select `retina_shadow_probe` after running
   `./gradlew :retina-fabric:syncAcceptancePacks`.
3. The gate's new reference pack or feature combination.

For every sample record average FPS, 1% low FPS, median frame time, 1% high frame time, render
distance, simulation distance, resolution, GPU/driver, Java, Minecraft/Fabric/Sodium versions,
and the exact commit. Attach a screenshot showing the scene and active feature state.

## Results

| Date | Commit | Configuration | Avg FPS | 1% low FPS | Median ms | 1% high ms | Notes |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- |
| 2026-08-13 | `97cfcfa` | Initial live probes | not captured | not captured | not captured | not captured | Establishes the protocol; do not infer performance. |

## Required validation

Each renderer gate also runs `./gradlew build`, repeats the active-pack reload, resizes the
window once, and checks `latest.log` for Retina, pipeline, Mixin, and Vulkan errors. Update
`README.md` and `HANDOFF.md` with the feature status and compatibility boundary before closing
the gate.
