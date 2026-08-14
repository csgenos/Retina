/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.render;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a pack's own vertex attributes into defined values on vertex formats that lack them.
 *
 * <p>Packs declare attributes such as {@code mc_Entity}, {@code mc_midTexCoord},
 * {@code at_tangent} and {@code at_midBlock}. The translator gives each one a location, because
 * Vulkan GLSL rejects an input without one. None of the vertex formats Retina currently draws
 * through — Sodium's compact terrain format, Minecraft's entity and particle formats — supplies
 * them, and binding a pipeline whose shader reads an attribute the format does not provide is
 * invalid.
 *
 * <p>So each one is demoted here from an input to a zero-initialised global. The pack compiles
 * and runs, and reads a defined value rather than whatever happened to be in the slot. That is
 * a deliberate stand-in, not the finished feature: sourcing real block, tangent and mid-block
 * data is a separate piece of work, and until it lands an effect keyed on {@code mc_Entity}
 * behaves as though every block were untagged.
 */
public final class CustomAttributes {

    /**
     * A translator-emitted input at a custom location.
     *
     * <p>Locations below {@code CUSTOM_ATTRIBUTE_BASE} are the legacy {@code gl_*} slots, which
     * each adapter rewrites itself against the format it is targeting; only the pack's own
     * attributes are matched here.
     */
    private static final Pattern CUSTOM_INPUT = Pattern.compile(
        "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*in\\s+"
            + "([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)"
            + "\\s*(\\[\\s*[0-9]+\\s*\\])?\\s*;");

    private CustomAttributes() {
    }

    /**
     * Replaces every pack-declared vertex attribute with a zero-initialised global.
     *
     * @param source     the adapted vertex shader
     * @param baseLocation the first location the translator assigns to a pack attribute
     */
    public static String demote(String source, int baseLocation) {
        Matcher matcher = CUSTOM_INPUT.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        while (matcher.find()) {
            int location = Integer.parseInt(matcher.group(1));
            if (location < baseLocation) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String type = matcher.group(2);
            String name = matcher.group(3);
            String array = matcher.group(4);
            // An array attribute gets no initialiser: GLSL does not accept a scalar one, and
            // its value is then undefined rather than zero. Packs do not declare array vertex
            // attributes in practice, so this is a corner left honest rather than papered over.
            String declaration = array == null
                ? type + " " + name + " = " + zeroOf(type) + ";"
                : type + " " + name + array.replaceAll("\\s+", "") + ";";
            matcher.appendReplacement(out, Matcher.quoteReplacement(declaration));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** A zero-valued constructor call for {@code type}. */
    private static String zeroOf(String type) {
        // Every GLSL scalar, vector and matrix type accepts a single-component constructor, so
        // the only thing that varies is the literal's own type.
        String zero = switch (type.charAt(0)) {
            case 'i' -> "0";        // int, ivecN
            case 'u' -> "0u";       // uint, uvecN
            case 'b' -> "false";    // bool, bvecN
            default -> "0.0";       // float, double, vecN, dvecN, matN
        };
        return type + "(" + zero + ")";
    }
}
