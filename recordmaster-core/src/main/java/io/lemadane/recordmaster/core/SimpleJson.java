package io.lemadane.recordmaster.core;

import java.util.*;

public final class SimpleJson {

    public static String serialize(Object obj) {
        StringBuilder sb = new StringBuilder();
        serializeVal(obj, sb);
        return sb.toString();
    }

    private static void serializeVal(Object val, StringBuilder sb) {
        if (val == null) {
            sb.append("null");
        } else if (val instanceof String || val instanceof UUID || val instanceof java.time.Instant || val.getClass().isEnum()) {
            sb.append('"').append(escape(val.toString())).append('"');
        } else if (val instanceof Number || val instanceof Boolean) {
            sb.append(val);
        } else if (val instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(entry.getKey().toString())).append("\":");
                serializeVal(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (val instanceof Collection<?> col) {
            sb.append('[');
            boolean first = true;
            for (Object item : col) {
                if (!first) sb.append(',');
                first = false;
                serializeVal(item, sb);
            }
            sb.append(']');
        } else if (val.getClass().isRecord()) {
            sb.append('{');
            boolean first = true;
            java.lang.reflect.RecordComponent[] components = val.getClass().getRecordComponents();
            for (java.lang.reflect.RecordComponent comp : components) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(comp.getName())).append("\":");
                try {
                    Object subVal = comp.getAccessor().invoke(val);
                    serializeVal(subVal, sb);
                } catch (Exception e) {
                    sb.append("null");
                }
            }
            sb.append('}');
        } else {
            // Treat as fallback string
            sb.append('"').append(escape(val.toString())).append('"');
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    public static Object parse(String json) {
        if (json == null) return null;
        return new Parser(json.trim()).parse();
    }

    private static class Parser {
        private final String src;
        private int pos = 0;

        public Parser(String src) {
            this.src = src;
        }

        public Object parse() {
            skipWhitespace();
            if (pos >= src.length()) return null;
            char ch = src.charAt(pos);
            if (ch == '{') return parseObject();
            if (ch == '[') return parseList();
            if (ch == '"') return parseString();
            if (Character.isDigit(ch) || ch == '-') return parseNumber();
            if (src.startsWith("true", pos)) { pos += 4; return true; }
            if (src.startsWith("false", pos)) { pos += 5; return false; }
            if (src.startsWith("null", pos)) { pos += 4; return null; }
            throw new RuntimeException("Unexpected character '" + ch + "' at position " + pos);
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // skip '{'
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != '"') {
                    throw new RuntimeException("Expected double-quoted key at position " + pos);
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != ':') {
                    throw new RuntimeException("Expected ':' at position " + pos);
                }
                pos++; // skip ':'
                Object val = parse();
                map.put(key, val);
                skipWhitespace();
                if (pos >= src.length()) {
                    throw new RuntimeException("Unclosed object");
                }
                char next = src.charAt(pos);
                if (next == '}') {
                    pos++;
                    break;
                } else if (next == ',') {
                    pos++;
                } else {
                    throw new RuntimeException("Expected ',' or '}' at position " + pos);
                }
            }
            return map;
        }

        private List<Object> parseList() {
            List<Object> list = new ArrayList<>();
            pos++; // skip '['
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object val = parse();
                list.add(val);
                skipWhitespace();
                if (pos >= src.length()) {
                    throw new RuntimeException("Unclosed list");
                }
                char next = src.charAt(pos);
                if (next == ']') {
                    pos++;
                    break;
                } else if (next == ',') {
                    pos++;
                } else {
                    throw new RuntimeException("Expected ',' or ']' at position " + pos);
                }
            }
            return list;
        }

        private String parseString() {
            pos++; // skip initial '"'
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char ch = src.charAt(pos);
                if (ch == '"') {
                    pos++;
                    return sb.toString();
                } else if (ch == '\\') {
                    pos++;
                    if (pos >= src.length()) throw new RuntimeException("Unescaped trailing backslash");
                    char escaped = src.charAt(pos);
                    switch (escaped) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 >= src.length()) throw new RuntimeException("Invalid unicode escape");
                            String hex = src.substring(pos + 1, pos + 5);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> sb.append(escaped);
                    }
                } else {
                    sb.append(ch);
                }
                pos++;
            }
            throw new RuntimeException("Unclosed string starting at " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            if (pos < src.length() && src.charAt(pos) == '-') {
                pos++;
            }
            boolean isDouble = false;
            while (pos < src.length()) {
                char ch = src.charAt(pos);
                if (Character.isDigit(ch)) {
                    pos++;
                } else if (ch == '.' || ch == 'e' || ch == 'E') {
                    isDouble = true;
                    pos++;
                } else {
                    break;
                }
            }
            String numStr = src.substring(start, pos);
            if (isDouble) {
                return Double.parseDouble(numStr);
            } else {
                try {
                    return Integer.parseInt(numStr);
                } catch (NumberFormatException e) {
                    return Long.parseLong(numStr);
                }
            }
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char ch = src.charAt(pos);
                if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }
    }
}
