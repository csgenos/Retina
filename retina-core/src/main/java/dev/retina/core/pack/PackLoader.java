/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.pack;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Locates the {@code shaders/} root inside an opened pack and validates the pack's shape.
 *
 * <p>Packs in the wild are not consistently laid out. The three arrangements that actually
 * occur are handled here:
 *
 * <ol>
 *   <li>{@code shaders/} at the pack root — the documented layout;
 *   <li>{@code <single-directory>/shaders/} — the result of zipping a folder rather than its
 *       contents, which is what most users produce by accident;
 *   <li>the pack root <em>is</em> the shaders directory, i.e. {@code gbuffers_*.fsh} sit
 *       beside {@code shaders.properties} with no wrapper. This is not valid per the format
 *       and is reported as an error rather than silently accepted, because accepting it
 *       would make {@code /lib/...} include resolution ambiguous.
 * </ol>
 */
public final class PackLoader {
    private PackLoader() {
    }

    /** The outcome of resolving a pack's shaders root. */
    public sealed interface Result {
        /** The pack is usable; {@code shadersRoot} is where program sources live. */
        record Ok(PackPath shadersRoot) implements Result {
        }

        /** The pack is not usable; {@code reason} is shown to the user verbatim. */
        record Invalid(String reason) implements Result {
        }
    }

    /**
     * Resolves the {@code shaders/} directory for {@code source}.
     *
     * <p>Only one level of wrapper directory is searched. Deeper nesting is refused rather
     * than guessed at, because a pack containing several nested candidate directories has no
     * unambiguous answer and silently picking one produces a confusing half-working pack.
     */
    public static Result resolve(PackSource source) throws IOException {
        PackPath direct = PackPath.of("/shaders");
        if (source.isDirectory(direct)) {
            return new Result.Ok(direct);
        }

        List<PackPath> topLevel = source.list(PackPath.root());
        List<PackPath> wrappers = topLevel.stream()
            .filter(source::isDirectory)
            .filter(p -> source.isDirectory(p.resolve("shaders")))
            .toList();

        if (wrappers.size() == 1) {
            return new Result.Ok(wrappers.getFirst().resolve("shaders"));
        }
        if (wrappers.size() > 1) {
            return new Result.Invalid("The pack contains " + wrappers.size()
                + " directories that each hold a shaders/ folder, so Retina cannot tell which"
                + " one is the pack. Re-zip only the pack folder.");
        }

        if (source.exists(PackPath.of("/shaders.properties"))
            || !listPrograms(source, PackPath.root()).isEmpty()) {
            return new Result.Invalid("The pack's shader files sit at the archive root."
                + " A shader pack must contain a shaders/ directory; re-zip the pack so that"
                + " shaders/ is at the top level.");
        }

        return new Result.Invalid("No shaders/ directory was found in this pack.");
    }

    /** Program-source files directly under {@code directory}. */
    private static List<PackPath> listPrograms(PackSource source, PackPath directory)
        throws IOException {
        return source.list(directory).stream()
            .filter(p -> switch (p.extension()) {
                case "vsh", "fsh", "gsh", "csh", "tcs", "tes" -> true;
                default -> false;
            })
            .toList();
    }

    /**
     * Reads the optional {@code shaders/lang/<code>.lang} localisation file for the pack UI.
     *
     * @param languageCode a Minecraft language code such as {@code en_us}
     */
    public static Optional<String> readLanguageFile(PackSource source, PackPath shadersRoot,
                                                    String languageCode) throws IOException {
        PackPath path = shadersRoot.resolve("lang/" + languageCode + ".lang");
        return source.readText(path);
    }
}
