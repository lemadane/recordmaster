package io.succinct.recordmaster;

public final class SortOrder {
    private final Field<?, ?> field;
    private final boolean ascending;

    public SortOrder(Field<?, ?> field, boolean ascending) {
        this.field = field;
        this.ascending = ascending;
    }

    public static SortOrder asc(Field<?, ?> field) {
        return new SortOrder(field, true);
    }

    public static SortOrder desc(Field<?, ?> field) {
        return new SortOrder(field, false);
    }

    public Field<?, ?> field() {
        return field;
    }

    public boolean ascending() {
        return ascending;
    }
}
