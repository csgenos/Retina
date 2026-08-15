# Publishing Retina

Retina is ready to be distributed as a Fabric client mod. GitHub Releases are published
automatically from a version tag (see below). Modrinth and CurseForge remain a project-owner
action: download the published release jar, then upload it through the Modrinth or CurseForge web
interface.

## Cutting a GitHub release

Nothing needs editing first. Pick the next semantic version and tag `main`'s current tip with it
-- plain `major.minor.patch`, no `v` prefix, no `+mc...` suffix (the workflow adds that part):

```
git tag 0.2.0
git push origin 0.2.0
```

`.github/workflows/release.yml` takes it from there:

1. Reads `minecraft.version` from `gradle.properties` as-committed -- the property that actually
   controls what Loom compiled against and what `fabric.mod.json` declares as a dependency -- and
   combines it with the tag to get the full version, e.g. `0.2.0+mc26.2`. This is deliberately not
   configurable from the tag: a tag can't retroactively make already-committed code compatible
   with a different Minecraft version, so a release always ships for whatever is currently
   committed.
2. Builds `:retina-fabric:build` with that version substituted in (running the same
   `verifyReleaseJar` check as any other build), regardless of what `gradle.properties`'s own
   `retina.version`/`retina.minecraft` currently say.
3. Publishes a GitHub **pre-release** for the tag, with the jar attached, titled with the full
   version string.
4. For the release body: uses the `CHANGELOG.md` section already headed with the full version
   string if one exists (i.e. you renamed `## Unreleased` to `## 0.2.0+mc26.2` before tagging),
   otherwise falls back to whatever is currently under `## Unreleased`, otherwise a generic note
   pointing at `CHANGELOG.md`. Renaming `## Unreleased` before tagging isn't required, just gets
   you a release body scoped to this version specifically rather than everything accumulated
   since the last one.

The release appears at `https://github.com/<owner>/<repo>/releases/tag/<tag>`. Download the jar
from there for the Modrinth/CurseForge steps below -- do not rebuild locally, the published jar is
already the exact tagged, verified artifact.

## Continuous build artifacts

GitHub Actions also runs the same Fabric build for every pull request and every push to `main`,
independent of tags. A successful run retains the jar as an Actions artifact for 30 days --
useful for testing an in-progress change, but not a substitute for a tagged release: it isn't
version-checked against `gradle.properties` and isn't attached to anything permanent.

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

## Before uploading to Modrinth or CurseForge

1. Download the jar from the GitHub release created above. To test a build before tagging one,
   build it locally instead, from the repository root on Java 25:
   ```powershell
   .\gradlew.bat :retina-fabric:build --no-daemon
   ```
   Upload only `retina-fabric/build/libs/retina-<version>.jar`, never the `-sources.jar`.
2. Launch once with Vulkan enabled, Sodium, Fabric API, and the built-in
   `retina_lighting_reference` shader pack.
3. Confirm the title screen/world load and inspect `latest.log` for a successful Retina activation.
4. Check the jar name, Minecraft version, and dependency versions in the platform form.
5. Publish as an early preview with the known compatibility boundaries stated plainly.

Modrinth and CurseForge uploads are not automated from this repository. That keeps those
credentials and each platform's final public description under the project owner's control; the
GitHub release step above only needs the token GitHub Actions already provides for itself.
