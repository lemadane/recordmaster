package io.succinct.recordmaster.core;

import io.succinct.recordmaster.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class QueryEngine<T extends io.succinct.recordmaster.Record> implements Query<T> {
    private final String tableName;
    private final TableState committedTable;
    private final TableChangeSet staging;
    private final IndexChangeSet indexStaging;
    private final RecordDatabase db;
    private final List<Condition> conditions = new ArrayList<>();
    private SortOrder sortOrder;
    private int limit = -1;
    private int offset = 0;

    public QueryEngine(String tableName, TableState committedTable, TableChangeSet staging, IndexChangeSet indexStaging, RecordDatabase db) {
        this.tableName = tableName;
        this.committedTable = committedTable;
        this.staging = staging;
        this.indexStaging = indexStaging;
        this.db = db;
    }

    @Override
    public Query<T> where(Condition condition) {
        if (condition != null) {
            conditions.add(condition);
        }
        return this;
    }

    @Override
    public Query<T> orderBy(SortOrder sortOrder) {
        this.sortOrder = sortOrder;
        return this;
    }

    @Override
    public Query<T> limit(int limit) {
        this.limit = limit;
        return this;
    }

    @Override
    public Query<T> offset(int offset) {
        this.offset = offset;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<T> list() {
        Stream<io.succinct.recordmaster.Record> stream;
        if (staging != null && staging.isCleared()) {
            stream = staging.getInserts().values().stream();
        } else {
            Stream<io.succinct.recordmaster.Record> committedStream = committedTable != null ? 
                    committedTable.recordPointers().entrySet().stream()
                            .filter(e -> {
                                Object key = e.getKey();
                                if (staging != null) {
                                    if (staging.getDeletes().contains(key)) return false;
                                    if (staging.getUpdates().containsKey(key)) return false;
                                    if (staging.getInserts().containsKey(key)) return false;
                                }
                                return true;
                            })
                            .map(e -> {
                                try {
                                    byte[] bytes = db.getTableStorage(tableName).readRecord(e.getValue());
                                    return BinaryCodec.deserialize(bytes, committedTable.entityType());
                                } catch (Exception ex) {
                                    throw new RecordMasterException("Failed to read record from disk in query", ex);
                                }
                            }) : Stream.empty();

            Stream<io.succinct.recordmaster.Record> stagedStream = staging != null ? 
                    Stream.concat(staging.getInserts().values().stream(), staging.getUpdates().values().stream()) :
                    Stream.empty();

            stream = Stream.concat(committedStream, stagedStream);
        }

        for (Condition cond : conditions) {
            stream = stream.filter(cond::test);
        }

        List<io.succinct.recordmaster.Record> results = stream.collect(Collectors.toList());

        if (sortOrder != null) {
            Field<?, ?> field = sortOrder.field();
            Comparator<io.succinct.recordmaster.Record> comparator = (r1, r2) -> {
                try {
                    Object val1 = ((Field<io.succinct.recordmaster.Record, Object>) field).getter().apply(r1);
                    Object val2 = ((Field<io.succinct.recordmaster.Record, Object>) field).getter().apply(r2);
                    if (val1 == null && val2 == null) return 0;
                    if (val1 == null) return sortOrder.ascending() ? -1 : 1;
                    if (val2 == null) return sortOrder.ascending() ? 1 : -1;
                    if (val1 instanceof Comparable && val2.getClass().isInstance(val1)) {
                        int cmp = ((Comparable<Object>) val1).compareTo(val2);
                        return sortOrder.ascending() ? cmp : -cmp;
                    }
                    return 0;
                } catch (Exception e) {
                    return 0;
                }
            };
            results.sort(comparator);
        }

        int fromIndex = Math.min(offset, results.size());
        int toIndex = limit < 0 ? results.size() : Math.min(fromIndex + limit, results.size());
        
        List<T> finalResults = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            finalResults.add((T) results.get(i));
        }
        return finalResults;
    }

    @Override
    public Optional<T> findFirst() {
        List<T> results = limit(1).list();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public QueryPlan explain() {
        boolean overlayUsed = staging != null && !staging.isEmpty();
        String strategy = "FULL_SCAN_WITH_TRANSACTION_OVERLAY";
        
        for (Condition cond : conditions) {
            if (cond instanceof EqualsCondition<?, ?> eq) {
                String fieldName = eq.field().name();
                if (fieldName.equals("id")) {
                    strategy = "PRIMARY_KEY_LOOKUP_WITH_TRANSACTION_OVERLAY";
                    break;
                }
                
                if (committedTable != null) {
                    for (IndexState idx : committedTable.indexes().values()) {
                        if (idx.metadata().fieldName().equals(fieldName)) {
                            if (idx.metadata().unique()) {
                                strategy = "UNIQUE_INDEX_LOOKUP_WITH_TRANSACTION_OVERLAY";
                            } else if (idx.metadata().ordered()) {
                                strategy = "ORDERED_INDEX_LOOKUP_WITH_TRANSACTION_OVERLAY";
                            } else {
                                strategy = "SECONDARY_INDEX_LOOKUP_WITH_TRANSACTION_OVERLAY";
                            }
                            break;
                        }
                    }
                }
            }
        }
        
        return new QueryPlan(strategy, overlayUsed);
    }
}
