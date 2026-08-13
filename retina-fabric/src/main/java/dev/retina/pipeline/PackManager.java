/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.pipeline;

import dev.retina.core.option.OptionScanner;
import dev.retina.core.option.OptionSet;
import dev.retina.core.option.OptionValues;
import dev.retina.core.pack.PackDiscovery;
import dev.retina.core.pack.PackLoader;
import dev.retina.core.pack.PackPath;
import dev.retina.core.pack.PackSource;
import dev.retina.core.props.ShaderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Owns pack discovery, inspection, and the transactional swap between pipelines.
 *
 * <p>The important property here is transactionality. Enabling or reloading a pack builds a
 * candidate pipeline to completion — parse, preprocess, translate, compile, validate — and
 * only then replaces the active one. If any step fails, the previously working pipeline keeps
 * rendering and the failure is reported. There is no intermediate state in which the renderer
 * holds a half-built pipeline, which is what otherwise produces a black screen that survives
 * until the user restarts the game.
 */
public final class PackManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Retina/Packs");

    private final PackDiscovery discovery;
    private final Path configDirectory;
    private final Path cacheDirectory;

    public PackManager(Path shaderpacks, Path configDirectory, Path cacheDirectory) {
        this.discovery = new PackDiscovery(shaderpacks);
        this.configDirectory = configDirectory;
        this.cacheDirectory = cacheDirectory;
    }

    /** Creates the directories Retina expects, so the UI's "open folder" always works. */
    public void ensureDirectories() throws IOException {
        discovery.ensureDirectory();
        java.nio.file.Files.createDirectories(configDirectory);
        java.nio.file.Files.createDirectories(cacheDirectory);
    }

    /** The {@code shaderpacks/} directory. */
    public Path shaderpacksDirectory() {
        return discovery.directory();
    }

    /** An entry in the shader-pack selection list. */
    public record PackEntry(PackDiscovery.Candidate candidate, Status status, String detail) {
        /** Whether the pack looks loadable. */
        public enum Status {
            /** The pack has a shaders/ directory and can be selected. */
            READY,
            /** The pack was found but is not a valid shader pack. */
            INVALID,
            /** The pack could not be opened at all. */
            UNREADABLE
        }

        /** The name shown in the list. */
        public String displayName() {
            return candidate.name();
        }
    }

    /**
     * Lists the packs on disk with a cheap validity check.
     *
     * <p>Each pack is opened, its {@code shaders/} root resolved, and then closed again. That
     * is enough to tell "ready" from "not a shader pack" without preprocessing anything, so
     * the screen opens promptly even with a folder full of large archives.
     */
    public List<PackEntry> list() throws IOException {
        List<PackEntry> entries = new ArrayList<>();
        for (PackDiscovery.Candidate candidate : discovery.discover()) {
            try (PackSource source = PackDiscovery.open(candidate)) {
                PackLoader.Result result = PackLoader.resolve(source);
                switch (result) {
                    case PackLoader.Result.Ok ok -> entries.add(new PackEntry(candidate,
                        PackEntry.Status.READY, ok.shadersRoot().toAbsoluteString()));
                    case PackLoader.Result.Invalid invalid -> entries.add(new PackEntry(
                        candidate, PackEntry.Status.INVALID, invalid.reason()));
                }
            } catch (IOException | RuntimeException e) {
                // One unreadable pack must not hide the rest of the list.
                entries.add(new PackEntry(candidate, PackEntry.Status.UNREADABLE,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return entries;
    }

    /** Everything the option screen needs about one pack. */
    public record PackDetails(String name, String contentHash, ShaderProperties properties,
                              OptionSet options, List<String> diagnostics) {
    }

    /**
     * Reads a pack's properties and options without compiling anything.
     *
     * <p>Option discovery has to scan source files, so it walks the {@code shaders/} tree
     * rather than relying on {@code shaders.properties}, which only lays options out and does
     * not declare them.
     */
    public Optional<PackDetails> inspect(String packName) {
        Optional<PackDiscovery.Candidate> candidate = findCandidate(packName);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        try (PackSource source = PackDiscovery.open(candidate.get())) {
            PackLoader.Result resolved = PackLoader.resolve(source);
            if (!(resolved instanceof PackLoader.Result.Ok ok)) {
                return Optional.empty();
            }
            PackPath shadersRoot = ok.shadersRoot();

            ShaderProperties properties = source
                .readText(shadersRoot.resolve("shaders.properties"))
                .map(ShaderProperties::parse)
                .orElseGet(ShaderProperties::empty);

            OptionScanner scanner = new OptionScanner();
            scanSources(source, shadersRoot, scanner, 0);

            List<String> diagnostics = new ArrayList<>();
            diagnostics.addAll(properties.problems());
            diagnostics.addAll(properties.unknownDirectives());
            diagnostics.addAll(scanner.result().problems());

            return Optional.of(new PackDetails(candidate.get().name(), source.contentHash(),
                properties, OptionSet.from(scanner.result()), List.copyOf(diagnostics)));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not inspect shader pack {}: {}", packName, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Walks a pack's shader tree, scanning every source file for option declarations.
     *
     * <p>Depth-bounded: a pack with a pathological directory structure must not turn opening
     * the settings screen into an unbounded walk.
     */
    private void scanSources(PackSource source, PackPath directory, OptionScanner scanner,
                             int depth) throws IOException {
        if (depth > 16) {
            return;
        }
        for (PackPath child : source.list(directory)) {
            if (source.isDirectory(child)) {
                scanSources(source, child, scanner, depth + 1);
                continue;
            }
            if (!isShaderSource(child)) {
                continue;
            }
            Optional<String> text = source.readText(child);
            if (text.isPresent()) {
                scanner.scan(child, text.get().lines().toList());
            }
        }
    }

    private static boolean isShaderSource(PackPath path) {
        return switch (path.extension()) {
            case "vsh", "fsh", "gsh", "csh", "tcs", "tes", "glsl", "inc" -> true;
            default -> false;
        };
    }

    /** The per-pack option values file. */
    public Path optionsFileFor(String packName) {
        // The pack name is used as a file name, so it is sanitised: pack names come from
        // user-controlled file names and may contain separators.
        String safe = packName.replaceAll("[^A-Za-z0-9._-]", "_");
        return configDirectory.resolve("packs").resolve(safe + ".json");
    }

    /** The compiled-shader cache directory for a pack. */
    public Path cacheDirectoryFor(String contentHash) {
        return cacheDirectory.resolve(contentHash.substring(0, Math.min(16, contentHash.length())));
    }

    /** Loads the stored option values for a pack, dropping entries it no longer declares. */
    public OptionValues loadOptions(String packName, OptionSet declared) {
        Path file = optionsFileFor(packName);
        if (!java.nio.file.Files.isRegularFile(file)) {
            return OptionValues.defaults();
        }
        try {
            String json = java.nio.file.Files.readString(file);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> stored =
                new com.google.gson.Gson().fromJson(json, java.util.Map.class);
            return stored == null ? OptionValues.defaults() : OptionValues.of(stored, declared);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read options for {}: {}", packName, e.getMessage());
            return OptionValues.defaults();
        }
    }

    /** Saves option values for a pack. */
    public void saveOptions(String packName, OptionValues values) throws IOException {
        Path file = optionsFileFor(packName);
        java.nio.file.Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        java.nio.file.Files.writeString(temporary,
            new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(values.overrides()));
        java.nio.file.Files.move(temporary, file,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private Optional<PackDiscovery.Candidate> findCandidate(String packName) {
        try {
            return discovery.discover().stream()
                .filter(c -> c.name().equals(packName))
                .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
