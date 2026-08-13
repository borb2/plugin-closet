package me.sirborb.plugincloset.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer.
 *
 * <p>ponytail: Paper ships Gson, but reaching into a server's internal classpath is
 * exactly what breaks on a Paper update. We read about a dozen fields and write one small
 * manifest, so a parser is smaller than the risk. Swap in a real library if this ever
 * needs streaming, big integers, or lenient input.
 *
 * <p>Values are {@code Map<String,Object>}, {@code List<Object>}, {@code String},
 * {@code Double}, {@code Boolean}, or null.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) {
            throw new IllegalArgumentException("trailing content at offset " + p.i);
        }
        return v;
    }

    private Object value() {
        char c = peek();
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // consume {
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') throw err("expected object key");
            String k = string();
            ws();
            if (peek() != ':') throw err("expected colon");
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            i++;
            if (c == '}') return m;
            if (c != ',') throw err("expected comma or close brace");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; // consume [
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ']') return l;
            if (c != ',') throw err("expected comma or close bracket");
        }
    }

    private String string() {
        i++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw err("unterminated escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                default -> throw err("bad escape character " + e);
            }
        }
    }

    private Double number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            boolean partOfNumber = (c >= '0' && c <= '9')
                    || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
            if (!partOfNumber) break;
            i++;
        }
        if (start == i) throw err("expected a value");
        return Double.parseDouble(s.substring(start, i));
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw err("expected " + word);
        i += word.length();
        return v;
    }

    private char peek() {
        if (i >= s.length()) throw err("unexpected end of input");
        return s.charAt(i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException(msg + " (offset " + i + ")");
    }

    // --- typed accessors: each tolerates a missing or wrong-typed field ---

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    public static Map<String, Object> child(Object o, String key) {
        return obj(obj(o).get(key));
    }

    public static List<Object> children(Object o, String key) {
        return arr(obj(o).get(key));
    }

    public static String str(Object o, String key) {
        Object v = obj(o).get(key);
        return v instanceof String text ? text : null;
    }

    public static long num(Object o, String key) {
        Object v = obj(o).get(key);
        return v instanceof Double d ? d.longValue() : 0L;
    }

    /** Strings of an array-valued field, skipping any non-string entries. */
    public static List<String> strings(Object o, String key) {
        List<String> out = new ArrayList<>();
        for (Object v : children(o, key)) {
            if (v instanceof String text) out.add(text);
        }
        return out;
    }

    // --- writing (only the install manifest needs this) ---

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        writeTo(b, v);
        return b.toString();
    }

    private static void writeTo(StringBuilder b, Object v) {
        switch (v) {
            case null -> b.append("null");
            case String text -> quote(b, text);
            case Boolean bool -> b.append(bool);
            case Number n -> b.append(n);
            case Map<?, ?> m -> {
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    quote(b, String.valueOf(e.getKey()));
                    b.append(':');
                    writeTo(b, e.getValue());
                }
                b.append('}');
            }
            case Iterable<?> it -> {
                b.append('[');
                boolean first = true;
                for (Object o : it) {
                    if (!first) b.append(',');
                    first = false;
                    writeTo(b, o);
                }
                b.append(']');
            }
            default -> quote(b, String.valueOf(v));
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int j = 0; j < s.length(); j++) {
            char c = s.charAt(j);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
