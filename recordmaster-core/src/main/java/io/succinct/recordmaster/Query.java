package io.succinct.recordmaster;

import java.util.List;
import java.util.Optional;

public interface Query<T extends Record> {

    Query<T> where(Condition condition);

    Query<T> orderBy(SortOrder sortOrder);

    Query<T> limit(int limit);

    Query<T> offset(int offset);

    List<T> list();

    Optional<T> findFirst();

    QueryPlan explain();
}
