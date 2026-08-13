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

The shaders-off baseline is the same named Creative world with no selected Retina pack. Gate 1
will add a normal-lighting reference pack here; it should replace neither this diagnostic probe
nor the shaders-off baseline.
