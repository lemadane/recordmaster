package io.lemadane.recordmaster;

public final class ComparableCondition<T extends Record, V> implements Condition {
    private final Field<T, V> field;
    private final V value;
    private final ComparableOperator operator;

    public ComparableCondition(Field<T, V> field, V value, ComparableOperator operator) {
        this.field = field;
        this.value = value;
        this.operator = operator;
    }

    public Field<T, V> field() {
        return field;
    }

    public V value() {
        return value;
    }

    public ComparableOperator operator() {
        return operator;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean test(Object record) {
        if (record == null) return false;
        try {
            V recordVal = field.getter().apply((T) record);
            if (recordVal == null || value == null) return false;
            if (recordVal instanceof Comparable && value.getClass().isInstance(recordVal)) {
                Comparable<Object> comp1 = (Comparable<Object>) recordVal;
                int cmp = comp1.compareTo(value);
                return switch (operator) {
                    case GT -> cmp > 0;
                    case GTE -> cmp >= 0;
                    case LT -> cmp < 0;
                    case LTE -> cmp <= 0;
                };
            }
            return false;
        } catch (ClassCastException e) {
            return false;
        }
    }
}
