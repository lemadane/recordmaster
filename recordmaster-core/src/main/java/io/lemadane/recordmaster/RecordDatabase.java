package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Table;
import io.lemadane.recordmaster.annotations.Index;
import io.lemadane.recordmaster.annotations.Indexes;
import io.lemadane.recordmaster.core.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public final class RecordDatabase implements AutoCloseable {

    private final Path directory;
    private final DurabilityMode durabilityMode;
    private final WalManager walManager;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ExecutorService executor;
    private final ThreadLocal<RecordTransactionImpl> activeTransaction = new ThreadLocal<>();

    private volatile DatabaseState committedState;
    private final Map<String, TableMetadata> tableMetadataMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TableStorage> tableStorageMap = new ConcurrentHashMap<>();
    private volatile Throwable lastPersistenceFailure;
    private volatile boolean closed = false;
    private final long databaseId;

    private record TableMetadata(
        String tableName,
        Class<?> idType,
        Class<? extends io.lemadane.recordmaster.Record> entityType,
        Function<io.lemadane.recordmaster.Record, Object> idExtractor
    ) {}

    public RecordDatabase(RecordDatabaseBuilder builder) {
        this.directory = builder.directory();
        this.durabilityMode = builder.durabilityMode();
        this.databaseId = new Random().nextLong() & Long.MAX_VALUE;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.walManager = new WalManager(directory, durabilityMode);

        try {
            DatabaseState snapState = SnapshotManager.readSnapshot(directory, (tableName, ts, record, recBytes) -> {
                TableStorage storage = getTableStorage(tableName);
                if (ts.recordPointers().isEmpty()) {
                    storage.close();
                    Files.deleteIfExists(directory.resolve(tableName + ".db"));
                    tableStorageMap.remove(tableName);
                    storage = getTableStorage(tableName);
                }
                RecordPointer ptr = storage.appendRecord(recBytes);
                ts.insert(record, ptr);
            });

            if (snapState == null) {
                snapState = new DatabaseState(0);
            }

            List<WalRecord> walRecords = walManager.readAllRecords();
            this.committedState = RecoveryManager.recover(snapState, walRecords, new RecoveryManager.RecoveryStorageHelper() {
                @Override
                public io.lemadane.recordmaster.Record readRecord(String tableName, Object id, RecordPointer ptr, Class<? extends io.lemadane.recordmaster.Record> type) throws Exception {
                    byte[] bytes = getTableStorage(tableName).readRecord(ptr);
                    return BinaryCodec.deserialize(bytes, type);
                }
                @Override
                public RecordPointer appendRecord(String tableName, io.lemadane.recordmaster.Record record, byte[] bytes) throws Exception {
                    return getTableStorage(tableName).appendRecord(bytes);
                }
            });

            for (TableState ts : committedState.tables().values()) {
                tableMetadataMap.put(ts.tableName(), new TableMetadata(
                    ts.tableName(), ts.idType(), ts.entityType(), ts.idExtractor()
                ));
            }
        } catch (Exception e) {
            this.lastPersistenceFailure = e;
            throw new RecordMasterException("Database startup recovery failed", e);
        }
    }

    public static RecordDatabase open(Path directory) {
        return builder().directory(directory).build();
    }

    public static RecordDatabaseBuilder builder() {
        return new RecordDatabaseBuilder();
    }

    public DatabaseState getCommittedState() {
        return committedState;
    }

    public WalManager getWalManager() {
        return walManager;
    }

    public TableStorage getTableStorage(String tableName) {
        if (closed) {
            throw new IllegalStateException("Database is closed");
        }
        return tableStorageMap.computeIfAbsent(tableName, name -> new TableStorage(directory, name));
    }

    public void releaseWriterLock() {
        if (writeLock.isHeldByCurrentThread()) {
            writeLock.unlock();
        }
        activeTransaction.remove();
    }

    public void publish(DatabaseState nextState) {
        this.committedState = nextState;
    }

    @SuppressWarnings("unchecked")
    public <ID, T extends io.lemadane.recordmaster.Record> RecordTable<ID, T> resolveTableForTransaction(Class<T> entityType, RecordTransactionImpl tx) {
        String tableName = getTableName(entityType);
        TableMetadata meta = tableMetadataMap.get(tableName);
        if (meta == null) {
            registerTableMetadata(tableName, null, entityType, null);
            meta = tableMetadataMap.get(tableName);
        }
        return new RecordTableImpl<>(tableName, (Class<ID>) meta.idType(), entityType, (Function<T, ID>) meta.idExtractor(), this, tx);
    }

    public void registerTableMetadata(String tableName, Class<?> idType, Class<? extends io.lemadane.recordmaster.Record> entityType, Function<?, ?> idExtractor) {
        if (tableMetadataMap.containsKey(tableName)) {
            return;
        }

        writeLock.lock();
        try {
            if (tableMetadataMap.containsKey(tableName)) {
                return;
            }

            Class<?> resolvedIdType = idType;
            Function<io.lemadane.recordmaster.Record, Object> resolvedIdExtractor = (Function<io.lemadane.recordmaster.Record, Object>) idExtractor;

            if (resolvedIdType == null || resolvedIdExtractor == null) {
                Method idAccessor = null;
                for (Method m : entityType.getMethods()) {
                    if (m.isAnnotationPresent(Id.class) || m.getName().equalsIgnoreCase("id")) {
                        idAccessor = m;
                        break;
                    }
                }
                if (idAccessor == null && entityType.getRecordComponents().length > 0) {
                    idAccessor = entityType.getMethod(entityType.getRecordComponents()[0].getName());
                }
                if (idAccessor == null) {
                    throw new IllegalStateException("Cannot resolve primary key ID component for " + entityType.getName());
                }
                resolvedIdType = idAccessor.getReturnType();
                Method finalIdAccessor = idAccessor;
                resolvedIdExtractor = r -> {
                    try {
                        return finalIdAccessor.invoke(r);
                    } catch (Exception e) {
                        throw new RecordMasterException("Failed to access ID", e);
                    }
                };
            }

            List<IndexMetadata> indexMetadataList = new ArrayList<>();

            for (RecordComponent comp : entityType.getRecordComponents()) {
                if (comp.isAnnotationPresent(Index.class)) {
                    Index idx = comp.getAnnotation(Index.class);
                    String idxName = idx.name().isEmpty() ? tableName + "_" + comp.getName() + "_idx" : idx.name();
                    
                    Method accessor = comp.getAccessor();
                    accessor.setAccessible(true);
                    Function<io.lemadane.recordmaster.Record, Object> extractor = r -> {
                        try {
                            return accessor.invoke(r);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    };
                    indexMetadataList.add(new IndexMetadata(idxName, comp.getName(), idx.unique(), idx.ordered(), extractor));
                }
            }

            TableMetadata meta = new TableMetadata(tableName, resolvedIdType, entityType, resolvedIdExtractor);
            tableMetadataMap.put(tableName, meta);

            long nextGen = currentGeneration() + 1;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeUTF(tableName);
            dos.writeUTF(resolvedIdType.getName());
            dos.writeUTF(entityType.getName());
            dos.flush();
            WalRecord createTableRec = new WalRecord(RecordWalOperation.CREATE_TABLE, databaseId, nextGen, baos.toByteArray());

            List<WalRecord> schemaRecords = new ArrayList<>();
            schemaRecords.add(createTableRec);

            for (IndexMetadata idxMeta : indexMetadataList) {
                ByteArrayOutputStream idxBaos = new ByteArrayOutputStream();
                DataOutputStream idxDos = new DataOutputStream(idxBaos);
                idxDos.writeUTF(tableName);
                idxDos.writeUTF(idxMeta.indexName());
                idxDos.writeBoolean(idxMeta.unique());
                idxDos.writeBoolean(idxMeta.ordered());
                idxDos.flush();
                schemaRecords.add(new WalRecord(RecordWalOperation.CREATE_INDEX, databaseId, nextGen, idxBaos.toByteArray()));
            }

            walManager.appendTransaction(databaseId, nextGen, schemaRecords);

            TableState newTableState = new TableState(tableName, resolvedIdType, entityType, resolvedIdExtractor, indexMetadataList);
            DatabaseState nextState = committedState.copy(nextGen);
            nextState.tables().put(tableName, newTableState);
            publish(nextState);

            RecordTransactionImpl tx = activeTransaction.get();
            if (tx != null) {
                tx.getCommittedDbState().tables().put(tableName, newTableState);
            }

        } catch (Exception e) {
            throw new RecordMasterException("Failed to register table: " + tableName, e);
        } finally {
            writeLock.unlock();
        }
    }

    public String getTableName(Class<?> entityType) {
        if (entityType.isAnnotationPresent(Table.class)) {
            String val = entityType.getAnnotation(Table.class).value();
            if (!val.isEmpty()) return val;
        }
        return entityType.getSimpleName();
    }

    @SuppressWarnings("unchecked")
    public <ID, T extends io.lemadane.recordmaster.Record> RecordTable<ID, T> table(Class<T> entityType) {
        String tableName = getTableName(entityType);
        TableMetadata meta = tableMetadataMap.get(tableName);
        if (meta == null) {
            registerTableMetadata(tableName, null, entityType, null);
            meta = tableMetadataMap.get(tableName);
        }
        return new RecordTableImpl<>(tableName, (Class<ID>) meta.idType(), entityType, (Function<T, ID>) meta.idExtractor(), this);
    }

    @SuppressWarnings("unchecked")
    public <ID, T extends io.lemadane.recordmaster.Record> RecordTable<ID, T> table(
            String tableName, Class<ID> idType, Class<T> entityType, Function<T, ID> idExtractor) {
        registerTableMetadata(tableName, idType, entityType, idExtractor);
        return new RecordTableImpl<>(tableName, idType, entityType, idExtractor, this);
    }

    public RecordTransaction beginTransaction() {
        if (activeTransaction.get() != null) {
            throw new NestedTransactionNotSupportedException("Nested write transactions are not supported");
        }
        
        writeLock.lock();
        try {
            RecordTransactionImpl tx = new RecordTransactionImpl(new Random().nextLong() & Long.MAX_VALUE, this, committedState);
            activeTransaction.set(tx);
            return tx;
        } catch (Exception e) {
            writeLock.unlock();
            throw e;
        }
    }

    public <R> R transaction(TransactionFunction<R> txFunc) {
        RecordTransactionImpl current = activeTransaction.get();
        if (current != null) {
            try {
                return txFunc.execute(current);
            } catch (Exception e) {
                if (e instanceof RuntimeException re) throw re;
                throw new RecordMasterException("Transaction execution failed", e);
            }
        }

        CompletableFuture<R> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                RecordTransaction tx = beginTransaction();
                try {
                    R result = txFunc.execute(tx);
                    if (tx.isActive()) {
                        tx.commit();
                    }
                    future.complete(result);
                } catch (Throwable t) {
                    try {
                        if (tx.isActive()) {
                            tx.rollback();
                        }
                    } catch (Throwable rollbackErr) {
                        t.addSuppressed(rollbackErr);
                    }
                    future.completeExceptionally(t);
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RecordMasterException("Transaction callback exception occurred", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RecordMasterException("Transaction interrupted", e);
        }
    }

    public void transaction(TransactionConsumer txConsumer) {
        transaction(tx -> {
            txConsumer.execute(tx);
            return null;
        });
    }

    public Set<String> tableNames() {
        return Collections.unmodifiableSet(committedState.tables().keySet());
    }

    public boolean containsTable(String tableName) {
        return committedState.tables().containsKey(tableName);
    }

    @SuppressWarnings("unchecked")
    public Optional<RecordTable<?, ?>> findTable(String tableName) {
        TableMetadata meta = tableMetadataMap.get(tableName);
        if (meta == null) {
            return Optional.empty();
        }
        @SuppressWarnings("rawtypes")
        RecordTableImpl table = new RecordTableImpl(meta.tableName(), meta.idType(), meta.entityType(), meta.idExtractor(), this);
        return Optional.of(table);
    }

    public boolean dropTable(String tableName) {
        writeLock.lock();
        try {
            if (!containsTable(tableName)) {
                return false;
            }
            long nextGen = currentGeneration() + 1;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeUTF(tableName);
            dos.flush();
            walManager.appendTransaction(databaseId, nextGen, List.of(
                new WalRecord(RecordWalOperation.DROP_TABLE, databaseId, nextGen, baos.toByteArray())
            ));

            DatabaseState nextState = committedState.copy(nextGen);
            nextState.tables().remove(tableName);
            publish(nextState);
            tableMetadataMap.remove(tableName);
            
            // Delete table storage
            TableStorage storage = tableStorageMap.remove(tableName);
            if (storage != null) {
                storage.close();
            }
            Files.deleteIfExists(directory.resolve(tableName + ".db"));

            return true;
        } catch (Exception e) {
            throw new RecordMasterException("Failed to drop table " + tableName, e);
        } finally {
            writeLock.unlock();
        }
    }

    public CompletionStage<Void> flushAsync() {
        return CompletableFuture.runAsync(this::flush, executor);
    }

    public void flush() {
        try {
            walManager.flush();
        } catch (Exception e) {
            this.lastPersistenceFailure = e;
            throw new RecordMasterException("Failed to flush WAL log", e);
        }
    }

    public CompletionStage<Void> compactAsync() {
        return CompletableFuture.runAsync(this::compact, executor);
    }

    public void compact() {
        writeLock.lock();
        try {
            SnapshotManager.writeSnapshot(directory, committedState, (tableName, key, ptr) -> getTableStorage(tableName).readRecord(ptr));
            
            // Compact storage files on disk
            for (Map.Entry<String, TableState> entry : committedState.tables().entrySet()) {
                String tableName = entry.getKey();
                TableState ts = entry.getValue();
                getTableStorage(tableName).compact(ts.recordPointers());
            }

            walManager.truncate();
        } catch (Exception e) {
            this.lastPersistenceFailure = e;
            throw new RecordMasterException("Database compaction failed", e);
        } finally {
            writeLock.unlock();
        }
    }

    public void exportJson(Path destination) {
        try {
            DatabaseState stable = committedState;
            Map<String, List<Map<String, Object>>> jsonDb = new LinkedHashMap<>();

            for (TableState ts : stable.tables().values()) {
                List<Map<String, Object>> recs = new ArrayList<>();
                TableStorage storage = getTableStorage(ts.tableName());
                for (RecordPointer ptr : ts.recordPointers().values()) {
                    byte[] bytes = storage.readRecord(ptr);
                    io.lemadane.recordmaster.Record rec = BinaryCodec.deserialize(bytes, ts.entityType());
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (RecordComponent comp : rec.getClass().getRecordComponents()) {
                        Object val = comp.getAccessor().invoke(rec);
                        map.put(comp.getName(), val);
                    }
                    recs.add(map);
                }
                jsonDb.put(ts.tableName(), recs);
            }

            String serialized = SimpleJson.serialize(jsonDb);
            Files.writeString(destination, serialized);
        } catch (Exception e) {
            throw new RecordMasterException("JSON export failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public void importJson(Path source) {
        try {
            String json = Files.readString(source);
            Map<String, Object> parsed = (Map<String, Object>) SimpleJson.parse(json);
            if (parsed == null) return;

            transaction((TransactionConsumer) tx -> {
                for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                    String tableName = entry.getKey();
                    TableMetadata meta = tableMetadataMap.get(tableName);
                    if (meta == null) {
                        continue;
                    }

                    RecordTable<Object, io.lemadane.recordmaster.Record> table = tx.table(tableName, (Class<Object>) meta.idType(), (Class<io.lemadane.recordmaster.Record>) meta.entityType(), meta.idExtractor());
                    List<Object> rows = (List<Object>) entry.getValue();
                    for (Object row : rows) {
                        Map<String, Object> rowMap = (Map<String, Object>) row;
                        io.lemadane.recordmaster.Record rec = constructFromMap(rowMap, (Class<io.lemadane.recordmaster.Record>) meta.entityType());
                        table.insert(rec);
                    }
                }
            });
        } catch (Exception e) {
            throw new RecordMasterException("JSON import failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends io.lemadane.recordmaster.Record> T constructFromMap(Map<String, Object> map, Class<T> recordClass) {
        try {
            RecordComponent[] components = recordClass.getRecordComponents();
            Object[] args = new Object[components.length];
            Class<?>[] paramTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent comp = components[i];
                Object val = map.get(comp.getName());
                paramTypes[i] = comp.getType();
                if (val == null) {
                    args[i] = BinaryCodec.castValue(null, comp.getType());
                } else if (comp.getType() == UUID.class) {
                    args[i] = UUID.fromString(val.toString());
                } else if (comp.getType() == java.time.Instant.class) {
                    args[i] = java.time.Instant.parse(val.toString());
                } else if (comp.getType().isEnum()) {
                    args[i] = Enum.valueOf((Class<Enum>) comp.getType(), val.toString());
                } else if (comp.getType() == Integer.class || comp.getType() == int.class) {
                    args[i] = ((Number) val).intValue();
                } else if (comp.getType() == Long.class || comp.getType() == long.class) {
                    args[i] = ((Number) val).longValue();
                } else if (comp.getType() == Double.class || comp.getType() == double.class) {
                    args[i] = ((Number) val).doubleValue();
                } else if (comp.getType() == Boolean.class || comp.getType() == boolean.class) {
                    args[i] = (Boolean) val;
                } else {
                    args[i] = val;
                }
            }
            java.lang.reflect.Constructor<T> canonicalConstructor = recordClass.getDeclaredConstructor(paramTypes);
            canonicalConstructor.setAccessible(true);
            return canonicalConstructor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct record from map", e);
        }
    }

    public long currentGeneration() {
        return committedState.generation();
    }

    public long persistedGeneration() {
        return currentGeneration();
    }

    public long snapshotGeneration() {
        try {
            Path latest = findLatestSnapshot(directory);
            if (latest == null) return 0;
            String name = latest.getFileName().toString();
            return Long.parseLong(name.substring("snapshot.".length()));
        } catch (Exception e) {
            return 0;
        }
    }

    private Path findLatestSnapshot(Path directory) throws IOException {
        if (!Files.exists(directory)) return null;
        try (var stream = Files.list(directory)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("snapshot."))
                         .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                         .max(Comparator.comparingLong(p -> {
                             String name = p.getFileName().toString();
                             try {
                                 return java.lang.Long.parseLong(name.substring("snapshot.".length()));
                             } catch (NumberFormatException e) {
                                 return -1;
                              }
                         })).orElse(null);
        }
    }

    public Optional<Throwable> lastPersistenceFailure() {
        return Optional.ofNullable(lastPersistenceFailure);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        
        for (TableStorage ts : tableStorageMap.values()) {
            ts.close();
        }
        tableStorageMap.clear();
        
        executor.close();
        walManager.close();
    }
}
