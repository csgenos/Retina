/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.translate;

import dev.retina.core.state.AlphaTest;
import dev.retina.core.uniform.UniformSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rewrites OpenGL-era shader-pack GLSL into Vulkan GLSL.
 *
 * <p>Everything here operates on the token stream from {@link GlslLexer}, never on raw text.
 * A rewrite only fires when a whole identifier token in a code position matches, so
 * {@code texture2D} inside a comment, inside {@code my_texture2D}, or inside a string is
 * untouched.
 *
 * <p>Line numbers are preserved exactly. Declarations that move into the generated uniform
 * block are blanked in place rather than deleted, keeping every following line at its
 * original number, and the prologue's length is recorded in {@link TranslatedSource} so the
 * shift it causes can be undone when mapping diagnostics.
 *
 * <p>What this class deliberately does <em>not</em> do: guess. When a construct has no
 * faithful Vulkan meaning, it records a warning or throws
 * {@link UnsupportedConstructException} rather than emitting something that compiles and
 * renders differently. A pack that silently renders wrong is worse than a pack that reports
 * why it cannot run.
 */
public final class VulkanTranslator {

    /** Thrown when pack source uses a construct with no faithful Vulkan translation. */
    public static final class UnsupportedConstructException extends RuntimeException {
        private final int line;

        public UnsupportedConstructException(String message, int line) {
            super(message);
            this.line = line;
        }

        /** The line in preprocessed source, for remapping. */
        public int line() {
            return line;
        }
    }

    /** Inputs that change what the translator emits. */
    public record Options(ShaderStage stage, DrawBuffersDirective drawBuffers,
                          AlphaTest alphaTest, boolean shadowPass, int glslVersion,
                          VaryingLayout varyings) {

        /**
         * Options with the varyings discovered from this translation unit alone.
         *
         * <p>Correct only for a single-stage compile. A real program must pass the union of
         * every stage's varyings so the vertex and fragment sides agree on locations; see
         * {@link VulkanTranslator#collectVaryings}.
         */
        public Options(ShaderStage stage, DrawBuffersDirective drawBuffers, AlphaTest alphaTest,
                       boolean shadowPass, int glslVersion) {
            this(stage, drawBuffers, alphaTest, shadowPass, glslVersion, null);
        }

        /** Defaults for a fullscreen composite-style program. */
        public static Options fullscreen(ShaderStage stage) {
            return new Options(stage, DrawBuffersDirective.defaultTargets(), AlphaTest.ALWAYS,
                false, 450, null);
        }

        /** A copy that uses {@code layout} for varying locations. */
        public Options withVaryings(VaryingLayout layout) {
            return new Options(stage, drawBuffers, alphaTest, shadowPass, glslVersion, layout);
        }
    }

    private final BindingLayout layout;
    private final UniformSchema schema;

    public VulkanTranslator(BindingLayout layout, UniformSchema schema) {
        this.layout = layout;
        this.schema = schema;
    }

    /** Translates one preprocessed translation unit. */
    public TranslatedSource translate(String preprocessedText, Options options) {
        List<GlslLexer.Token> tokens = new ArrayList<>(GlslLexer.tokenize(preprocessedText));
        List<String> warnings = new ArrayList<>();

        int versionIndex = findVersionDirective(tokens);
        int versionLine = versionIndex >= 0 ? tokens.get(versionIndex).line() : 0;
        int sourceVersion = versionIndex >= 0 ? parseVersion(tokens.get(versionIndex).text()) : 110;

        if (versionIndex >= 0) {
            tokens.set(versionIndex, tokens.get(versionIndex)
                .withText("#version " + options.glslVersion()));
        }

        Options resolved = options.varyings() != null
            ? options
            : options.withVaryings(
                VaryingLayout.build(collectVaryings(tokens, options.stage())).layout());

        Collected collected = collectDeclarations(tokens, resolved, warnings);
        rewriteIdentifiers(tokens, resolved, collected, warnings);
        rewriteFragmentOutputs(tokens, resolved, collected, warnings);
        injectAlphaTest(tokens, resolved, collected, warnings);

        String prologue = buildPrologue(resolved, collected, sourceVersion, warnings);
        int prologueLines = (int) prologue.lines().count();

        StringBuilder out = new StringBuilder();
        List<GlslLexer.Token> head = versionIndex < 0
            ? List.of()
            : tokens.subList(0, endOfLine(tokens, versionIndex) + 1);
        List<GlslLexer.Token> tail = versionIndex < 0
            ? tokens
            : tokens.subList(endOfLine(tokens, versionIndex) + 1, tokens.size());

        if (versionIndex < 0) {
            // No version directive at all: supply one, since Vulkan GLSL requires it.
            out.append("#version ").append(resolved.glslVersion()).append('\n');
            prologueLines += 1;
        }
        out.append(GlslLexer.render(head));
        out.append(prologue);
        out.append(GlslLexer.render(tail));

        Set<String> resourcesInThisStage = collected.opaqueUniforms.keySet().stream()
            .map(BindingLayout::canonicalName).collect(java.util.stream.Collectors.toSet());
        List<BindingLayout.Binding> stageBindings = layout.usedBindings().stream()
            .filter(binding -> resourcesInThisStage.contains(binding.name())).toList();
        return new TranslatedSource(out.toString(), prologueLines,
            versionIndex < 0 ? 0 : versionLine, stageBindings,
            resolved.drawBuffers().targets(), warnings);
    }

    // ------------------------------------------------------------------
    // Declaration collection
    // ------------------------------------------------------------------

    /** A uniform-block member's type, and its length when it is an array. */
    private record MemberType(String glslType, int arrayLength) {
    }

