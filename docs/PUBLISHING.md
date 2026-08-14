# Publishing Retina

Retina is ready to be distributed as a Fabric client mod. Publishing remains a project-owner
action: build and inspect the artifact locally, then upload it through the Modrinth or CurseForge
web interface.

## Build the release artifact

From the repository root on Java 25:

```powershell
.\gradlew.bat :retina-fabric:build --no-daemon
```

Upload only `retina-fabric/build/libs/retina-<version>.jar`. Do not upload the `-sources.jar`.
The build runs `verifyReleaseJar`, which confirms that `fabric.mod.json`, the Retina icon, LGPL
notice, and bundled Retina core are present in the distributable jar.

## Continuous build artifacts

GitHub Actions runs this same release build for pull requests and pushes to
`main`. A successful workflow retains the publishable JAR as an Actions artifact
for 30 days. These artifacts are intended for testing and review; the workflow
does not publish a GitHub release, Modrinth file, or CurseForge file.

## Platform metadata

Use the following initial listing details:

| Field | Value |
| --- | --- |
| Project type | Mod |
| Loader | Fabric |
| Environment | Client-side |
| Game version | Minecraft 26.2 |
| Java | 25 |
| License | LGPL-3.0-only |
| Main file | `retina-0.1.0+mc26.2.jar` |
| Version type | Alpha or Beta; do not label the first release stable |

Declare Fabric API and Sodium 0.9.x as required dependencies. Retina requires Minecraft's Vulkan
graphics backend. It must not be installed alongside Iris, Sulkan, VulkanMod, Canvas, Embeddium,
or OptiFabric; the Fabric metadata rejects those combinations.

Use the repository's `CHANGELOG.md` entry as the release body, keeping the early-preview warning.
The mod jar includes its own square icon, but upload the same icon separately to the project page
if the platform asks for one.

## Before upload

1. Run the release build command above on a clean checkout.
2. Launch once with Vulkan enabled, Sodium, Fabric API, and the built-in
   `retina_lighting_reference` shader pack.
3. Confirm the title screen/world load and inspect `latest.log` for a successful Retina activation.
4. Check the jar name, Minecraft version, and dependency versions in the platform form.
5. Publish as an early preview with the known compatibility boundaries stated plainly.

Neither platform upload is automated from this repository. That keeps release credentials and the
final public description under the project owner's control.
