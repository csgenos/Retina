/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.option;

import dev.retina.core.pack.PackPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OptionSystemTest {

    private static final PackPath FILE = PackPath.of("/shaders/lib/settings.glsl");

    private static OptionSet scan(String... lines) {
        OptionScanner scanner = new OptionScanner();
        scanner.scan(FILE, List.of(lines));
        return OptionSet.from(scanner.result());
    }

    @Test
    @DisplayName("a bare #define is a toggle, and commenting it out flips the default")
    void toggles() {
        OptionSet on = scan("#define SHADOWS");
        PackOption.BooleanOption enabled =
            assertInstanceOf(PackOption.BooleanOption.class, on.byName("SHADOWS").orElseThrow());
        assertTrue(enabled.defaultEnabled());

        OptionSet off = scan("//#define SHADOWS");
        PackOption.BooleanOption disabled =
            assertInstanceOf(PackOption.BooleanOption.class, off.byName("SHADOWS").orElseThrow());
        assertFalse(disabled.defaultEnabled());
    }

    @Test
    @DisplayName("a valued #define is only an option when it carries a [...] annotation")
    void annotatedValuesOnly() {
        assertTrue(scan("#define INTERNAL_CONSTANT 4").isEmpty(),
            "an unannotated valued define is internal, not a user option");

        OptionSet annotated = scan("#define QUALITY 2 // [1 2 3 4] Shadow quality");
        PackOption.ValueOption option = assertInstanceOf(PackOption.ValueOption.class,
            annotated.byName("QUALITY").orElseThrow());
        assertEquals(List.of("1", "2", "3", "4"), option.allowedValues());
        assertEquals("2", option.defaultValueText());
        assertEquals("Shadow quality", option.comment().orElseThrow());
        assertEquals(PackOption.ValueType.INT, option.type());
        assertTrue(option.sliderCapable());
    }

    @Test
    @DisplayName("a const declaration is an option and keeps its literal text exactly")
    void constOptions() {
        OptionSet set = scan("const int shadowMapResolution = 2048; // [512 1024 2048 4096]");
        PackOption.ValueOption option = assertInstanceOf(PackOption.ValueOption.class,
            set.byName("shadowMapResolution").orElseThrow());
        assertEquals("2048", option.defaultValueText());
        assertEquals(PackOption.Declaration.Kind.CONST, option.declaration().kind());

        OptionSet booleanConst = scan("const bool shadowHardwareFiltering = true;");
        assertInstanceOf(PackOption.BooleanOption.class,
            booleanConst.byName("shadowHardwareFiltering").orElseThrow());
    }

    @Test
    @DisplayName("a source default outside the annotated list is still offered")
    void defaultOutsideListIsKept() {
        OptionSet set = scan("#define X 3 // [1 2 4]");
        PackOption.ValueOption option = assertInstanceOf(PackOption.ValueOption.class,
            set.byName("X").orElseThrow());
        assertTrue(option.allowedValues().contains("3"),
            () -> "the source default must remain selectable: " + option.allowedValues());
        assertEquals("3", option.defaultValueText());
    }

    @Test
    @DisplayName("float values are inferred as floats, mixed values as strings")
    void typeInference() {
        assertEquals(PackOption.ValueType.FLOAT,
            PackOption.ValueType.infer(List.of("0.5", "1.0", "2.0")));
        assertEquals(PackOption.ValueType.INT, PackOption.ValueType.infer(List.of("1", "2")));
        assertEquals(PackOption.ValueType.STRING,
            PackOption.ValueType.infer(List.of("LOW", "HIGH")));
    }

    @Test
    @DisplayName("inconsistent declarations of one option are dropped and reported")
    void conflictingDeclarationsAreRefused() {
        OptionScanner scanner = new OptionScanner();
        scanner.scan(PackPath.of("/shaders/a.glsl"), List.of("#define Q 1 // [1 2]"));
        scanner.scan(PackPath.of("/shaders/b.glsl"), List.of("#define Q 1 // [1 2 3]"));
        OptionScanner.Result result = scanner.result();

        assertFalse(result.options().containsKey("Q"),
            "an option whose declarations disagree must not be offered");
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().getFirst().contains("declared inconsistently"),
            result.problems().getFirst());
    }

    @Test
    @DisplayName("applying a value rewrites only the declaration line, keeping its annotation")
    void applyingValuesRewritesInPlace() {
        OptionSet set = scan("#define QUALITY 2 // [1 2 3 4] Shadow quality");
        OptionApplier applier = new OptionApplier(set,
            OptionValues.defaults().with("QUALITY", "4"));

        String rewritten = applier.rewrite(FILE, 1,
            "#define QUALITY 2 // [1 2 3 4] Shadow quality");
        assertEquals("#define QUALITY 4 // [1 2 3 4] Shadow quality", rewritten);

        // A different line in the same file is untouched.
        assertEquals("int x = QUALITY;", applier.rewrite(FILE, 2, "int x = QUALITY;"));
        // A different file is untouched.
        assertEquals("#define QUALITY 2 // [1 2 3 4]",
            applier.rewrite(PackPath.of("/shaders/other.glsl"), 1,
                "#define QUALITY 2 // [1 2 3 4]"));
    }

    @Test
    @DisplayName("toggling comments and uncomments the declaration, preserving indentation")
    void applyingTogglesRewritesComments() {
        OptionSet on = scan("    #define SHADOWS");
        String disabled = new OptionApplier(on, OptionValues.defaults().with("SHADOWS", "false"))
            .rewrite(FILE, 1, "    #define SHADOWS");
        assertEquals("    //#define SHADOWS", disabled);

        OptionSet off = scan("    //#define SHADOWS");
        String enabled = new OptionApplier(off, OptionValues.defaults().with("SHADOWS", "true"))
            .rewrite(FILE, 1, "    //#define SHADOWS");
        assertEquals("    #define SHADOWS", enabled);
    }

    @Test
    @DisplayName("a const value is replaced rather than commented out")
    void applyingConstValues() {
        OptionSet set = scan("const int shadowMapResolution = 2048; // [512 1024 2048 4096]");
        String rewritten = new OptionApplier(set,
            OptionValues.defaults().with("shadowMapResolution", "4096"))
            .rewrite(FILE, 1, "const int shadowMapResolution = 2048; // [512 1024 2048 4096]");
        assertEquals("const int shadowMapResolution = 4096; // [512 1024 2048 4096]", rewritten);
    }

    @Test
    @DisplayName("stored values the pack no longer declares are dropped on load")
    void staleStoredValuesAreDropped() {
        OptionSet set = scan("#define QUALITY 2 // [1 2 3 4]");
        OptionValues values = OptionValues.of(Map.of(
            "QUALITY", "3",
            "REMOVED_OPTION", "7",
            "QUALITY_OUT_OF_RANGE", "99"), set);
        assertEquals(Map.of("QUALITY", "3"), values.overrides());

        OptionValues invalid = OptionValues.of(Map.of("QUALITY", "99"), set);
        assertTrue(invalid.isDefault(),
            "a stored value outside the allowed list must not be restored");
    }

    @Test
    @DisplayName("the option fingerprint is order independent")
    void fingerprintIsOrderIndependent() {
        OptionValues a = OptionValues.defaults().with("A", "1").with("B", "2");
        OptionValues b = OptionValues.defaults().with("B", "2").with("A", "1");
        assertEquals(a.cacheFingerprint(), b.cacheFingerprint());
        assertEquals(a, b);
    }

    @Test
    @DisplayName("block-comment annotations are read like line-comment ones")
    void blockCommentAnnotations() {
        OptionScanner.Split split =
            OptionScanner.splitValueAndComment("2 /* [1 2 3] Quality */");
        assertEquals("2", split.value());
        assertEquals(List.of("1", "2", "3"), split.allowedValues());
        assertEquals("Quality", split.commentText().orElseThrow());
    }
}
