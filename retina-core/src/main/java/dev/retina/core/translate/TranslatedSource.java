/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.translate;

import dev.retina.core.preprocess.PreprocessedSource;
import dev.retina.core.preprocess.SourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * Vulkan GLSL produced from pack source, with the mapping needed to blame errors correctly.
 *
 * <p>Translation injects a prologue after {@code #version} and can therefore shift every
 * subsequent line. {@code prologueLines} records that shift so a compiler diagnostic can be
 * walked all the way back: compiler line -> preprocessed line -> pack file and line.
 *
 * @param text the Vulkan GLSL handed to shaderc
 * @param prologueLines how many lines were injected after the version directive
 * @param prologueInsertedAt the 1-based preprocessed line the prologue was inserted after
 * @param bindings resources the program uses, with their assigned sets and bindings
 * @param fragmentOutputs colour attachment indices written, in fragment output order
 * @param warnings non-fatal translation notes shown in the pack diagnostics UI
 */
public record TranslatedSource(String text, int prologueLines, int prologueInsertedAt,
                               List<BindingLayout.Binding> bindings,
                               List<Integer> fragmentOutputs, List<String> warnings) {

    public TranslatedSource {
        bindings = List.copyOf(bindings);
        fragmentOutputs = List.copyOf(fragmentOutputs);
        warnings = List.copyOf(warnings);
    }

    /**
     * Maps a line in {@link #text()} back to the corresponding preprocessed line.
     *
     * @return empty when the line falls inside the injected prologue, which has no pack origin
     */
    public Optional<Integer> toPreprocessedLine(int translatedLine) {
        if (translatedLine <= prologueInsertedAt) {
            return Optional.of(translatedLine);
        }
        if (translatedLine <= prologueInsertedAt + prologueLines) {
            return Optional.empty();
        }
        return Optional.of(translatedLine - prologueLines);
    }

    /** Maps a line in {@link #text()} all the way back to a pack file and line. */
    public Optional<SourceLocation> locate(PreprocessedSource preprocessed, int translatedLine) {
        return toPreprocessedLine(translatedLine).flatMap(preprocessed::locate);
    }

    /**
     * Rewrites compiler diagnostics so every {@code <name>:<line>:} prefix names a pack file.
     *
     * <p>Lines that map into the injected prologue are annotated as such rather than left
     * pointing at a line number the pack author cannot find, because an error there is a
     * Retina bug and should read like one.
     */
    public String remapDiagnostics(PreprocessedSource preprocessed, String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 64);
        for (String line : raw.split("\n", -1)) {
            out.append(remapLine(preprocessed, line)).append('\n');
        }
        if (!out.isEmpty()) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private String remapLine(PreprocessedSource preprocessed, String line) {
        int firstColon = line.indexOf(':');
        if (firstColon < 0) {
            return line;
        }
        int secondColon = line.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            return line;
        }
        int translatedLine;
        try {
            translatedLine = Integer.parseInt(line.substring(firstColon + 1, secondColon).trim());
        } catch (NumberFormatException e) {
            return line;
        }
        Optional<Integer> preprocessedLine = toPreprocessedLine(translatedLine);
        if (preprocessedLine.isEmpty()) {
            return "<Retina generated prologue>" + line.substring(secondColon);
        }
        Optional<SourceLocation> location = preprocessed.locate(preprocessedLine.get());
        if (location.isEmpty()) {
            return line;
        }
        SourceLocation loc = location.get();
        return loc.file().toAbsoluteString() + ":" + loc.line() + line.substring(secondColon);
    }
}
