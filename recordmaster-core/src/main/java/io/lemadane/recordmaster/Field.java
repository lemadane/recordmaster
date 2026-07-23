package io.lemadane.recordmaster;

import java.util.function.Function;

public final class Field<T extends Record, V> {
    private final String name;
    private final Function<T, V> getter;

    public Field(String name, Function<T, V> getter) {
        this.name = name;
        this.getter = getter;
    }

    public String name() {
        return name;
    }

    public Function<T, V> getter() {
        return getter;
    }

    public Condition eq(V value) {
        return new EqualsCondition<>(this, value);
    }

    public Condition ne(V value) {
        return new NotEqualsCondition<>(this, value);
    }

    public Condition gt(V value) {
        return new ComparableCondition<>(this, value, ComparableOperator.GT);
    }

    public Condition gte(V value) {
        return new ComparableCondition<>(this, value, ComparableOperator.GTE);
    }

    public Condition lt(V value) {
        return new ComparableCondition<>(this, value, ComparableOperator.LT);
    }

    public Condition lte(V value) {
        return new ComparableCondition<>(this, value, ComparableOperator.LTE);
    }
}
