package org.kelvinizer.profile;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存档模型的 JSON 读写：只用 Jackson 流式核心，按公共字段自动绑定，线程安全。
 * 不用 jackson-databind，是因为它在 Android 5.0–7.1 上会抛 {@code NoSuchMethodError}；读写的 JSON 与 databind 相同。
 *
 * <p>JSON binding for the profile model's plain public-field classes ({@link Profile},
 * {@link DeviceSettings}, {@link ProfileStore.Index} and everything they contain), built on
 * Jackson's streaming core only.
 *
 * <p><b>Why not jackson-databind.</b> Its bean introspection calls
 * {@code Method.getParameterCount()} / {@code Constructor.getParameterCount()}, which Android only
 * has from API 26 (desugaring does not cover {@code java.lang.reflect}). With minSdk 21 every
 * profile load or save would throw {@code NoSuchMethodError} on Android 5.0–7.1. This binder
 * uses nothing newer than API 9 ({@link Field}, {@link Field#getGenericType()}, the public
 * no-argument constructor), and it reads and writes the same JSON databind wrote for these
 * classes, so existing saves and export codes stay valid.
 *
 * <p><b>Mapping</b> (the subset of databind's defaults the model uses):
 * <ul>
 *   <li>every public, non-static, non-transient field is a property under its own name; unknown
 *       keys are skipped and absent keys keep the constructor's default;</li>
 *   <li>supported field types: {@code int}, {@code long}, {@code float}, {@code double},
 *       {@code boolean} (and their boxes), {@code String}, enums (by name), {@code List} /
 *       {@code Set} / {@code Collection} of a supported type, {@code Map<String, V>}, arrays, and
 *       nested classes with a public no-argument constructor;</li>
 *   <li>scalar coercions follow databind's defaults: numbers from numeric strings, floats truncated
 *       into integer fields, booleans from {@code "true"}/{@code "false"} and from 0/1, any scalar
 *       into a {@code String}; JSON {@code null} sets a primitive to 0 / false and an object field
 *       to null (each class's {@code sanitize()} repairs it);</li>
 *   <li>anything else (an object where a number belongs, an unparsable number, truncated JSON)
 *       throws {@link IOException}, which {@link ProfileStore} treats as a corrupt file.</li>
 * </ul>
 * Thread-safe: the factory and the per-class field tables are shared.
 */
final class PlainJson {
    private static final JsonFactory FACTORY = new JsonFactory();
    /** Bound fields per class, in {@link Class#getFields()} order. */
    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();
    /** The same fields by JSON name. */
    private static final Map<Class<?>, Map<String, Field>> FIELDS_BY_NAME = new ConcurrentHashMap<>();

    private PlainJson() {
    }

    /**
     * @param pretty true for the indented layout of the files on disk (databind's default
     *               pretty printer), false for the compact export code
     * @return {@code value} as UTF-8 JSON
     */
    static byte[] write(Object value, boolean pretty) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        try (JsonGenerator g = FACTORY.createGenerator(out, JsonEncoding.UTF8)) {
            if (pretty) g.useDefaultPrettyPrinter();
            writeValue(g, value);
        }
        return out.toByteArray();
    }

    /** @return the value of {@code type} in {@code json} (encoding detected), or null for JSON {@code null} */
    static <T> T read(byte[] json, Class<T> type) throws IOException {
        try (JsonParser p = FACTORY.createParser(json)) {
            return readRoot(p, type);
        }
    }

    /** @return the value of {@code type} read from {@code reader}, or null for JSON {@code null} */
    static <T> T read(Reader reader, Class<T> type) throws IOException {
        try (JsonParser p = FACTORY.createParser(reader)) {
            return readRoot(p, type);
        }
    }

    private static <T> T readRoot(JsonParser p, Class<T> type) throws IOException {
        if (p.nextToken() == null) throw new IOException("no JSON content");
        // Like databind's default, whatever follows the first value is ignored.
        return type.cast(readValue(p, type, type.getSimpleName()));
    }

    // ------------------------------------------------------------------ writing

    private static void writeValue(JsonGenerator g, Object v) throws IOException {
        if (v == null) {
            g.writeNull();
        } else if (v instanceof String) {
            g.writeString((String) v);
        } else if (v instanceof Boolean) {
            g.writeBoolean((Boolean) v);
        } else if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
            g.writeNumber(((Number) v).intValue());
        } else if (v instanceof Long) {
            g.writeNumber((Long) v);
        } else if (v instanceof Float) {
            g.writeNumber((Float) v);
        } else if (v instanceof Double) {
            g.writeNumber((Double) v);
        } else if (v instanceof Enum) {
            g.writeString(((Enum<?>) v).name());
        } else if (v instanceof Map) {
            g.writeStartObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (e.getKey() == null) throw new IOException("null map key");
                g.writeFieldName(e.getKey().toString());
                writeValue(g, e.getValue());
            }
            g.writeEndObject();
        } else if (v instanceof Collection) {
            g.writeStartArray();
            for (Object o : (Collection<?>) v) writeValue(g, o);
            g.writeEndArray();
        } else if (v.getClass().isArray()) {
            g.writeStartArray();
            int n = Array.getLength(v);
            for (int i = 0; i < n; i++) writeValue(g, Array.get(v, i));
            g.writeEndArray();
        } else {
            g.writeStartObject();
            for (Field f : fields(v.getClass())) {
                g.writeFieldName(f.getName());
                try {
                    writeValue(g, f.get(v));
                } catch (IllegalAccessException e) {
                    throw new IOException("cannot read " + f.getName(), e);
                }
            }
            g.writeEndObject();
        }
    }

    // ------------------------------------------------------------------ reading

    /** Reads the value at the parser's current token; {@code where} names it in error messages. */
    private static Object readValue(JsonParser p, Type type, String where) throws IOException {
        Class<?> raw = rawClass(type);
        if (p.currentToken() == JsonToken.VALUE_NULL) return raw.isPrimitive() ? zero(raw) : null;
        if (raw == String.class) return readText(p, where);
        if (raw == int.class || raw == Integer.class) {
            return (int) readLong(p, Integer.MIN_VALUE, Integer.MAX_VALUE, where);
        }
        if (raw == long.class || raw == Long.class) return readLong(p, Long.MIN_VALUE, Long.MAX_VALUE, where);
        if (raw == short.class || raw == Short.class) {
            return (short) readLong(p, Short.MIN_VALUE, Short.MAX_VALUE, where);
        }
        if (raw == byte.class || raw == Byte.class) return (byte) readLong(p, Byte.MIN_VALUE, Byte.MAX_VALUE, where);
        if (raw == float.class || raw == Float.class) return (float) readDouble(p, where);
        if (raw == double.class || raw == Double.class) return readDouble(p, where);
        if (raw == boolean.class || raw == Boolean.class) return readBoolean(p, where);
        if (raw.isEnum()) return readEnum(p, raw, where);
        if (Map.class.isAssignableFrom(raw)) {
            if (typeArgument(type, 0, where) != String.class) {
                throw new IOException("map keys must be strings at " + where);
            }
            return readMap(p, raw, typeArgument(type, 1, where), where);
        }
        if (Collection.class.isAssignableFrom(raw)) return readCollection(p, raw, typeArgument(type, 0, where), where);
        if (raw.isArray()) return readArray(p, type, raw, where);
        return readObject(p, raw, where);
    }

    private static Object readObject(JsonParser p, Class<?> cls, String where) throws IOException {
        expect(p, JsonToken.START_OBJECT, where);
        Object o = newInstance(cls);
        Map<String, Field> byName = fieldsByName(cls);
        JsonToken t;
        while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            p.nextToken();
            Field f = byName.get(name);
            if (f == null) {
                p.skipChildren();
                continue;
            }
            Object v = readValue(p, f.getGenericType(), name);
            try {
                f.set(o, v);
            } catch (IllegalAccessException | IllegalArgumentException e) {
                throw new IOException("cannot set " + name, e);
            }
        }
        if (t != JsonToken.END_OBJECT) throw new IOException("unterminated object at " + where);
        return o;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object readMap(JsonParser p, Class<?> raw, Type valueType, String where) throws IOException {
        expect(p, JsonToken.START_OBJECT, where);
        Map map = raw.isInterface() || Modifier.isAbstract(raw.getModifiers())
            ? new LinkedHashMap<>() : (Map) newInstance(raw);
        JsonToken t;
        while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
            String key = p.currentName();
            p.nextToken();
            map.put(key, readValue(p, valueType, key));
        }
        if (t != JsonToken.END_OBJECT) throw new IOException("unterminated object at " + where);
        return map;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object readCollection(JsonParser p, Class<?> raw, Type elementType, String where)
        throws IOException {
        expect(p, JsonToken.START_ARRAY, where);
        Collection c;
        if (!raw.isInterface() && !Modifier.isAbstract(raw.getModifiers())) c = (Collection) newInstance(raw);
        else if (Set.class.isAssignableFrom(raw)) c = new LinkedHashSet<>();
        else c = new ArrayList<>();
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
            if (t == null) throw new IOException("unterminated array at " + where);
            c.add(readValue(p, elementType, where));
        }
        return c;
    }

    private static Object readArray(JsonParser p, Type type, Class<?> raw, String where) throws IOException {
        Type component = type instanceof GenericArrayType
            ? ((GenericArrayType) type).getGenericComponentType() : raw.getComponentType();
        List<?> items = (List<?>) readCollection(p, ArrayList.class, component, where);
        Object array = Array.newInstance(raw.getComponentType(), items.size());
        for (int i = 0; i < items.size(); i++) {
            Object v = items.get(i);
            Array.set(array, i, v == null && raw.getComponentType().isPrimitive() ? zero(raw.getComponentType()) : v);
        }
        return array;
    }

    private static String readText(JsonParser p, String where) throws IOException {
        if (p.currentToken().isScalarValue()) return p.getText();
        throw new IOException("expected text at " + where);
    }

    private static long readLong(JsonParser p, long min, long max, String where) throws IOException {
        long v;
        switch (p.currentToken()) {
            case VALUE_NUMBER_INT:
                if (p.getNumberType() == JsonParser.NumberType.BIG_INTEGER) throw outOfRange(where);
                v = p.getLongValue();
                break;
            case VALUE_NUMBER_FLOAT:
                v = truncate(p.getDoubleValue(), where);
                break;
            case VALUE_STRING:
                v = parseLong(p.getText().trim(), where);
                break;
            default:
                throw new IOException("expected a number at " + where);
        }
        if (v < min || v > max) throw outOfRange(where);
        return v;
    }

    private static long parseLong(String s, String where) throws IOException {
        if (s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            try {
                return truncate(Double.parseDouble(s), where);
            } catch (NumberFormatException e2) {
                throw new IOException("not a number at " + where + ": " + s, e2);
            }
        }
    }

    private static long truncate(double d, String where) throws IOException {
        if (Double.isNaN(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) throw outOfRange(where);
        return (long) d;
    }

    private static double readDouble(JsonParser p, String where) throws IOException {
        switch (p.currentToken()) {
            case VALUE_NUMBER_INT:
            case VALUE_NUMBER_FLOAT:
                return p.getDoubleValue();
            case VALUE_STRING:
                String s = p.getText().trim();
                if (s.isEmpty()) return 0.0;
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    throw new IOException("not a number at " + where + ": " + s, e);
                }
            default:
                throw new IOException("expected a number at " + where);
        }
    }

    private static boolean readBoolean(JsonParser p, String where) throws IOException {
        switch (p.currentToken()) {
            case VALUE_TRUE:
                return true;
            case VALUE_FALSE:
                return false;
            case VALUE_NUMBER_INT:
                return p.getLongValue() != 0L;
            case VALUE_STRING:
                String s = p.getText().trim();
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s) || s.isEmpty()) return false;
                throw new IOException("not a boolean at " + where + ": " + s);
            default:
                throw new IOException("expected a boolean at " + where);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object readEnum(JsonParser p, Class<?> raw, String where) throws IOException {
        String s = readText(p, where);
        try {
            return Enum.valueOf((Class) raw, s);
        } catch (IllegalArgumentException e) {
            throw new IOException("unknown " + raw.getSimpleName() + " at " + where + ": " + s, e);
        }
    }

    private static void expect(JsonParser p, JsonToken token, String where) throws IOException {
        if (p.currentToken() != token) {
            throw new IOException("expected " + (token == JsonToken.START_OBJECT ? "an object" : "an array")
                + " at " + where + " but found " + p.currentToken());
        }
    }

    private static IOException outOfRange(String where) {
        return new IOException("number out of range at " + where);
    }

    // ------------------------------------------------------------------ reflection

    /** @return the bound fields of {@code cls}: public, non-static, non-transient, non-synthetic */
    private static Field[] fields(Class<?> cls) {
        Field[] cached = FIELDS.get(cls);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<>();
        for (Field f : cls.getFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isTransient(m) || f.isSynthetic()) continue;
            out.add(f);
        }
        Field[] result = out.toArray(new Field[0]);
        FIELDS.put(cls, result);
        return result;
    }

    private static Map<String, Field> fieldsByName(Class<?> cls) {
        Map<String, Field> cached = FIELDS_BY_NAME.get(cls);
        if (cached != null) return cached;
        Map<String, Field> byName = new LinkedHashMap<>();
        for (Field f : fields(cls)) byName.put(f.getName(), f);
        FIELDS_BY_NAME.put(cls, byName);
        return byName;
    }

    private static Object newInstance(Class<?> cls) throws IOException {
        try {
            Constructor<?> c = cls.getConstructor();
            return c.newInstance();
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                 | InvocationTargetException e) {
            throw new IOException("cannot create " + cls.getSimpleName(), e);
        }
    }

    private static Class<?> rawClass(Type type) throws IOException {
        if (type instanceof Class) return (Class<?>) type;
        if (type instanceof ParameterizedType) return rawClass(((ParameterizedType) type).getRawType());
        if (type instanceof GenericArrayType) {
            return Array.newInstance(rawClass(((GenericArrayType) type).getGenericComponentType()), 0).getClass();
        }
        throw new IOException("unsupported field type " + type);
    }

    /** @return type argument {@code index} of a parameterised collection or map type */
    private static Type typeArgument(Type type, int index, String where) throws IOException {
        if (type instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            if (index < args.length) return args[index];
        }
        throw new IOException("collection without element type at " + where);
    }

    private static Object zero(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.FALSE;
        if (primitive == long.class) return 0L;
        if (primitive == float.class) return 0f;
        if (primitive == double.class) return 0.0;
        if (primitive == short.class) return (short) 0;
        if (primitive == byte.class) return (byte) 0;
        if (primitive == char.class) return (char) 0;
        return 0;
    }
}
