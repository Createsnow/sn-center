package com.adtek.framework.audit;

import java.util.*;

/** 只接收受控字段快照，不接受 DTO/Entity；即使调用者误传密钥字段也拒绝。 */
public final class AuditContext {
    private static final ThreadLocal<Change> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Set<String>> FIELDS = new ThreadLocal<>();

    private AuditContext() {}

    record Change(String objectId, Map<String, Object> diff) {}

    static void begin(Set<String> fields) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("Nested management audit");
        }
        if (fields.stream()
                .anyMatch(
                        field ->
                                !field.matches("[A-Za-z][A-Za-z0-9]*")
                                        || field.toLowerCase(Locale.ROOT)
                                                .matches(".*(hash|token|secret|credential).*"))) {
            throw new IllegalArgumentException("Sensitive audit field declaration");
        }
        FIELDS.set(Set.copyOf(fields));
        CURRENT.set(new Change(null, Map.of()));
    }

    static Change get() {
        return CURRENT.get();
    }

    static void clear() {
        CURRENT.remove();
        FIELDS.remove();
    }

    public static void record(
            String objectId, Map<String, Object> before, Map<String, Object> after) {
        if (CURRENT.get() == null) {
            throw new IllegalStateException("Missing @OperLog boundary");
        }
        Set<String> fields = new HashSet<>(before.keySet());
        fields.addAll(after.keySet());
        if (!FIELDS.get().containsAll(fields)) {
            throw new IllegalArgumentException("Unapproved audit field");
        }
        Map<String, Object> changes = new TreeMap<>();
        for (String key : fields) {
            Object previous = before.get(key), next = after.get(key);
            if (key.toLowerCase(Locale.ROOT).contains("password")
                    && ((previous != null && !(previous instanceof Boolean))
                            || (next != null && !(next instanceof Boolean)))) {
                throw new IllegalArgumentException("Password material cannot enter audit");
            }
            validate(previous);
            validate(next);
            if (!Objects.equals(previous, next)) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("before", previous);
                diff.put("after", next);
                changes.put(key, diff);
            }
        }
        CURRENT.set(new Change(objectId, Collections.unmodifiableMap(changes)));
    }

    private static void validate(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return;
        }
        if (value instanceof List<?> items && items.stream().allMatch(String.class::isInstance)) {
            return;
        }
        throw new IllegalArgumentException("Unapproved audit value");
    }
}
