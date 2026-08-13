/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.render;

import dev.retina.core.translate.GlslLexer;
import dev.retina.core.translate.TranslatedSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adapts Retina's backend-neutral legacy GLSL to Sodium's compact terrain ABI. */
public final class TerrainShaderAdapter {
    private static final Pattern UNIFORM_BLOCK = Pattern.compile(
        "layout\\s*\\(\\s*std140\\s*,[^)]*\\)\\s*uniform\\s+RetinaUniforms\\s*\\{"
            + "(?<body>.*?)\\}\\s*;", Pattern.DOTALL);
    private static final Pattern MEMBER = Pattern.compile(
        "(?m)^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;\\s*$");
    private static final Pattern PUSH_BLOCK = Pattern.compile(
        "layout\\s*\\(\\s*push_constant\\s*\\)\\s*uniform\\s+RetinaDrawConstants\\s*\\{"
            + ".*?\\}\\s*retina_draw\\s*;", Pattern.DOTALL);
    private static final Pattern LEGACY_INPUT = Pattern.compile(
        "layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)\\s*in\\s+"
            + "(vec[234])\\s+(retina_(?:Vertex|Color|Normal|MultiTexCoord[01345]))\\s*;");
    private static final Pattern MAIN = Pattern.compile(
        "void\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");

    private TerrainShaderAdapter() {
    }

    /** Reads the generated block members before all programs are changed to the union block. */
    public static Map<String, String> collectUniformMembers(List<TranslatedSource> sources) {
        Map<String, String> members = new LinkedHashMap<>();
        for (TranslatedSource source : sources) {
            Matcher block = UNIFORM_BLOCK.matcher(source.text());
            if (!block.find()) {
                throw new IllegalArgumentException("translated shader has no RetinaUniforms block");
            }
            Matcher member = MEMBER.matcher(block.group("body"));
            while (member.find()) {
                String type = member.group(1);
                String name = member.group(2);
                String previous = members.putIfAbsent(name, type);
                if (previous != null && !previous.equals(type)) {
                    throw new IllegalArgumentException("uniform '" + name
                        + "' has incompatible types " + previous + " and " + type);
                }
            }
        }
        return members;
    }

    public static String adapt(TranslatedSource translated, TerrainUniformLayout uniforms,
                               boolean vertex) {
        String source = translated.text();
        Matcher block = UNIFORM_BLOCK.matcher(source);
        if (!block.find()) {
            throw new IllegalArgumentException("translated shader has no RetinaUniforms block");
        }
        String replacement = "layout(std140, binding = 0) uniform RetinaUniforms {\n"
            + uniforms.glslMembers() + "};";
        source = block.replaceFirst(Matcher.quoteReplacement(replacement));
        source = PUSH_BLOCK.matcher(source).replaceFirst(Matcher.quoteReplacement(
            "layout(push_constant) uniform RetinaSodiumDrawConstants {\n"
                + "    vec3 u_RegionOffset;\n"
                + "    int u_CurrentTime;\n"
                + "    uint u_RegionID;\n"
                + "} retina_sodium_draw;"));

        // Minecraft reflects every resource into one bind group and rewrites bindings by
        // name. Descriptor-set decorations from the generic translator would otherwise leave
        // the resources in sets the engine never binds.
        source = source.replaceAll("set\\s*=\\s*\\d+\\s*,\\s*", "")
            .replaceAll(",\\s*set\\s*=\\s*\\d+", "");
        source = rewriteResourceNames(source);
        if (vertex) {
            // Keep each used legacy value as a private variable in the generated prologue.
            // In particular ftransform() is emitted there and must see retina_Vertex before
            // its own function definition.
            source = LEGACY_INPUT.matcher(source).replaceAll("$1 $2;");
            source = injectVertexAbi(source);
        }
        return source;
    }

    private static String rewriteResourceNames(String source) {
        List<GlslLexer.Token> tokens = new java.util.ArrayList<>(GlslLexer.tokenize(source));
        for (int i = 0; i < tokens.size(); i++) {
            GlslLexer.Token token = tokens.get(i);
            if (token.kind() != GlslLexer.Kind.IDENTIFIER) {
                continue;
            }
            if (token.text().equals("gtexture")) {
                tokens.set(i, token.withText("u_BlockTex"));
            } else if (token.text().equals("lightmap")) {
                tokens.set(i, token.withText("u_LightTex"));
            }
        }
        return GlslLexer.render(tokens);
    }

    private static String injectVertexAbi(String source) {
        Matcher main = MAIN.matcher(source);
        if (!main.find()) {
            throw new IllegalArgumentException("terrain vertex shader has no main() function");
        }
        StringBuilder missingVariables = new StringBuilder();
        addIfMissing(source, missingVariables, "vec4", "retina_Vertex");
        addIfMissing(source, missingVariables, "vec4", "retina_Color");
        addIfMissing(source, missingVariables, "vec3", "retina_Normal");
        addIfMissing(source, missingVariables, "vec4", "retina_MultiTexCoord0");
        addIfMissing(source, missingVariables, "vec4", "retina_MultiTexCoord1");
        addIfMissing(source, missingVariables, "vec4", "retina_MultiTexCoord3");
        addIfMissing(source, missingVariables, "vec4", "retina_MultiTexCoord4");
        addIfMissing(source, missingVariables, "vec4", "retina_MultiTexCoord5");
        String declarations = """

            layout(location = 0) in uvec2 a_Position;
            layout(location = 1) in vec4 a_Color;
            layout(location = 2) in uvec2 a_TexCoord;
            layout(location = 3) in uvec4 a_LightAndData;
            layout(location = 4) in vec4 a_RetinaNormal;

            """ + missingVariables + """
            uvec3 retina_deinterleave_position(uvec2 data) {
                uvec3 hi = (uvec3(data.x) >> uvec3(0u, 10u, 20u)) & 0x3ffu;
                uvec3 lo = (uvec3(data.y) >> uvec3(0u, 10u, 20u)) & 0x3ffu;
                return (hi << 10u) | lo;
            }

            uvec3 retina_relative_chunk(uint packed) {
                return uvec3(packed) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
            }

            void retina_init_sodium_vertex() {
                vec3 localPosition = vec3(retina_deinterleave_position(a_Position))
                    * (32.0 / 1048576.0) - 8.0;
                vec3 translation = retina_sodium_draw.u_RegionOffset
                    + vec3(retina_relative_chunk(a_LightAndData.w)) * 16.0;
                vec2 diffuse = vec2(a_TexCoord & 0x7fffu) / 32768.0;
                vec2 light = vec2(a_LightAndData.xy) / 256.0;
                retina_Vertex = vec4(localPosition + translation, 1.0);
                retina_Color = a_Color;
                retina_Normal = normalize(a_RetinaNormal.xyz);
                retina_MultiTexCoord0 = vec4(diffuse, 0.0, 1.0);
                retina_MultiTexCoord1 = vec4(light, 0.0, 1.0);
                retina_MultiTexCoord3 = vec4(0.0);
                retina_MultiTexCoord4 = vec4(0.0);
                retina_MultiTexCoord5 = vec4(0.0);
            }

            """;
        source = source.substring(0, main.start()) + declarations + source.substring(main.start());
        main = MAIN.matcher(source);
        if (!main.find()) {
            throw new IllegalStateException("main() disappeared while injecting terrain ABI");
        }
        return source.substring(0, main.end()) + "\n    retina_init_sodium_vertex();"
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
