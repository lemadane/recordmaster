package io.lemadane.recordmaster;

import java.util.Objects;

public final class EqualsCondition<T extends Record, V> implements Condition {
    private final Field<T, V> field;
    private final V value;

    public EqualsCondition(Field<T, V> field, V value) {
        this.field = field;
        this.value = value;
    }

    public Field<T, V> field() {
        return field;
    }

    public V value() {
        return value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean test(Object record) {
        if (record == null) return false;
        try {
            V recordVal = field.getter().apply((T) record);
            return Objects.equals(recordVal, value);
        } catch (ClassCastException e) {
            return false;
        }
    }
}
