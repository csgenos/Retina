# Publishing Retina

Retina is ready to be distributed as a Fabric client mod. GitHub Releases are published
automatically from a version tag (see below). Modrinth and CurseForge remain a project-owner
action: download the published release jar, then upload it through the Modrinth or CurseForge web
interface.

## Cutting a GitHub release

1. Update `gradle.properties` (`retina.version` and/or `retina.minecraft`) and `CHANGELOG.md` --
   rename `## Unreleased` to a version heading containing the new version string, e.g.
   `## 0.2.0+mc26.2`. Commit this on `main` first; the release workflow reads both files as they
   stand at the tagged commit.
2. Tag that commit with the version string exactly as it appears in `gradle.properties`:
   `retina.version` + `+mc` + `retina.minecraft`, e.g. `0.2.0+mc26.2` (no `v` prefix -- this
   matches the jar filename and the in-game version string, not a separate convention).
   ```
   git tag 0.2.0+mc26.2
   git push origin 0.2.0+mc26.2
   ```
3. `.github/workflows/release.yml` builds `:retina-fabric:build` (running the same
   `verifyReleaseJar` check as any other build), refuses to continue if the tag does not match the
   version computed from `gradle.properties`, and publishes a GitHub **pre-release** with the jar
   attached and the matching `CHANGELOG.md` section as the release body.
4. The release appears at `https://github.com/<owner>/<repo>/releases/tag/<tag>`. Download the jar
   from there for the Modrinth/CurseForge steps below -- do not rebuild locally, the published jar
   is already the exact tagged, verified artifact.

If step 1 is skipped -- the tag has no matching `## <version>` heading in `CHANGELOG.md` yet -- the
release still publishes, just with a generic note pointing at `CHANGELOG.md` instead of the real
section.

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
