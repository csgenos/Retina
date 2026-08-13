/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.preprocess;

import dev.retina.core.pack.MemoryPackSource;
import dev.retina.core.pack.PackPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PreprocessorTest {

    private static final PackPath SHADERS = PackPath.of("/shaders");

    private static ShaderPreprocessor preprocessorFor(MemoryPackSource source) {
        return new ShaderPreprocessor(source, SHADERS);
    }

    @Test
    @DisplayName("relative and root-absolute includes both resolve")
    void includesResolve() throws IOException {
        MemoryPackSource pack = new MemoryPackSource("p")
            .put("/shaders/composite.fsh", """
                #version 120
                #include "lib/local.glsl"
                #include "/lib/shared.glsl"
                void main() {}
                """)
            .put("/shaders/lib/local.glsl", "float local() { return 1.0; }")
            .put("/shaders/lib/shared.glsl", "float shared_() { return 2.0; }");

        PreprocessedSource result = preprocessorFor(pack)
            .process(PackPath.of("/shaders/composite.fsh"), Map.of());

        assertTrue(result.text().contains("float local()"));
        assertTrue(result.text().contains("float shared_()"));
        assertEquals(3, result.includedFiles().size(),
            () -> "expected the root plus two includes, got " + result.includedFiles());
    }

    @Test
    @DisplayName("an include cycle is reported with the full path, not a stack overflow")
    void includeCycleIsReported() {
        MemoryPackSource pack = new MemoryPackSource("p")
            .put("/shaders/composite.fsh", "#version 120\n#include \"a.glsl\"\n")
            .put("/shaders/a.glsl", "#include \"b.glsl\"\n")
            .put("/shaders/b.glsl", "#include \"a.glsl\"\n");

        ShaderPreprocessor.PreprocessException failure =
            assertThrows(ShaderPreprocessor.PreprocessException.class,
                () -> preprocessorFor(pack).process(PackPath.of("/shaders/composite.fsh"),
                    Map.of()));
        assertTrue(failure.getMessage().contains("cycle"), failure.getMessage());
        assertTrue(failure.getMessage().contains("/shaders/a.glsl"), failure.getMessage());
    }

    @Test
    @DisplayName("an include that escapes the pack is refused")
    void includeTraversalIsRefused() {
        MemoryPackSource pack = new MemoryPackSource("p")
            .put("/shaders/composite.fsh",
                "#version 120\n#include \"../../../../etc/passwd\"\n");

        ShaderPreprocessor.PreprocessException failure =
            assertThrows(ShaderPreprocessor.PreprocessException.class,
                () -> preprocessorFor(pack).process(PackPath.of("/shaders/composite.fsh"),
                    Map.of()));
        assertTrue(failure.getMessage().contains("escapes the pack root"),
            failure.getMessage());
    }

    @Test
    @DisplayName("a missing include names the file and the resolved path")
    void missingIncludeIsPrecise() {
        MemoryPackSource pack = new MemoryPackSource("p")
            .put("/shaders/composite.fsh", "#version 120\n#include \"lib/absent.glsl\"\n");

        ShaderPreprocessor.PreprocessException failure =
            assertThrows(ShaderPreprocessor.PreprocessException.class,
                () -> preprocessorFor(pack).process(PackPath.of("/shaders/composite.fsh"),
                    Map.of()));
        assertTrue(failure.getMessage().contains("/shaders/lib/absent.glsl"),
            failure.getMessage());
        assertTrue(failure.getMessage().contains("/shaders/composite.fsh:2"),
            () -> "the failure should name the including file and line: "
                + failure.getMessage());
    }

    @Test
    @DisplayName("every output line maps back to its original file and line")
    void lineMappingIsExact() throws IOException {
        MemoryPackSource pack = new MemoryPackSource("p")
            .put("/shaders/composite.fsh", """
                #version 120
                // root line 2
                #include "lib/a.glsl"
                // root line 4
                void main() {}
                """)
            .put("/shaders/lib/a.glsl", """
                // a line 1
                // a line 2
                #include "b.glsl"
                // a line 4
                """)
            .put("/shaders/lib/b.glsl", "// b line 1\n");

        PreprocessedSource result = preprocessorFor(pack)
            .process(PackPath.of("/shaders/composite.fsh"), Map.of());

        String[] lines = result.text().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i];
            Optional<SourceLocation> location = result.locate(i + 1);
            if (!text.startsWith("// root line") && !text.startsWith("// a line")
                && !text.startsWith("// b line")) {
                continue;
            }
            assertTrue(location.isPresent(), "line " + (i + 1) + " (" + text + ") has no origin");
            SourceLocation loc = location.get();
            String expectedFile = text.startsWith("// root") ? "/shaders/composite.fsh"
                : text.startsWith("// a") ? "/shaders/lib/a.glsl" : "/shaders/lib/b.glsl";
            int expectedLine = Integer.parseInt(text.replaceAll("\\D+", ""));
            assertEquals(expectedFile, loc.file().toAbsoluteString(),
                "wrong file for output line " + (i + 1));
            assertEquals(expectedLine, loc.line(),
                "wrong line for output line " + (i + 1) + ": " + text);
        }
    }

    @Test
    @DisplayName("the include chain is recorded so nested errors can be traced")
    void includeChainIsRecorded() throws IOException {
        MemoryPackSource pack = new MemoryPackSource("p")
            .put("/shaders/composite.fsh", "#version 120\n#include \"a.glsl\"\n")
            .put("/shaders/a.glsl", "#include \"b.glsl\"\n")
            .put("/shaders/b.glsl", "// deep\n");

        PreprocessedSource result = preprocessorFor(pack)
            .process(PackPath.of("/shaders/composite.fsh"), Map.of());

        int deepLine = -1;
        String[] lines = result.text().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals("// deep")) {
                deepLine = i + 1;
            }
        }
        assertTrue(deepLine > 0, "the deep include was not expanded");
        SourceLocation location = result.locate(deepLine).orElseThrow();
        assertEquals("/shaders/b.glsl", location.file().toAbsoluteString());
        assertEquals(2, location.includeChain().size(),
            () -> "expected composite -> a in the chain, got " + location.includeChain());
        assertTrue(location.describe().contains("included from"), location.describe());
    }

    @Test
    @DisplayName("diagnostics from the compiler are rewritten to name pack files")
    void diagnosticsAreRemapped() throws IOException {
        MemoryPackSource pack = new MemoryPackSource("p")
            .put("/shaders/composite.fsh", "#version 120\n#include \"a.glsl\"\nvoid main(){}\n")
            .put("/shaders/a.glsl", "// one\n// two\n// three\n");

        PreprocessedSource result = preprocessorFor(pack)
            .process(PackPath.of("/shaders/composite.fsh"), Map.of());

        int line = -1;
        String[] lines = result.text().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals("// two")) {
                line = i + 1;
            }
        }
        String remapped = result.remapDiagnostics(
            "composite.fsh:" + line + ": error: something went wrong");
        assertEquals("/shaders/a.glsl:2: error: something went wrong", remapped);
    }

    @Test
    @DisplayName("defines are injected after #version, never before it")
    void definesGoAfterVersion() throws IOException {
        MemoryPackSource pack = new MemoryPackSource("p")
            .put("/shaders/composite.fsh", "// leading comment\n#version 120\nvoid main(){}\n");

        Map<String, String> defines = new LinkedHashMap<>();
        defines.put("RETINA", "1");
        defines.put("MC_VERSION", "26020");

        PreprocessedSource result = preprocessorFor(pack)
            .process(PackPath.of("/shaders/composite.fsh"), defines);

        int versionAt = result.text().indexOf("#version 120");
        int defineAt = result.text().indexOf("#define RETINA 1");
        assertTrue(versionAt >= 0 && defineAt > versionAt,
            () -> "defines must follow #version:\n" + result.text());
        assertTrue(result.text().contains("#define MC_VERSION 26020"));
    }

    @Test
    @DisplayName("#include inside a comment or as part of a longer word is not a directive")
    void includeDetectionIsPrecise() {
        assertTrue(ShaderPreprocessor.parseInclude("#include \"a.glsl\"").isPresent());
        assertTrue(ShaderPreprocessor.parseInclude("  #  include <a.glsl>").isPresent());
        assertTrue(ShaderPreprocessor.parseInclude("#include a.glsl").isPresent());
        assertTrue(ShaderPreprocessor.parseInclude("#includeme \"a\"").isEmpty());
        assertTrue(ShaderPreprocessor.parseInclude("// #include \"a.glsl\"").isEmpty());
        assertTrue(ShaderPreprocessor.parseInclude("int includes = 1;").isEmpty());
    }
}
