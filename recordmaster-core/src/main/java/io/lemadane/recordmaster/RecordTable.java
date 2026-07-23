package io.lemadane.recordmaster;

import java.util.Optional;

public interface RecordTable<ID, T extends Record> {

    String tableName();

    Class<ID> idType();

    Class<T> entityType();

    Optional<T> findById(ID id);

    T insert(T record);

    T update(T record);

    T upsert(T record);

    boolean deleteById(ID id);

    void clear();

    Query<T> query();
}