    /** What a scan of the translation unit found. */
    private static final class Collected {
        /** Non-opaque uniforms that must move into the generated block. */
        final Map<String, MemberType> blockUniforms = new LinkedHashMap<>();
        /** Opaque uniforms that stay standalone and need a set/binding. */
        final Map<String, String> opaqueUniforms = new LinkedHashMap<>();
        /**
         * Layout qualifiers the pack wrote on an opaque uniform, keyed by name.
         *
         * <p>Preserved because a storage image's format qualifier is not decoration: without
         * it, `layout(rgba16f) uniform image2D` becomes an image that GLSL refuses to read.
         * Dropping it turns a working compute pass into a compile error.
         */
        final Map<String, String> opaqueLayouts = new LinkedHashMap<>();
        /** Legacy {@code gl_*} builtins actually referenced. */
        final Set<String> usedBuiltins = new LinkedHashSet<>();
        /** Whether the program writes {@code gl_FragColor}. */
        boolean usesFragColor;
        /** Highest {@code gl_FragData} index referenced, or -1. */
        int maxFragDataIndex = -1;
        /** The line the highest {@code gl_FragData} index was written on. */
        int maxFragDataLine;
        /** Whether the pack declares its own fragment outputs. */
        boolean hasExplicitOutputs;
        /** Pack-declared fragment outputs by location, so the alpha test can find location 0. */
        final Map<Integer, String> fragmentOutputs = new LinkedHashMap<>();
        /** The next location to hand a pack-declared fragment output. */
        int nextFragmentOutput;
        /** Vertex attributes referenced through legacy {@code gl_*} names. */
        final Set<String> usedLegacyAttributes = new LinkedHashSet<>();
        /** Locations for pack-declared vertex attributes, keyed by name. */
        Map<String, Integer> attributeLocations = Map.of();
        /** Pack-declared vertex attributes that were qualified, name to type. */
        final Map<String, String> declaredAttributes = new LinkedHashMap<>();
    }

    /**
     * The first location available to a pack-declared vertex attribute.
     *
     * <p>Locations 0..7 are reserved for the legacy {@code gl_*} attributes in
     * {@link LegacyBuiltins}, so a pack's own {@code mc_Entity} or {@code at_tangent} has to
     * start above them or the two would alias.
     */
    public static final int CUSTOM_ATTRIBUTE_BASE = 8;

