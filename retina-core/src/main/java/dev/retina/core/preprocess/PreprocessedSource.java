/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.preprocess;

import dev.retina.core.pack.PackPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fully include-expanded shader source plus the map back to original pack files.
 *
 * <p>The map is what makes shader errors usable. shaderc reports a line number in the text
 * it was given; without this map that number points into a 12,000-line expansion of eleven
 * includes and is worse than useless to a pack author.
 *
 * <p>The map is stored as a sorted run list rather than one entry per line: an include
 * expansion produces long contiguous runs from the same file, so the run form is a few dozen
 * entries for a source that would otherwise need thousands.
 */
public final class PreprocessedSource {
    /**
     * A contiguous run of output lines originating from one file.
     *
     * @param firstOutputLine 1-based first line of the run in the expanded source
     * @param lineCount number of lines in the run
     * @param file the pack file the run came from
     * @param firstSourceLine 1-based line in {@code file} corresponding to
     *                        {@code firstOutputLine}
     * @param includeChain files that led to {@code file}, outermost first
     */
    public record Run(int firstOutputLine, int lineCount, PackPath file, int firstSourceLine,
                      List<PackPath> includeChain) {
        public Run {
            includeChain = List.copyOf(includeChain);
        }

        boolean covers(int outputLine) {
            return outputLine >= firstOutputLine && outputLine < firstOutputLine + lineCount;
        }
    }

    private final String text;
    private final List<Run> runs;
    private final List<PackPath> includedFiles;

    PreprocessedSource(String text, List<Run> runs, List<PackPath> includedFiles) {
        this.text = text;
        this.runs = List.copyOf(runs);
        this.includedFiles = List.copyOf(includedFiles);
    }

    /** The expanded GLSL. */
    public String text() {
        return text;
    }

    /** The line runs, ordered by output line. */
    public List<Run> runs() {
        return runs;
    }

    /**
     * Every pack file that contributed to this source, including the root.
     *
     * <p>Used as a cache-key input and to decide which files a reload must re-read.
     */
    public List<PackPath> includedFiles() {
        return includedFiles;
    }

    /**
     * Maps a 1-based line in {@link #text()} back to its origin.
     *
     * @return empty when the line is outside the source or was synthesised by Retina
     */
    public Optional<SourceLocation> locate(int outputLine) {
        // Binary search over the runs; they are contiguous and sorted by construction.
        int lo = 0;
        int hi = runs.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Run run = runs.get(mid);
            if (run.covers(outputLine)) {
                int offset = outputLine - run.firstOutputLine();
                return Optional.of(new SourceLocation(run.file(),
                    run.firstSourceLine() + offset, run.includeChain()));
            }
            if (outputLine < run.firstOutputLine()) {
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return Optional.empty();
    }

    /**
     * Rewrites a compiler diagnostic so its line numbers point at pack files.
     *
     * <p>shaderc emits {@code <name>:<line>: <message>}. Each such prefix is replaced with
     * the mapped original location. Lines that cannot be mapped are left untouched rather
     * than guessed at, so a synthesised-prologue error still reads correctly.
     */
    public String remapDiagnostics(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 64);
        for (String line : raw.split("\n", -1)) {
            out.append(remapLine(line)).append('\n');
        }
        if (out.length() > 0) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private String remapLine(String line) {
        // Match "<anything>:<digits>:" at the start, which is what shaderc/glslang produce.
        int firstColon = line.indexOf(':');
        if (firstColon < 0) {
            return line;
        }
        int secondColon = line.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            return line;
        }
        String digits = line.substring(firstColon + 1, secondColon).trim();
        int outputLine;
        try {
            outputLine = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return line;
        }
        Optional<SourceLocation> location = locate(outputLine);
        if (location.isEmpty()) {
            return line;
        }
        SourceLocation loc = location.get();
        return loc.file().toAbsoluteString() + ":" + loc.line() + line.substring(secondColon);
    }

    /** Builder used by {@link ShaderPreprocessor}. */
    static final class Builder {
        private final StringBuilder text = new StringBuilder();
        private final List<Run> runs = new ArrayList<>();
        private int outputLine = 1;
        private Run open;

        void append(String lineText, PackPath file, int sourceLine, List<PackPath> chain) {
            text.append(lineText).append('\n');
            if (open != null && open.file().equals(file)
                && open.firstSourceLine() + open.lineCount() == sourceLine
                && open.includeChain().equals(chain)) {
                open = new Run(open.firstOutputLine(), open.lineCount() + 1, file,
                    open.firstSourceLine(), chain);
            } else {
                flush();
                open = new Run(outputLine, 1, file, sourceLine, chain);
            }
            outputLine++;
        }

        /** Appends a line Retina generated, which maps to no pack file. */
        void appendSynthetic(String lineText) {
            text.append(lineText).append('\n');
            flush();
            outputLine++;
        }

        private void flush() {
            if (open != null) {
                runs.add(open);
                open = null;
            }
        }

        PreprocessedSource build(List<PackPath> includedFiles) {
            flush();
            return new PreprocessedSource(text.toString(), runs, includedFiles);
        }
    }
}
