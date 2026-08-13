/*
 * Retina - a Vulkan shader-pack loader for Fabric and Sodium.
 * Copyright (C) 2026 the Retina contributors.
 * Licensed under the GNU Lesser General Public License v3.0 only.
 */
package dev.retina.core.translate;

import java.util.ArrayList;
import java.util.List;

/**
 * A lossless GLSL tokenizer.
 *
 * <p>Translation must never use plain textual substitution. {@code texture2D} appears inside
 * comments, inside identifiers such as {@code my_texture2D_helper}, and inside preprocessor
 * conditionals that are not taken; a global regex replace corrupts all three. Tokenizing
 * first makes every rewrite operate on whole tokens in code positions only, which is the
 * difference between a translator and a find-and-replace script.
 *
 * <p>The token stream is lossless: concatenating every token's text reproduces the input
 * byte for byte, so a translation pass can rewrite selected tokens and re-emit the rest
 * untouched, preserving line numbers for error mapping.
 */
public final class GlslLexer {

    /** What a token is. */
    public enum Kind {
        /** An identifier or keyword. */
        IDENTIFIER,
        /** A numeric literal, including suffixes and exponents. */
        NUMBER,
        /** Operators and punctuation, one token per maximal operator. */
        PUNCTUATION,
        /** Spaces and tabs. Newlines are {@link #NEWLINE}. */
        WHITESPACE,
        /** A single line terminator. */
        NEWLINE,
        /** A {@code //} comment, not including the terminator. */
        LINE_COMMENT,
        /** A {@code /* ... *&#47;} comment, possibly spanning lines. */
        BLOCK_COMMENT,
        /** A whole preprocessor directive line, including its {@code #}. */
        DIRECTIVE
    }

    /**
     * One token.
     *
     * @param kind what the token is
     * @param text the exact source text
     * @param line the 1-based line the token starts on
     */
    public record Token(Kind kind, String text, int line) {
        /** Whether this token is code rather than trivia. */
        public boolean isCode() {
            return switch (kind) {
                case IDENTIFIER, NUMBER, PUNCTUATION -> true;
                default -> false;
            };
        }

        /** Whether this is the identifier {@code name}. */
        public boolean isIdentifier(String name) {
            return kind == Kind.IDENTIFIER && text.equals(name);
        }

        /** Whether this is the punctuation {@code symbol}. */
        public boolean isPunctuation(String symbol) {
            return kind == Kind.PUNCTUATION && text.equals(symbol);
        }

        /** A copy with different text, keeping kind and line. */
        public Token withText(String newText) {
            return new Token(kind, newText, line);
        }
    }

    private GlslLexer() {
    }

    /** Tokenizes {@code source}. Never throws: malformed input becomes punctuation tokens. */
    public static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>(Math.max(16, source.length() / 4));
        int i = 0;
        int line = 1;
        int length = source.length();
        // A `#` only starts a directive when it is the first non-blank character on a line.
        boolean atLineStart = true;

        while (i < length) {
            char c = source.charAt(i);

            if (c == '\n') {
                tokens.add(new Token(Kind.NEWLINE, "\n", line));
                i++;
                line++;
                atLineStart = true;
                continue;
            }
            if (c == '\r') {
                // Normalise CRLF into one NEWLINE token so line counting cannot drift.
                String text = (i + 1 < length && source.charAt(i + 1) == '\n') ? "\r\n" : "\r";
                tokens.add(new Token(Kind.NEWLINE, text, line));
                i += text.length();
                line++;
                atLineStart = true;
                continue;
            }
            if (c == ' ' || c == '\t') {
                int start = i;
                while (i < length && (source.charAt(i) == ' ' || source.charAt(i) == '\t')) {
                    i++;
                }
                tokens.add(new Token(Kind.WHITESPACE, source.substring(start, i), line));
                continue;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int start = i;
                while (i < length && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                    i++;
                }
                tokens.add(new Token(Kind.LINE_COMMENT, source.substring(start, i), line));
                continue;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int start = i;
                int startLine = line;
                i += 2;
                while (i < length && !(source.charAt(i) == '*' && i + 1 < length
                    && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
                i = Math.min(length, i + 2);
                tokens.add(new Token(Kind.BLOCK_COMMENT, source.substring(start, i), startLine));
                atLineStart = false;
                continue;
            }
            if (c == '#' && atLineStart) {
                int start = i;
                int startLine = line;
                // A directive continues across backslash-newline, as in C.
                while (i < length) {
                    char d = source.charAt(i);
                    if (d == '\\' && i + 1 < length
                        && (source.charAt(i + 1) == '\n' || source.charAt(i + 1) == '\r')) {
                        i++;
                        if (source.charAt(i) == '\r' && i + 1 < length
                            && source.charAt(i + 1) == '\n') {
                            i++;
                        }
                        i++;
                        line++;
                        continue;
                    }
                    if (d == '\n' || d == '\r') {
                        break;
                    }
                    i++;
                }
                tokens.add(new Token(Kind.DIRECTIVE, source.substring(start, i), startLine));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < length && (Character.isLetterOrDigit(source.charAt(i))
                    || source.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(Kind.IDENTIFIER, source.substring(start, i), line));
                atLineStart = false;
                continue;
            }
            if (Character.isDigit(c)
                || (c == '.' && i + 1 < length && Character.isDigit(source.charAt(i + 1)))) {
                int start = i;
                boolean seenExponent = false;
                while (i < length) {
                    char d = source.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '.') {
                        if (d == 'e' || d == 'E') {
                            seenExponent = true;
                        } else if (d != 'x' && d != 'X' && !Character.isDigit(d)) {
                            seenExponent = false;
                        }
                        i++;
                        continue;
                    }
                    if ((d == '+' || d == '-') && seenExponent) {
                        seenExponent = false;
                        i++;
                        continue;
                    }
                    break;
                }
                tokens.add(new Token(Kind.NUMBER, source.substring(start, i), line));
                atLineStart = false;
                continue;
            }

            // Punctuation: take the longest matching multi-character operator.
            String op = matchOperator(source, i);
            tokens.add(new Token(Kind.PUNCTUATION, op, line));
            i += op.length();
            atLineStart = false;
        }
        return tokens;
    }

    private static final String[] OPERATORS = {
        "<<=", ">>=", "++", "--", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "^^",
        "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^="
    };

    private static String matchOperator(String source, int at) {
        for (String op : OPERATORS) {
            if (source.startsWith(op, at)) {
                return op;
            }
        }
        return String.valueOf(source.charAt(at));
    }

    /** Reassembles a token list into source text. */
    public static String render(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        for (Token token : tokens) {
            out.append(token.text());
        }
        return out.toString();
    }

    /** The index of the next code token at or after {@code from}, or -1. */
    public static int nextCode(List<Token> tokens, int from) {
        for (int i = from; i < tokens.size(); i++) {
            if (tokens.get(i).isCode()) {
                return i;
            }
        }
        return -1;
    }

    /** The index of the previous code token at or before {@code from}, or -1. */
    public static int previousCode(List<Token> tokens, int from) {
        for (int i = Math.min(from, tokens.size() - 1); i >= 0; i--) {
            if (tokens.get(i).isCode()) {
                return i;
            }
        }
        return -1;
    }
}
