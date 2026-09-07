package fun.autorun.safeexec.lite;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts the raw argument map a planner produced into a tool's input record. Strict on purpose:
 * unknown keys are rejected (a model that invents a field fails here), types are coerced only in obvious
 * ways (number → int/long/double/BigDecimal, string → enum), and nothing else is guessed.
 */
public final class RecordInputConverter {

    public <I> I convert(Map<String, Object> raw, Class<I> type) {
        if (!type.isRecord()) throw new InputConversionException("input type must be a record: " + type.getName());
        RecordComponent[] comps = type.getRecordComponents();
        Set<String> known = new HashSet<>();
        for (RecordComponent c : comps) known.add(c.getName());
        for (String k : raw.keySet()) if (!known.contains(k)) throw new InputConversionException("unknown field '" + k + "' for " + type.getSimpleName());

        Object[] args = new Object[comps.length];
        Class<?>[] types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            args[i] = coerce(comps[i].getName(), raw.get(comps[i].getName()), comps[i].getType());
        }
        try {
            Constructor<I> ctor = type.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new InputConversionException("cannot construct " + type.getSimpleName() + ": " + e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(String field, Object v, Class<?> t) {
        if (v == null) {
            if (t.isPrimitive()) throw new InputConversionException("missing required field '" + field + "'");
            return null;
        }
        if (t.isInstance(v) && !t.isPrimitive()) return v;
        if (t == String.class) return String.valueOf(v);
        if (v instanceof Number n) {
            if (t == int.class || t == Integer.class) return n.intValue();
            if (t == long.class || t == Long.class) return n.longValue();
            if (t == double.class || t == Double.class) return n.doubleValue();
            if (t == BigDecimal.class) return new BigDecimal(n.toString());
        }
        if (v instanceof Boolean b && (t == boolean.class || t == Boolean.class)) return b;
        if (t.isEnum() && v instanceof String s) {
            try { return Enum.valueOf((Class<? extends Enum>) t, s.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new InputConversionException("field '" + field + "': '" + s + "' is not one of " + java.util.Arrays.toString(t.getEnumConstants())); }
        }
        throw new InputConversionException("field '" + field + "': cannot convert " + v.getClass().getSimpleName() + " to " + t.getSimpleName());
    }

    public static final class InputConversionException extends RuntimeException {
        public InputConversionException(String m) { super(m); }
    }
}
