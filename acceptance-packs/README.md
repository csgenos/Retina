# Retina acceptance packs

These source packs are deliberately small renderer probes. They are not visual-quality shader
packs and must not be presented as such. Run the explicit sync task before a live validation:

```powershell
.\gradlew.bat :retina-fabric:syncAcceptancePacks
```

The task copies them to the ignored `retina-fabric/run/shaderpacks/` directory used by the
Fabric development client.

- `retina_shadow_probe`: terrain MRT plus the D32 terrain shadow-map path; its intentionally
  darkened shadow receiver makes shadow lookup visible.
- `retina_lighting_reference`: a playable baseline using terrain/entity lightmaps, distance
  fog, day-cycle sun tint, and terrain-shadow composition. It intentionally does not include
  AO, advanced water, clouds, bloom, exposure, or anti-aliasing; those remain later gates.

The shaders-off baseline is the same named Creative world with no selected Retina pack. The
reference pack must not replace either that baseline or the diagnostic probe.
