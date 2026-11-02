package sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Минимальная работа с JSON без внешних библиотек.
 * Объекты — LinkedHashMap (порядок ключей сохраняется), массивы — ArrayList,
 * числа — Long (целые) или Double, остальное — String, Boolean, null.
 * Spring Boot с Jackson заменит этот класс на следующем этапе практики.
 */
public final class Json {
    private Json() {}

    // ---------------------------------------------------------------- разбор

    public static Object parse(String text) {
        int[] pos = {0};
        skipSpace(text, pos);
        Object value = readValue(text, pos);
        skipSpace(text, pos);
        if (pos[0] != text.length()) {
            throw new IllegalArgumentException("Лишние символы после JSON в позиции " + pos[0]);
        }
        return value;
    }

    private static void skipSpace(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) {
            p[0]++;
        }
    }

    private static Object readValue(String s, int[] p) {
        if (p[0] >= s.length()) {
            throw new IllegalArgumentException("Неожиданный конец JSON");
        }
        char c = s.charAt(p[0]);
        switch (c) {
            case '{': return readObject(s, p);
            case '[': return readArray(s, p);
            case '"': return readString(s, p);
            case 't': expect(s, p, "true"); return Boolean.TRUE;
            case 'f': expect(s, p, "false"); return Boolean.FALSE;
            case 'n': expect(s, p, "null"); return null;
            default: return readNumber(s, p);
        }
    }

    private static void expect(String s, int[] p, String word) {
        if (!s.startsWith(word, p[0])) {
            throw new IllegalArgumentException("Ожидалось " + word + " в позиции " + p[0]);
        }
        p[0] += word.length();
    }

    private static Map<String, Object> readObject(String s, int[] p) {
        Map<String, Object> map = new LinkedHashMap<>();
        p[0]++;
        skipSpace(s, p);
        if (s.charAt(p[0]) == '}') {
            p[0]++;
            return map;
        }
        while (true) {
            skipSpace(s, p);
            String key = readString(s, p);
            skipSpace(s, p);
            if (s.charAt(p[0]) != ':') {
                throw new IllegalArgumentException("Ожидалось ':' в позиции " + p[0]);
            }
            p[0]++;
            skipSpace(s, p);
            map.put(key, readValue(s, p));
            skipSpace(s, p);
            char c = s.charAt(p[0]++);
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Ожидалось ',' или '}' в позиции " + (p[0] - 1));
            }
        }
    }

    private static List<Object> readArray(String s, int[] p) {
        List<Object> list = new ArrayList<>();
        p[0]++;
        skipSpace(s, p);
        if (s.charAt(p[0]) == ']') {
            p[0]++;
            return list;
        }
        while (true) {
            skipSpace(s, p);
            list.add(readValue(s, p));
            skipSpace(s, p);
            char c = s.charAt(p[0]++);
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Ожидалось ',' или ']' в позиции " + (p[0] - 1));
            }
        }
    }

    private static String readString(String s, int[] p) {
        if (s.charAt(p[0]) != '"') {
            throw new IllegalArgumentException("Ожидалась строка в позиции " + p[0]);
        }
        p[0]++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(p[0]++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(p[0]++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16));
                    p[0] += 4;
                    break;
                default: sb.append(e);
            }
        }
    }

    private static Object readNumber(String s, int[] p) {
        int start = p[0];
        boolean real = false;
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]);
            if (c == '.' || c == 'e' || c == 'E') {
                real = true;
            } else if (!(c == '-' || c == '+' || Character.isDigit(c))) {
                break;
            }
            p[0]++;
        }
        String num = s.substring(start, p[0]);
        if (num.isEmpty()) {
            throw new IllegalArgumentException("Ожидалось значение в позиции " + start);
        }
        return real ? (Object) Double.valueOf(num) : (Object) Long.valueOf(num);
    }

    // ---------------------------------------------------------------- запись

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, int level) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
            sb.append(v);
        } else if (v instanceof Double) {
            double d = (Double) v;
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("Число не представимо в JSON: " + d);
            }
            sb.append(Double.toString(d)); // Double.toString не зависит от локали
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                indent(sb, level + 1);
                writeString(sb, e.getKey());
                sb.append(": ");
                writeValue(sb, e.getValue(), level + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            indent(sb, level);
            sb.append('}');
        } else if (v instanceof List) {
            List<Object> list = (List<Object>) v;
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            if (isFlat(list)) { // короткие списки чисел и строк — в одну строку
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    writeValue(sb, list.get(i), level);
                }
                sb.append(']');
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(sb, level + 1);
                writeValue(sb, list.get(i), level + 1);
                sb.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            indent(sb, level);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Тип не поддерживается: " + v.getClass());
        }
    }

    private static boolean isFlat(List<?> list) {
        for (Object o : list) {
            if (o instanceof Map || o instanceof List) {
                if (!(o instanceof List && isNumberPair((List<?>) o))) {
                    return false;
                }
            }
        }
        return list.size() <= 8;
    }

    private static boolean isNumberPair(List<?> l) {
        return l.size() == 2 && l.get(0) instanceof Number && l.get(1) instanceof Number;
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------ доступ к данным

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("Ожидался объект JSON");
        }
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        if (!(o instanceof List)) {
            throw new IllegalArgumentException("Ожидался массив JSON");
        }
        return (List<Object>) o;
    }

    public static String str(Object o) {
        if (!(o instanceof String)) {
            throw new IllegalArgumentException("Ожидалась строка JSON");
        }
        return (String) o;
    }

    public static double num(Object o) {
        if (!(o instanceof Number)) {
            throw new IllegalArgumentException("Ожидалось число JSON");
        }
        return ((Number) o).doubleValue();
    }

    public static long integer(Object o) {
        if (!(o instanceof Long)) {
            throw new IllegalArgumentException("Ожидалось целое число JSON");
        }
        return (Long) o;
    }

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
