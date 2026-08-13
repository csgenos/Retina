/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.render;

import dev.retina.core.translate.GlslLexer;
import dev.retina.core.translate.TranslatedSource;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adapts translated composite-style GLSL to a vertex-buffer-free fullscreen triangle. */
public final class FullscreenShaderAdapter {
    private static final Pattern UNIFORM_BLOCK = Pattern.compile(
        "layout\\s*\\(\\s*std140\\s*,[^)]*\\)\\s*uniform\\s+RetinaUniforms\\s*\\{"
            + ".*?\\}\\s*;", Pattern.DOTALL);
    private static final Pattern PUSH_BLOCK = Pattern.compile(
        "layout\\s*\\(\\s*push_constant\\s*\\)\\s*uniform\\s+RetinaDrawConstants\\s*\\{"
            + ".*?\\}\\s*retina_draw\\s*;", Pattern.DOTALL);
    private static final Pattern LEGACY_INPUT = Pattern.compile(
        "layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)\\s*in\\s+"
            + "(vec[234])\\s+(retina_(?:Vertex|Color|Normal|MultiTexCoord[01345]))\\s*;");
    private static final Pattern MAIN = Pattern.compile(
        "void\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    private static final Pattern FTRANSFORM = Pattern.compile(
        "vec4\\s+ftransform\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+"
            + "retina_ModelViewProjectionMatrix\\s*\\*\\s*retina_Vertex\\s*;\\s*\\}");

    private FullscreenShaderAdapter() {
    }

    public static String adapt(TranslatedSource translated, TerrainUniformLayout uniforms,
                               boolean vertex) {
        String source = translated.text();
        Matcher block = UNIFORM_BLOCK.matcher(source);
        if (!block.find()) {
            throw new IllegalArgumentException("translated post shader has no RetinaUniforms block");
        }
        source = block.replaceFirst(Matcher.quoteReplacement(
            "layout(std140, binding = 0) uniform RetinaUniforms {\n"
                + uniforms.glslMembers() + "};"));
        source = PUSH_BLOCK.matcher(source).replaceFirst("");
        source = source.replaceAll("set\\s*=\\s*\\d+\\s*,\\s*", "")
            .replaceAll(",\\s*set\\s*=\\s*\\d+", "");
        source = canonicaliseSamplers(source);
        if (vertex) {
            source = LEGACY_INPUT.matcher(source).replaceAll("$1 $2;");
            // The generated vertex is already in Vulkan clip space. The generic legacy
            // helper is correct for geometry, but applying the world camera matrix here
            // collapses the fullscreen triangle into the scene.
            source = FTRANSFORM.matcher(source)
                .replaceFirst("vec4 ftransform() { return retina_Vertex; }");
            source = injectFullscreenAbi(source);
        }
        return source;
    }

    private static String canonicaliseSamplers(String source) {
        List<GlslLexer.Token> tokens = new java.util.ArrayList<>(GlslLexer.tokenize(source));
        for (int i = 0; i < tokens.size(); i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER) {
                continue;
            }
            int next = GlslLexer.nextCode(tokens, i + 1);
            if (token.text().equals("texture") && next >= 0
                && tokens.get(next).isPunctuation("(")) {
                // `texture` is both the modern GLSL sampling function and a legacy atlas
                // sampler alias. Only the latter is a resource name.
                continue;
            }
            String canonical = dev.retina.core.translate.BindingLayout.canonicalName(token.text());
            if (!canonical.equals(token.text())) {
                tokens.set(i, token.withText(canonical));
            }
        }
        return GlslLexer.render(tokens);
    }

    private static String injectFullscreenAbi(String source) {
        Matcher main = MAIN.matcher(source);
        if (!main.find()) {
            throw new IllegalArgumentException("post vertex shader has no main() function");
        }
        StringBuilder variables = new StringBuilder();
        addIfMissing(source, variables, "vec4", "retina_Vertex");
        addIfMissing(source, variables, "vec4", "retina_Color");
        addIfMissing(source, variables, "vec3", "retina_Normal");
        addIfMissing(source, variables, "vec4", "retina_MultiTexCoord0");
        addIfMissing(source, variables, "vec4", "retina_MultiTexCoord1");
        addIfMissing(source, variables, "vec4", "retina_MultiTexCoord3");
        addIfMissing(source, variables, "vec4", "retina_MultiTexCoord4");
        addIfMissing(source, variables, "vec4", "retina_MultiTexCoord5");
        String declarations = """

            %s
            void retina_init_fullscreen_vertex() {
                vec2 uv = vec2(float((gl_VertexIndex << 1) & 2),
                               float(gl_VertexIndex & 2));
                retina_Vertex = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
                retina_Color = vec4(1.0);
                retina_Normal = vec3(0.0, 0.0, 1.0);
                retina_MultiTexCoord0 = vec4(uv, 0.0, 1.0);
                retina_MultiTexCoord1 = vec4(uv, 0.0, 1.0);
                retina_MultiTexCoord3 = vec4(0.0);
                retina_MultiTexCoord4 = vec4(0.0);
                retina_MultiTexCoord5 = vec4(0.0);
            }

            """.formatted(variables);
        source = source.substring(0, main.start()) + declarations + source.substring(main.start());
        main = MAIN.matcher(source);
        if (!main.find()) {
            throw new IllegalStateException("main() disappeared while injecting fullscreen ABI");
        }
        return source.substring(0, main.end()) + "\n    retina_init_fullscreen_vertex();"
            + source.substring(main.end());
    }

    private static void addIfMissing(String source, StringBuilder out, String type,
                                     String name) {
        Pattern declaration = Pattern.compile("\\b" + Pattern.quote(type) + "\\s+"
            + Pattern.quote(name) + "\\s*;");
        if (!declaration.matcher(source).find()) {
            out.append(type).append(' ').append(name).append(";\n");
        }
    }
}