    /**
     * Assigns locations to the vertex attributes a translation unit declares.
     *
     * <p>Sorted by name so the assignment depends only on the set of names, never on the order
     * the declarations happen to appear in — the same property the varying layout needs, and
     * for the same reason: the pipeline cache key includes the layout.
     */
    private static Map<String, Integer> vertexAttributeLocations(List<GlslLexer.Token> tokens) {
        Map<String, VaryingLayout.Declaration> found = new java.util.TreeMap<>();
        int braceDepth = 0;
        int parenDepth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.isPunctuation("{")) {
                braceDepth++;
            } else if (token.isPunctuation("}")) {
                braceDepth--;
            } else if (token.isPunctuation("(")) {
                parenDepth++;
            } else if (token.isPunctuation(")")) {
                parenDepth--;
            } else if (braceDepth == 0 && parenDepth == 0
                && token.kind() == GlslLexer.Kind.IDENTIFIER
                && (token.text().equals("attribute") || token.text().equals("in"))
                && !alreadyQualified(tokens, i)) {
                InterfaceDeclaration declaration = parseInterfaceDeclaration(tokens, i);
                if (declaration == null) {
                    continue;
                }
                for (Declarator declarator : declaration.declarators()) {
                    found.put(declarator.name(), new VaryingLayout.Declaration(declarator.name(),
                        declaration.glslType(), declarator.arrayLength()));
                }
                i = declaration.semicolon();
            }
        }
        Map<String, Integer> locations = new LinkedHashMap<>();
        int next = CUSTOM_ATTRIBUTE_BASE;
        for (VaryingLayout.Declaration declaration : found.values()) {
            locations.put(declaration.name(), next);
            next += VaryingLayout.locationCount(declaration);
        }
        return locations;
    }

    /**
     * Walks the token stream once, collecting uniform declarations and blanking those that
     * move into the generated block.
     */
    private Collected collectDeclarations(List<GlslLexer.Token> tokens, Options options,
                                          List<String> warnings) {
        Collected collected = new Collected();
        if (options.stage().readsVertexAttributes()) {
            collected.attributeLocations = vertexAttributeLocations(tokens);
        }
        int braceDepth = 0;
        int parenDepth = 0;

        for (int i = 0; i < tokens.size(); i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.isPunctuation("{")) {
                braceDepth++;
                continue;
            }
            if (token.isPunctuation("}")) {
                braceDepth--;
                continue;
            }
            if (token.isPunctuation("(")) {
                parenDepth++;
                continue;
            }
            if (token.isPunctuation(")")) {
                parenDepth--;
                continue;
            }
            if (braceDepth != 0 || parenDepth != 0
                || token.kind() != GlslLexer.Kind.IDENTIFIER) {
                continue;
            }
            String keyword = token.text();
            if (keyword.equals("varying") || keyword.equals("in") || keyword.equals("out")
                || keyword.equals("attribute")) {
                if (alreadyQualified(tokens, i)) {
                    if (keyword.equals("out") && options.stage().writesFragmentOutputs()) {
                        recordQualifiedOutput(tokens, i, collected);
                    }
                    continue;
                }
                i = qualifyInterface(tokens, i, options, collected, warnings);
                continue;
            }
            if (!keyword.equals("uniform")) {
                continue;
            }
            i = handleUniformDeclaration(tokens, startOfDeclaration(tokens, i), i, collected,
                warnings);
        }
        return collected;
    }

    /**
     * Adds explicit locations to an unqualified user attribute/in/out declaration.
     *
     * <p>Vulkan GLSL rejects a user input or output with no location, so every one of these has
     * to be given one, and the two stages of a program have to arrive at the same answer
     * independently. Three interfaces are qualified here, from three different sources:
     * vertex attributes from {@link #vertexAttributeLocations}, fragment outputs in declaration
     * order, and everything else from the program's shared {@link VaryingLayout}.
     *
     * @return the index of the declaration's semicolon, or {@code start} when it was not a
     *         declaration after all
     */
    private int qualifyInterface(List<GlslLexer.Token> tokens, int start, Options options,
                                 Collected collected, List<String> warnings) {
        InterfaceDeclaration declaration = parseInterfaceDeclaration(tokens, start);
        if (declaration == null) {
            return start;
        }
        String keyword = tokens.get(start).text();
        String direction = switch (keyword) {
            case "varying" -> options.stage().varyingDirection();
            case "attribute" -> "in";
            default -> keyword;
        };
        int line = tokens.get(start).line();

        if (direction.equals("out") && options.stage().writesFragmentOutputs()) {
            return qualifyFragmentOutputs(tokens, start, declaration, collected);
        }
        if (direction.equals("in") && options.stage().readsVertexAttributes()) {
            return qualifyVertexAttributes(tokens, start, declaration, collected, warnings, line);
        }

        List<String> replacements = new ArrayList<>();
        for (Declarator declarator : declaration.declarators()) {
            Optional<VaryingLayout.Slot> slot = options.varyings() == null
                ? Optional.empty()
                : options.varyings().slot(declarator.name());
            if (slot.isEmpty()) {
                warnings.add("line " + line + ": varying '" + declarator.name()
                    + "' has no assigned location; Retina could not classify its declaration"
                    + " and left it unqualified");
                return declaration.semicolon();
            }
            replacements.add("layout(location = " + slot.get().location() + ") " + direction
                + " " + declare(declaration.glslType(), declarator) + ";");
        }
        rewriteDeclaration(tokens, start, declaration.semicolon(), replacements);
        return declaration.semicolon();
    }

    /** Qualifies pack-declared fragment outputs, recording which name is location 0. */
    private static int qualifyFragmentOutputs(List<GlslLexer.Token> tokens, int start,
                                              InterfaceDeclaration declaration,
                                              Collected collected) {
        collected.hasExplicitOutputs = true;
        List<String> replacements = new ArrayList<>();
        for (Declarator declarator : declaration.declarators()) {
            int location = collected.nextFragmentOutput++;
            collected.fragmentOutputs.putIfAbsent(location, declarator.name());
            replacements.add("layout(location = " + location + ") out "
                + declare(declaration.glslType(), declarator) + ";");
        }
        rewriteDeclaration(tokens, start, declaration.semicolon(), replacements);
        return declaration.semicolon();
    }

    /** Qualifies pack-declared vertex attributes from the unit's deterministic layout. */
    private static int qualifyVertexAttributes(List<GlslLexer.Token> tokens, int start,
                                               InterfaceDeclaration declaration,
                                               Collected collected, List<String> warnings,
                                               int line) {
        List<String> replacements = new ArrayList<>();
        for (Declarator declarator : declaration.declarators()) {
            Integer location = collected.attributeLocations.get(declarator.name());
            if (location == null) {
                warnings.add("line " + line + ": vertex attribute '" + declarator.name()
                    + "' could not be assigned a location and was left unqualified");
                return declaration.semicolon();
            }
            collected.declaredAttributes.putIfAbsent(declarator.name(),
                declaration.glslType());
            replacements.add("layout(location = " + location + ") in "
                + declare(declaration.glslType(), declarator) + ";");
        }
        rewriteDeclaration(tokens, start, declaration.semicolon(), replacements);
        return declaration.semicolon();
    }

    private static String declare(String glslType, Declarator declarator) {
        return glslType + " " + declarator.name()
            + (declarator.arrayLength() > 0 ? "[" + declarator.arrayLength() + "]" : "");
    }

    /**
     * Replaces a declaration with one or more generated declarations.
     *
     * <p>The whole original range is blanked and the replacement text is attached to the first
     * token, so a declaration that introduced several names becomes several fully qualified
     * declarations without moving any line: {@link #blank} keeps every newline the range held.
     */
    private static void rewriteDeclaration(List<GlslLexer.Token> tokens, int start, int semicolon,
                                           List<String> replacements) {
        GlslLexer.Token first = tokens.get(start);
        blank(tokens, start, semicolon);
        tokens.set(start, first.withText(String.join(" ", replacements)));
    }

    /** One declarator in a declaration: a name and, when it is an array, its length. */
    private record Declarator(String name, int arrayLength) {
    }

    /**
     * A parsed in/out/varying/attribute declaration.
     *
     * <p>{@code declarators} holds every name the declaration introduces. A declaration such as
     * {@code varying vec2 a, b;} introduces two, and giving the pair one shared location
     * qualifier makes them overlap, so each has to be tracked and qualified separately.
     */
    private record InterfaceDeclaration(List<Declarator> declarators, String glslType,
                                        int semicolon) {
        Declarator first() {
            return declarators.getFirst();
        }
    }

    /**
     * Parses the declaration beginning at an in/out/varying/attribute keyword.
     *
     * @return null when the tokens are not a variable declaration
     */
    private static InterfaceDeclaration parseInterfaceDeclaration(List<GlslLexer.Token> tokens,
                                                                  int start) {
        int i = GlslLexer.nextCode(tokens, start + 1);
        while (i >= 0 && i < tokens.size()
            && tokens.get(i).kind() == GlslLexer.Kind.IDENTIFIER
            && GlslTypes.QUALIFIERS.contains(tokens.get(i).text())) {
            i = GlslLexer.nextCode(tokens, i + 1);
        }
        if (i < 0 || i >= tokens.size()
            || tokens.get(i).kind() != GlslLexer.Kind.IDENTIFIER) {
            return null;
        }
        String type = tokens.get(i).text();
        int afterType = GlslLexer.nextCode(tokens, i + 1);
        if (afterType < 0 || afterType >= tokens.size()) {
            return null;
        }
        int semicolon = skipToSemicolon(tokens, i);
        if (semicolon < 0) {
            return null;
        }
        List<Declarator> declarators = declaredNames(tokens, afterType, semicolon);
        if (declarators.isEmpty()) {
            return null;
        }
        return new InterfaceDeclaration(declarators, type, semicolon);
    }

    /**
     * Collects every varying a translation unit declares.
     *
     * <p>The pipeline builder calls this for each stage of a program and feeds the union to
     * {@link VaryingLayout#build}, which is what makes the stages agree on locations.
     */
    public static List<VaryingLayout.Declaration> collectVaryings(List<GlslLexer.Token> tokens,
                                                                  ShaderStage stage) {
        List<VaryingLayout.Declaration> out = new ArrayList<>();
        int braceDepth = 0;
        int parenDepth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.isPunctuation("{")) {
                braceDepth++;
            } else if (token.isPunctuation("}")) {
                braceDepth--;
            } else if (token.isPunctuation("(")) {
                parenDepth++;
            } else if (token.isPunctuation(")")) {
                parenDepth--;
            } else if (braceDepth == 0 && parenDepth == 0
                && token.kind() == GlslLexer.Kind.IDENTIFIER) {
                String keyword = token.text();
                boolean isVarying = keyword.equals("varying")
                    || (keyword.equals("in") && !stage.readsVertexAttributes())
                    || (keyword.equals("out") && !stage.writesFragmentOutputs());
                if (!isVarying || alreadyQualified(tokens, i)) {
                    continue;
                }
                InterfaceDeclaration declaration = parseInterfaceDeclaration(tokens, i);
                if (declaration != null) {
                    // Every declarator, not just the first: `varying vec2 a, b;` introduces two
                    // varyings, and reserving a slot for only one of them overlaps the other
                    // onto an occupied location.
                    for (Declarator declarator : declaration.declarators()) {
                        out.add(new VaryingLayout.Declaration(declarator.name(),
                            declaration.glslType(), declarator.arrayLength()));
                    }
                    i = declaration.semicolon();
                }
            }
        }
        return out;
    }

    /** Collects varyings from raw source text. */
    public static List<VaryingLayout.Declaration> collectVaryings(String source,
                                                                  ShaderStage stage) {
        return collectVaryings(GlslLexer.tokenize(source), stage);
    }

    /**
     * Records a fragment output the pack qualified itself.
     *
     * <p>Needed for the same reason as {@link #qualifyFragmentOutputs}: the injected alpha test
     * has to name the variable bound to location 0, and when the pack declares its own outputs
     * that name is the pack's, not Retina's.
     */
    private static void recordQualifiedOutput(List<GlslLexer.Token> tokens, int index,
                                              Collected collected) {
        collected.hasExplicitOutputs = true;
        InterfaceDeclaration declaration = parseInterfaceDeclaration(tokens, index);
        if (declaration == null) {
            return;
        }
        int location = qualifiedLocation(tokens, index).orElse(collected.nextFragmentOutput);
        for (Declarator declarator : declaration.declarators()) {
            collected.fragmentOutputs.putIfAbsent(location, declarator.name());
            location++;
        }
        collected.nextFragmentOutput = Math.max(collected.nextFragmentOutput, location);
    }

    /** Reads {@code location = n} out of the layout qualifier preceding {@code index}. */
    private static Optional<Integer> qualifiedLocation(List<GlslLexer.Token> tokens, int index) {
        int close = GlslLexer.previousCode(tokens, index - 1);
        if (close < 0 || !tokens.get(close).isPunctuation(")")) {
            return Optional.empty();
        }
        int depth = 0;
        int open = -1;
        for (int i = close; i >= 0; i--) {
            if (tokens.get(i).isPunctuation(")")) {
                depth++;
            } else if (tokens.get(i).isPunctuation("(")) {
                depth--;
                if (depth == 0) {
                    open = i;
                    break;
                }
            }
        }
        if (open < 0) {
            return Optional.empty();
        }
        for (int i = open + 1; i < close; i++) {
            if (!tokens.get(i).isIdentifier("location")) {
                continue;
            }
            int equals = GlslLexer.nextCode(tokens, i + 1);
            if (equals < 0 || !tokens.get(equals).isPunctuation("=")) {
                continue;
            }
            int value = GlslLexer.nextCode(tokens, equals + 1);
            if (value < 0 || tokens.get(value).kind() != GlslLexer.Kind.NUMBER) {
                continue;
            }
            try {
                return Optional.of(Integer.parseInt(tokens.get(value).text()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Whether the declaration at {@code index} is already preceded by a layout qualifier. */
    private static boolean alreadyQualified(List<GlslLexer.Token> tokens, int index) {
        int previous = GlslLexer.previousCode(tokens, index - 1);
        // Walk back over `)` ... `(` layout
        if (previous >= 0 && tokens.get(previous).isPunctuation(")")) {
            int depth = 0;
            for (int i = previous; i >= 0; i--) {
                if (tokens.get(i).isPunctuation(")")) {
                    depth++;
                } else if (tokens.get(i).isPunctuation("(")) {
                    depth--;
                    if (depth == 0) {
                        int before = GlslLexer.previousCode(tokens, i - 1);
                        return before >= 0 && tokens.get(before).isIdentifier("layout");
                    }
                }
            }
        }
        return false;
    }

    /**
     * The first token of the declaration containing the {@code uniform} keyword at
     * {@code uniformIndex}.
     *
     * <p>A pack may write {@code layout(rgba16f) uniform image2D img;}, so the qualifiers can
     * precede the keyword. Scanning back to the previous statement boundary finds them; the
     * whole range is later blanked together so no fragment of the original declaration is
     * left behind.
     */
    private static int startOfDeclaration(List<GlslLexer.Token> tokens, int uniformIndex) {
        int start = uniformIndex;
        int i = GlslLexer.previousCode(tokens, uniformIndex - 1);
        while (i >= 0) {
            GlslLexer.Token token = tokens.get(i);
            if (token.isPunctuation(";") || token.isPunctuation("}") || token.isPunctuation("{")) {
                break;
            }
            if (token.isPunctuation(")")) {
                int depth = 0;
                int j = i;
                for (; j >= 0; j--) {
                    if (tokens.get(j).isPunctuation(")")) {
                        depth++;
                    } else if (tokens.get(j).isPunctuation("(")) {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                }
                int before = GlslLexer.previousCode(tokens, j - 1);
                if (before < 0 || !tokens.get(before).isIdentifier("layout")) {
                    break;
                }
                start = before;
                i = GlslLexer.previousCode(tokens, before - 1);
                continue;
            }
            if (token.kind() == GlslLexer.Kind.IDENTIFIER
                && GlslTypes.QUALIFIERS.contains(token.text())) {
                start = i;
                i = GlslLexer.previousCode(tokens, i - 1);
                continue;
            }
            break;
        }
        return start;
    }

    /**
     * Processes one {@code uniform} declaration starting at {@code start}.
     *
     * @return the index of the declaration's terminating {@code ;}, or {@code start} when the
     *         declaration could not be parsed and was left alone
     */
    private int handleUniformDeclaration(List<GlslLexer.Token> tokens, int declarationStart,
                                         int start, Collected collected,
                                         List<String> warnings) {
        // Qualifiers written before the `uniform` keyword belong to this declaration too.
        List<String> leadingQualifiers = collectLeadingQualifiers(tokens, declarationStart,
            start);
        int i = GlslLexer.nextCode(tokens, start + 1);
        if (i < 0) {
            return start;
        }
        // Collect qualifiers and any layout(...) the pack already wrote, so they can be
        // re-emitted alongside Retina's set/binding rather than lost.
        List<String> packQualifiers = new ArrayList<>(leadingQualifiers);
        while (i < tokens.size()) {
            GlslLexer.Token token = tokens.get(i);
            if (token.kind() == GlslLexer.Kind.IDENTIFIER
                && GlslTypes.QUALIFIERS.contains(token.text())) {
                packQualifiers.add(token.text());
                i = GlslLexer.nextCode(tokens, i + 1);
                continue;
            }
            if (token.isIdentifier("layout")) {
                int open = GlslLexer.nextCode(tokens, i + 1);
                int afterLayout = skipBalanced(tokens, open, "(", ")");
                packQualifiers.add(layoutContents(tokens, open, afterLayout));
                i = GlslLexer.nextCode(tokens, afterLayout);
                continue;
            }
            break;
        }
        if (i < 0 || i >= tokens.size()) {
            return start;
        }

        GlslLexer.Token typeToken = tokens.get(i);
        if (typeToken.kind() != GlslLexer.Kind.IDENTIFIER) {
            return start;
        }
        String type = typeToken.text();

        int afterType = GlslLexer.nextCode(tokens, i + 1);
        if (afterType >= 0 && tokens.get(afterType).isPunctuation("{")) {
            // `uniform Name { ... };` — an interface block the pack declared itself. It is
            // left as written; a pack that hand-rolls a UBO is already Vulkan-aware and
            // rewriting it would be more likely to break it than to help.
            warnings.add("line " + typeToken.line() + ": pack declares its own uniform block '"
                + type + "'; Retina left it unchanged and did not assign it a descriptor set");
            return skipToSemicolon(tokens, afterType);
        }

        int semicolon = skipToSemicolon(tokens, i);
        if (semicolon < 0) {
            return start;
        }

        List<Declarator> names = declaredNames(tokens, afterType, semicolon);
        if (names.isEmpty()) {
            return semicolon;
        }

        if (GlslTypes.isOpaque(type)) {
            String qualifiers = String.join(", ", packQualifiers.stream()
                .filter(q -> !q.isBlank()).toList());
            for (Declarator declarator : names) {
                collected.opaqueUniforms.put(declarator.name(), type);
                if (!qualifiers.isEmpty()) {
                    collected.opaqueLayouts.put(declarator.name(), qualifiers);
                }
                layout.withType(declarator.name(), type);
            }
            // Blank the declaration; the prologue re-emits it with a set/binding qualifier.
            blank(tokens, declarationStart, semicolon);
            return semicolon;
        }

        if (!GlslTypes.isValueType(stripArray(type))) {
            // Refused rather than warned. A default-block struct uniform is illegal in Vulkan,
            // so leaving it standalone produces a link error whose message names neither the
            // uniform nor the reason.
            throw new UnsupportedConstructException("uniform '" + names.getFirst().name()
                + "' has type '" + type + "', which has no Vulkan uniform-block representation;"
                + " Retina supports scalar, vector and matrix uniforms, and samplers and images"
                + " as standalone resources", typeToken.line());
        }

        for (Declarator declarator : names) {
            collected.blockUniforms.put(declarator.name(),
                new MemberType(type, declarator.arrayLength()));
        }
        blank(tokens, declarationStart, semicolon);
        return semicolon;
    }

    /** Qualifiers and layout contents written before the {@code uniform} keyword. */
    private static List<String> collectLeadingQualifiers(List<GlslLexer.Token> tokens,
                                                         int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.isIdentifier("layout")) {
                int open = GlslLexer.nextCode(tokens, i + 1);
                int afterLayout = skipBalanced(tokens, open, "(", ")");
                out.add(layoutContents(tokens, open, afterLayout));
                i = afterLayout - 1;
                continue;
            }
            if (token.kind() == GlslLexer.Kind.IDENTIFIER
                && GlslTypes.QUALIFIERS.contains(token.text())) {
                out.add(token.text());
            }
        }
        return out;
    }

    /**
     * Collects declarator names, and their array lengths, between the type and the semicolon.
     *
     * <p>Both GLSL array spellings are recognised. {@code vec3 lights[4]} attaches the length
     * to the declarator; {@code vec3[4] lights} attaches it to the type, in which case it
     * applies to every declarator that does not carry its own. Losing the length here is not a
     * cosmetic omission: the member is then laid out and uploaded as a single element while the
     * shader indexes four, and {@code lights[0]} on a {@code vec3} is a component access that
     * compiles.
     */
    private static List<Declarator> declaredNames(List<GlslLexer.Token> tokens, int from,
                                                  int to) {
        List<Declarator> names = new ArrayList<>();
        int depth = 0;
        boolean expectName = true;
        Integer bracketLiteral = null;
        int typeArrayLength = 0;
        for (int i = from; i >= 0 && i < to; i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.isPunctuation("[")) {
                depth++;
                if (depth == 1) {
                    bracketLiteral = null;
                }
                continue;
            }
            if (token.isPunctuation("]")) {
                depth--;
                if (depth == 0 && bracketLiteral != null) {
                    if (names.isEmpty()) {
                        // The size sits on the type: `vec3[4] lights;`
                        typeArrayLength = bracketLiteral;
                    } else {
                        int last = names.size() - 1;
                        names.set(last, new Declarator(names.get(last).name(), bracketLiteral));
                    }
                }
                continue;
            }
            if (depth > 0) {
                if (depth == 1 && token.kind() == GlslLexer.Kind.NUMBER) {
                    try {
                        bracketLiteral = Integer.parseInt(token.text());
                    } catch (NumberFormatException ignored) {
                        bracketLiteral = null;
                    }
                }
                continue;
            }
            if (token.isPunctuation(",")) {
                expectName = true;
                continue;
            }
            if (token.kind() == GlslLexer.Kind.IDENTIFIER && expectName) {
                names.add(new Declarator(token.text(), 0));
                expectName = false;
            }
        }
        if (typeArrayLength > 0) {
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).arrayLength() == 0) {
                    names.set(i, new Declarator(names.get(i).name(), typeArrayLength));
                }
            }
        }
        return names;
    }

    private static String stripArray(String type) {
        int bracket = type.indexOf('[');
        return bracket < 0 ? type : type.substring(0, bracket);
    }

    /**
     * Replaces a token range with empty text, keeping newlines so line numbers do not move.
     */
    private static void blank(List<GlslLexer.Token> tokens, int from, int to) {
        for (int i = from; i <= to && i < tokens.size(); i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.kind() == GlslLexer.Kind.NEWLINE) {
                continue;
            }
            // A block comment can span lines, and it can sit inside a declaration that moves
            // into the generated block. Emptying it whole would delete those newlines and
            // shift every diagnostic after it, which is exactly what the run list exists to
            // prevent, so keep the line terminators it held.
            tokens.set(i, token.withText(lineTerminatorsOf(token.text())));
        }
    }

    /** Just the line terminators in {@code text}, normalised to {@code \n}. */
    private static String lineTerminatorsOf(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.append('\n');
            } else if (c == '\r') {
                out.append('\n');
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
            }
        }
        return out.toString();
    }

    private static int skipToSemicolon(List<GlslLexer.Token> tokens, int from) {
        int depth = 0;
        for (int i = from; i < tokens.size(); i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.isPunctuation("{") || token.isPunctuation("(")) {
                depth++;
            } else if (token.isPunctuation("}") || token.isPunctuation(")")) {
                depth--;
            } else if (token.isPunctuation(";") && depth <= 0) {
                return i;
            }
        }
        return -1;
    }

    /** The text between a {@code layout} directive's parentheses, without the parentheses. */
    private static String layoutContents(List<GlslLexer.Token> tokens, int open, int afterClose) {
        if (open < 0 || afterClose <= open + 1) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = open + 1; i < afterClose - 1 && i < tokens.size(); i++) {
            out.append(tokens.get(i).text());
        }
        return out.toString().trim();
    }

    private static int skipBalanced(List<GlslLexer.Token> tokens, int from, String open,
                                    String close) {
        if (from < 0 || from >= tokens.size() || !tokens.get(from).isPunctuation(open)) {
            return from;
        }
        int depth = 0;
        for (int i = from; i < tokens.size(); i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.isPunctuation(open)) {
                depth++;
            } else if (token.isPunctuation(close)) {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return tokens.size();
    }

    // ------------------------------------------------------------------
    // Identifier rewriting
    // ------------------------------------------------------------------

    private void rewriteIdentifiers(List<GlslLexer.Token> tokens, Options options,
                                    Collected collected, List<String> warnings) {
        for (int i = 0; i < tokens.size(); i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER) {
                continue;
            }
            String name = token.text();

            // `attribute` and `varying` were removed in GLSL 140/150.
            if (name.equals("attribute") && options.stage().readsVertexAttributes()) {
                tokens.set(i, token.withText("in"));
                continue;
            }
            if (name.equals("varying")) {
                tokens.set(i, token.withText(options.stage().varyingDirection()));
                continue;
            }

            if (name.equals("ftransform") && isCallPosition(tokens, i)) {
                // Not a `gl_` name, so it never reaches rewriteBuiltin; the prologue emits a
                // replacement function and the call site is left as written.
                collected.usedBuiltins.add("ftransform");
                collected.usedLegacyAttributes.add("gl_Vertex");
                continue;
            }

            if (GlslTypes.SHADOW_VEC4_FUNCTIONS.contains(name) && isCallPosition(tokens, i)) {
                // Wrapped rather than renamed: `texture()` on a shadow sampler yields a float
                // whereas `shadow2D()` yielded a vec4, and packs index the result.
                tokens.set(i, token.withText("retina_" + name));
                collected.usedBuiltins.add("retina_" + name);
                continue;
            }
            String replacement = GlslTypes.LEGACY_TEXTURE_FUNCTIONS.get(name);
            if (replacement != null && isCallPosition(tokens, i)) {
                tokens.set(i, token.withText(replacement));
                continue;
            }

            if (name.startsWith("gl_")) {
                rewriteBuiltin(tokens, i, options, collected, warnings);
                continue;
            }

            // A legacy sampler alias is renamed at every reference. It is never emitted as a
            // `#define`, because aliases such as `texture` collide with GLSL builtins and a
            // macro would rewrite `texture(sampler, uv)` calls into calls on a variable.
            if (BindingLayout.isLegacyAlias(name) && collected.opaqueUniforms.containsKey(name)
                && !isCallPosition(tokens, i)) {
                tokens.set(i, token.withText(BindingLayout.canonicalName(name)));
            }
        }
    }

    /**
     * Rewrites one {@code gl_*} reference.
     *
     * <p>Compatibility-profile builtins do not exist in Vulkan GLSL. Each one becomes either a
     * declared vertex input or a member of the generated uniform block; the set of names
     * touched here is exactly the set Retina supplies, and anything else is refused rather
     * than left to produce an opaque link error.
     */
    private void rewriteBuiltin(List<GlslLexer.Token> tokens, int index, Options options,
                                Collected collected, List<String> warnings) {
        GlslLexer.Token token = tokens.get(index);
        String name = token.text();

        // Builtins that exist unchanged in Vulkan GLSL.
        switch (name) {
            case "gl_Position", "gl_PointSize", "gl_VertexIndex", "gl_InstanceIndex",
                 "gl_FragCoord", "gl_FragDepth", "gl_FrontFacing", "gl_PointCoord",
                 "gl_PrimitiveID", "gl_Layer", "gl_ViewportIndex", "gl_ClipDistance",
                 "gl_CullDistance", "gl_InvocationID", "gl_in", "gl_out", "gl_TessCoord",
                 "gl_TessLevelInner", "gl_TessLevelOuter", "gl_PatchVerticesIn",
                 "gl_WorkGroupID", "gl_WorkGroupSize", "gl_LocalInvocationID",
                 "gl_GlobalInvocationID", "gl_LocalInvocationIndex", "gl_NumWorkGroups",
                 "gl_SampleID", "gl_SampleMask", "gl_SamplePosition" -> {
                return;
            }
            // Vertex/instance ids differ in base offset between GL and Vulkan. Rewriting the
            // name alone would introduce an off-by-base bug in any pack that uses them for
            // addressing, so they are expressed relative to the Vulkan-provided base.
            case "gl_VertexID" -> {
                tokens.set(index, token.withText("gl_VertexIndex"));
                return;
            }
            case "gl_InstanceID" -> {
                tokens.set(index, token.withText("gl_InstanceIndex"));
                return;
            }
            case "gl_FragColor" -> {
                collected.usesFragColor = true;
                tokens.set(index, token.withText("retina_FragData0"));
                return;
            }
            case "gl_FragData" -> {
                collected.maxFragDataLine = token.line();
                int consumed = rewriteFragData(tokens, index, collected);
                if (consumed < 0) {
                    throw new UnsupportedConstructException(
                        "gl_FragData must be indexed with a literal constant so Retina can"
                            + " assign it a fixed fragment output location", token.line());
                }
                return;
            }
            default -> {
                // fall through to the table lookups below
            }
        }

        if (LegacyBuiltins.VERTEX_ATTRIBUTES.containsKey(name)) {
            if (!options.stage().readsVertexAttributes()) {
                throw new UnsupportedConstructException(name
                    + " is a vertex attribute and cannot be read from the "
                    + options.stage().name().toLowerCase(java.util.Locale.ROOT) + " stage",
                    token.line());
            }
            collected.usedLegacyAttributes.add(name);
            tokens.set(index, token.withText(LegacyBuiltins.VERTEX_ATTRIBUTES.get(name).glslName()));
            return;
        }

        if (LegacyBuiltins.MATRIX_UNIFORMS.containsKey(name)) {
            collected.usedBuiltins.add(name);
            tokens.set(index,
                token.withText(LegacyBuiltins.MATRIX_UNIFORMS.get(name).glslName()));
            return;
        }

        if (name.equals("ftransform")) {
            collected.usedBuiltins.add("ftransform");
            collected.usedLegacyAttributes.add("gl_Vertex");
            return;
        }

        warnings.add("line " + token.line() + ": '" + name
            + "' is a legacy OpenGL builtin that Retina does not provide; the shader will"
            + " fail to compile if this reference is reachable");
    }

    /**
     * Rewrites {@code gl_FragData[n]} into a declared output.
     *
     * @return the index after the closing bracket, or -1 when the index is not a literal
     */
    private static int rewriteFragData(List<GlslLexer.Token> tokens, int index,
                                       Collected collected) {
        int open = GlslLexer.nextCode(tokens, index + 1);
        if (open < 0 || !tokens.get(open).isPunctuation("[")) {
            return -1;
        }
        int number = GlslLexer.nextCode(tokens, open + 1);
        if (number < 0 || tokens.get(number).kind() != GlslLexer.Kind.NUMBER) {
            return -1;
        }
        int close = GlslLexer.nextCode(tokens, number + 1);
        if (close < 0 || !tokens.get(close).isPunctuation("]")) {
            return -1;
        }
        int slot;
        try {
            slot = Integer.parseInt(tokens.get(number).text());
        } catch (NumberFormatException e) {
            return -1;
        }
        collected.maxFragDataIndex = Math.max(collected.maxFragDataIndex, slot);
        tokens.set(index, tokens.get(index).withText("retina_FragData" + slot));
        for (int i = open; i <= close; i++) {
            tokens.set(i, tokens.get(i).withText(""));
        }
        return close + 1;
    }

    /** Whether the identifier at {@code index} is immediately followed by {@code (}. */
    private static boolean isCallPosition(List<GlslLexer.Token> tokens, int index) {
        int next = GlslLexer.nextCode(tokens, index + 1);
        return next >= 0 && tokens.get(next).isPunctuation("(");
    }

    // ------------------------------------------------------------------
    // Fragment outputs and alpha test
    // ------------------------------------------------------------------

    private void rewriteFragmentOutputs(List<GlslLexer.Token> tokens, Options options,
                                        Collected collected, List<String> warnings) {
        if (!options.stage().writesFragmentOutputs()) {
            return;
        }
        int declared = options.drawBuffers().attachmentCount();
        int used = Math.max(collected.maxFragDataIndex + 1, collected.usesFragColor ? 1 : 0);
        if (used > declared) {
            // Refused rather than warned. The prologue declares one output per draw buffer, so
            // a surplus write refers to a variable that does not exist and the pack fails with
            // "undeclared identifier" — a message that says nothing about the real cause.
            throw new UnsupportedConstructException("the program writes fragment output "
                + (used - 1) + " but its "
                + (options.drawBuffers().form() == DrawBuffersDirective.Form.DEFAULT
                    ? "missing DRAWBUFFERS/RENDERTARGETS directive implies only colortex0"
                    : options.drawBuffers().form() + " directive declares only " + declared
                        + " target(s)")
                + "; add the target to the directive or remove the write",
                collected.maxFragDataLine);
        }
    }

    /**
     * Appends the alpha test to the end of {@code main}.
     *
     * <p>Injected as a trailing statement rather than at each write site, because the test
     * applies to the final value of output 0 and a pack may write it several times.
     */
    private void injectAlphaTest(List<GlslLexer.Token> tokens, Options options,
                                 Collected collected, List<String> warnings) {
        if (!options.stage().writesFragmentOutputs() || !options.alphaTest().discards()) {
            return;
        }
        int mainClose = findMainClosingBrace(tokens);
        if (mainClose < 0) {
            warnings.add("alphaTest was requested but no main() was found; the test was not"
                + " applied");
            return;
        }
        // The test applies to whatever is bound to location 0, which is Retina's generated
        // output only when the pack did not declare its own. Naming the generated one
        // unconditionally makes every cutout pass in a modern pack fail to compile.
        String target = "retina_FragData0";
        if (collected.hasExplicitOutputs) {
            String declared = collected.fragmentOutputs.get(0);
            if (declared == null) {
                throw new UnsupportedConstructException("alphaTest was requested but the"
                    + " program declares its own fragment outputs and none of them is at"
                    + " location 0, so Retina cannot tell which value to test",
                    tokens.get(mainClose).line());
            }
            target = declared;
        }
        String statement = "if (" + options.alphaTest().discardCondition(target + ".a")
            + ") { discard; } ";
        GlslLexer.Token brace = tokens.get(mainClose);
        tokens.set(mainClose, brace.withText(statement + brace.text()));
    }

    /** Finds the closing brace of {@code main}, or -1. */
    private static int findMainClosingBrace(List<GlslLexer.Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).isIdentifier("main")) {
                continue;
            }
            int paren = GlslLexer.nextCode(tokens, i + 1);
            if (paren < 0 || !tokens.get(paren).isPunctuation("(")) {
                continue;
            }
            int afterParams = skipBalanced(tokens, paren, "(", ")");
            int brace = GlslLexer.nextCode(tokens, afterParams);
            if (brace < 0 || !tokens.get(brace).isPunctuation("{")) {
                continue;
            }
            int depth = 0;
            for (int j = brace; j < tokens.size(); j++) {
                if (tokens.get(j).isPunctuation("{")) {
                    depth++;
                } else if (tokens.get(j).isPunctuation("}")) {
                    depth--;
                    if (depth == 0) {
                        return j;
                    }
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Prologue
    // ------------------------------------------------------------------

    private String buildPrologue(Options options, Collected collected, int sourceVersion,
                                 List<String> warnings) {
        StringBuilder out = new StringBuilder(1024);
        out.append("// ==== Retina Vulkan translation prologue ====\n");
        out.append("// translated from GLSL ").append(sourceVersion).append('\n');
        out.append("#extension GL_GOOGLE_include_directive : disable\n");

        // Uniform block. Members keep their original names because the block is unnamed, so
        // no reference in pack source has to change.
        List<PrologueMember> members = new ArrayList<>();
        collected.usedBuiltins.stream()
            .filter(LegacyBuiltins.MATRIX_UNIFORMS::containsKey)
            .map(LegacyBuiltins.MATRIX_UNIFORMS::get)
            .forEach(matrix -> addIfAbsent(members, matrix.glslName(), matrix.glslType(), 0));
        if (collected.usedBuiltins.contains("ftransform")) {
            addIfAbsent(members, "retina_ModelViewProjectionMatrix", "mat4", 0);
        }
        collected.blockUniforms.forEach((name, type) ->
            addIfAbsent(members, name, type.glslType(), type.arrayLength()));

        // Scene values the pack did not declare but Retina always supplies are appended so
        // that every program in a pack ends with the same tail of scene members. The block is
        // laid out and uploaded per pack from the union of these declarations; see
        // TerrainUniformLayout and Std140Layout.
        schema.alwaysPresent().forEach(entry -> addIfAbsent(members, entry.name(),
            entry.glslType(), 0));

        out.append("layout(std140, set = ").append(BindingLayout.SET_UNIFORMS)
            .append(", binding = ").append(BindingLayout.BINDING_UNIFORM_BLOCK)
            .append(") uniform RetinaUniforms {\n");
        for (PrologueMember member : members) {
            out.append("    ").append(member.declaration()).append(";\n");
        }
        out.append("};\n");

        // Push constants for per-draw data.
        out.append("layout(push_constant) uniform RetinaDrawConstants {\n");
        for (UniformSchema.Entry entry : schema.pushConstants()) {
            out.append("    ").append(entry.glslType()).append(' ')
                .append(entry.name()).append(";\n");
        }
        out.append("} retina_draw;\n");

        // Opaque uniforms, re-emitted with explicit sets and bindings.
        collected.opaqueUniforms.forEach((name, type) -> {
            String canonical = BindingLayout.canonicalName(name);
            BindingLayout.Binding binding = layout.withType(canonical, type);
            String packQualifiers = collected.opaqueLayouts.get(name);
            // Memory qualifiers (readonly/writeonly/coherent/...) are not layout items and
            // must sit outside the parentheses; the format qualifier must sit inside.
            List<String> inside = new ArrayList<>();
            List<String> outside = new ArrayList<>();
            if (packQualifiers != null) {
                for (String qualifier : packQualifiers.split(",")) {
                    String trimmed = qualifier.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    (GlslTypes.QUALIFIERS.contains(trimmed) ? outside : inside).add(trimmed);
                }
            }
            inside.add("set = " + binding.set());
            inside.add("binding = " + binding.binding());
            out.append("layout(").append(String.join(", ", inside)).append(") ");
            if (!outside.isEmpty()) {
                out.append(String.join(" ", outside)).append(' ');
            }
            out.append("uniform ").append(type).append(' ').append(canonical).append(";\n");
        });

        // Legacy vertex attributes.
        for (String name : collected.usedLegacyAttributes) {
            LegacyBuiltins.Attribute attribute = LegacyBuiltins.VERTEX_ATTRIBUTES.get(name);
            out.append("layout(location = ").append(attribute.location()).append(") in ")
                .append(attribute.glslType()).append(' ').append(attribute.glslName())
                .append(";\n");
        }

        // Fragment outputs, one per declared draw buffer.
        if (options.stage().writesFragmentOutputs() && !collected.hasExplicitOutputs) {
            for (int location = 0; location < options.drawBuffers().attachmentCount();
                 location++) {
                out.append("layout(location = ").append(location).append(") out vec4 retina_FragData")
                    .append(location).append(";\n");
            }
        }

        // Shadow compatibility wrappers.
        if (collected.usedBuiltins.contains("retina_shadow2D")) {
            out.append("vec4 retina_shadow2D(sampler2DShadow s, vec3 c) {"
                + " return vec4(texture(s, c)); }\n");
        }
        if (collected.usedBuiltins.contains("retina_shadow2DProj")) {
            out.append("vec4 retina_shadow2DProj(sampler2DShadow s, vec4 c) {"
                + " return vec4(textureProj(s, c)); }\n");
        }
        if (collected.usedBuiltins.contains("retina_shadow2DLod")) {
            out.append("vec4 retina_shadow2DLod(sampler2DShadow s, vec3 c, float l) {"
                + " return vec4(textureLod(s, c, l)); }\n");
        }
        if (collected.usedBuiltins.contains("retina_shadow2DProjLod")) {
            out.append("vec4 retina_shadow2DProjLod(sampler2DShadow s, vec4 c, float l) {"
                + " return vec4(textureProjLod(s, c, l)); }\n");
        }
        if (collected.usedBuiltins.contains("retina_shadow1D")) {
            out.append("vec4 retina_shadow1D(sampler1DShadow s, vec3 c) {"
                + " return vec4(texture(s, c)); }\n");
        }
        if (collected.usedBuiltins.contains("retina_shadow1DLod")) {
            out.append("vec4 retina_shadow1DLod(sampler1DShadow s, vec3 c, float l) {"
                + " return vec4(textureLod(s, c, l)); }\n");
        }
        if (collected.usedBuiltins.contains("ftransform")) {
            out.append("vec4 ftransform() {"
                + " return retina_ModelViewProjectionMatrix * retina_Vertex; }\n");
        }

        out.append("// ==== end Retina prologue ====\n");
        return out.toString();
    }

    /** One member of the generated uniform block, as the prologue will declare it. */
    private record PrologueMember(String name, String glslType, int arrayLength) {
        String declaration() {
            return glslType + " " + name + (arrayLength > 0 ? "[" + arrayLength + "]" : "");
        }
    }

    private static void addIfAbsent(List<PrologueMember> members, String name, String type,
                                    int arrayLength) {
        for (PrologueMember member : members) {
            if (member.name().equals(name)) {
                return;
            }
        }
        members.add(new PrologueMember(name, type, arrayLength));
    }

    // ------------------------------------------------------------------
    // Version directive helpers
    // ------------------------------------------------------------------

    static int findVersionDirective(List<GlslLexer.Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.kind() == GlslLexer.Kind.DIRECTIVE
                && token.text().replaceFirst("^#\\s*", "").startsWith("version")) {
                return i;
            }
        }
        return -1;
    }

    static int parseVersion(String directive) {
        String rest = directive.replaceFirst("^#\\s*version\\s*", "").trim();
        int space = rest.indexOf(' ');
        String number = space < 0 ? rest : rest.substring(0, space);
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return 110;
        }
    }

    private static int endOfLine(List<GlslLexer.Token> tokens, int from) {
        for (int i = from; i < tokens.size(); i++) {
            if (tokens.get(i).kind() == GlslLexer.Kind.NEWLINE) {
                return i;
            }
        }
        return tokens.size() - 1;
    }

    /** The set of legacy builtins Retina translates, exposed for the compatibility matrix. */
    public static Optional<String> describeBuiltin(String name) {
        if (LegacyBuiltins.VERTEX_ATTRIBUTES.containsKey(name)) {
            return Optional.of("vertex attribute -> location "
                + LegacyBuiltins.VERTEX_ATTRIBUTES.get(name).location());
        }
        if (LegacyBuiltins.MATRIX_UNIFORMS.containsKey(name)) {
            return Optional.of("uniform block member "
                + LegacyBuiltins.MATRIX_UNIFORMS.get(name).glslName());
        }
        return Optional.empty();
    }
}
