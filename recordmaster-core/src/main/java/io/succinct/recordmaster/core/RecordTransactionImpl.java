package io.succinct.recordmaster.core;

import io.succinct.recordmaster.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.*;

public final class RecordTransactionImpl implements RecordTransaction {

    private final long txId;
    private final RecordDatabase db;
    private final DatabaseState committedDbState;
    private final TransactionChangeSet changeSet;
    private volatile TransactionStatus status;

    public RecordTransactionImpl(long txId, RecordDatabase db, DatabaseState committedDbState) {
        this.txId = txId;
        this.db = db;
        this.committedDbState = committedDbState;
        this.changeSet = new TransactionChangeSet(txId);
        this.status = TransactionStatus.ACTIVE;
    }

    @Override
    public long transactionId() {
        return txId;
    }

    public DatabaseState getCommittedDbState() {
        return committedDbState;
    }

    public TransactionChangeSet getChangeSet() {
        return changeSet;
    }

    @Override
    public TransactionStatus status() {
        return status;
    }

    @Override
    public boolean isActive() {
        return status == TransactionStatus.ACTIVE;
    }

    @Override
    public void setRollbackOnly() {
        setRollbackOnly("Explicitly set rollback-only");
    }

    @Override
    public void setRollbackOnly(String reason) {
        checkActive();
        changeSet.setRollbackReason(reason);
    }

    @Override
    public boolean isRollbackOnly() {
        checkActive();
        return changeSet.isRollbackOnly();
    }

    @Override
    public <ID, T extends io.succinct.recordmaster.Record> RecordTable<ID, T> table(Class<T> entityType) {
        checkActive();
        return db.resolveTableForTransaction(entityType, this);
    }

    @Override
    public <ID, T extends io.succinct.recordmaster.Record> RecordTable<ID, T> table(
            String tableName, Class<ID> idType, Class<T> entityType, java.util.function.Function<T, ID> idExtractor) {
        checkActive();
        db.registerTableMetadata(tableName, idType, entityType, idExtractor);
        return new RecordTableImpl<>(tableName, idType, entityType, idExtractor, db, this);
    }

    private void checkActive() {
        if (status != TransactionStatus.ACTIVE) {
            throw new InvalidTransactionStateException("Transaction is not active (status: " + status + ")");
        }
    }

    private void releaseWriterLock() {
        db.releaseWriterLock();
    }

    @Override
    public void rollback() {
        if (status == TransactionStatus.ROLLED_BACK) {
            return; // Idempotent
        }
        if (status == TransactionStatus.COMMITTED) {
            throw new InvalidTransactionStateException("Cannot rollback: Transaction is already committed");
        }
        
        status = TransactionStatus.ROLLING_BACK;
        try {
            long nextGen = db.currentGeneration() + 1;
            db.getWalManager().appendRollbackMarker(txId, nextGen, changeSet.getRollbackReason());
            status = TransactionStatus.ROLLED_BACK;
        } catch (Exception e) {
            status = TransactionStatus.FAILED;
            throw new RecordMasterException("Failed to append rollback to WAL", e);
        } finally {
            releaseWriterLock();
        }
    }

    @Override
    public void commit() {
        if (status == TransactionStatus.COMMITTED) {
            throw new InvalidTransactionStateException("Cannot commit: Transaction is already committed");
        }
        if (status == TransactionStatus.ROLLED_BACK || status == TransactionStatus.ROLLING_BACK) {
            throw new InvalidTransactionStateException("Cannot commit: Transaction is rolled back");
        }
        checkActive();

        if (changeSet.isRollbackOnly()) {
            rollback();
            throw new TransactionRolledBackException(txId, changeSet.getRollbackReason());
        }

        status = TransactionStatus.COMMITTING;
        long nextGen = db.currentGeneration() + 1;

        try {
            UniqueIndexValidator.validate(db, committedDbState, changeSet);

            List<WalRecord> walRecords = new ArrayList<>();
            for (Map.Entry<String, TableChangeSet> entry : changeSet.getAllTableChanges().entrySet()) {
                String tableName = entry.getKey();
                TableChangeSet tc = entry.getValue();

                TableState cts = committedDbState.getTable(tableName);
                String entityClassName = cts != null ? cts.entityType().getName() : "";
                String keyClassName = cts != null ? cts.idType().getName() : "";

                if (tc.isCleared()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(baos);
                    dos.writeUTF(tableName);
                    dos.flush();
                    walRecords.add(new WalRecord(RecordWalOperation.CLEAR_TABLE, txId, nextGen, baos.toByteArray()));
                }

                for (Object key : tc.getDeletes()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(baos);
                    dos.writeUTF(tableName);
                    dos.writeUTF(keyClassName);
                    BinaryCodec.writeValue(dos, key);
                    dos.flush();
                    walRecords.add(new WalRecord(RecordWalOperation.DELETE, txId, nextGen, baos.toByteArray()));
                }

                for (io.succinct.recordmaster.Record record : tc.getInserts().values()) {
                    byte[] recBytes = BinaryCodec.serialize(record);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(baos);
                    dos.writeUTF(tableName);
                    dos.writeUTF(entityClassName);
                    dos.writeInt(recBytes.length);
                    dos.write(recBytes);
                    dos.flush();
                    walRecords.add(new WalRecord(RecordWalOperation.INSERT, txId, nextGen, baos.toByteArray()));
                }

                for (io.succinct.recordmaster.Record record : tc.getUpdates().values()) {
                    byte[] recBytes = BinaryCodec.serialize(record);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream dos = new DataOutputStream(baos);
                    dos.writeUTF(tableName);
                    dos.writeUTF(entityClassName);
                    dos.writeInt(recBytes.length);
                    dos.write(recBytes);
                    dos.flush();
                    walRecords.add(new WalRecord(RecordWalOperation.UPDATE, txId, nextGen, baos.toByteArray()));
                }
            }

            if (!walRecords.isEmpty()) {
                db.getWalManager().appendTransaction(txId, nextGen, walRecords);
            }

            DatabaseState nextState = committedDbState.copy(nextGen);
            for (Map.Entry<String, TableChangeSet> entry : changeSet.getAllTableChanges().entrySet()) {
                String tableName = entry.getKey();
                TableChangeSet tc = entry.getValue();
                TableState ts = nextState.getTable(tableName);
                TableStorage storage = db.getTableStorage(tableName);

                if (tc.isCleared()) {
                    ts.clear();
                }

                for (Object key : tc.getDeletes()) {
                    TableState oldTableState = committedDbState.getTable(tableName);
                    if (oldTableState == null) {
                        oldTableState = db.getCommittedState().getTable(tableName);
                    }
                    io.succinct.recordmaster.Record oldRecord = null;
                    if (oldTableState != null) {
                        RecordPointer oldPtr = oldTableState.recordPointers().get(key);
                        if (oldPtr != null) {
                            byte[] bytes = storage.readRecord(oldPtr);
                            oldRecord = BinaryCodec.deserialize(bytes, oldTableState.entityType());
                        }
                    }
                    ts.delete(key, oldRecord);
                }

                for (io.succinct.recordmaster.Record record : tc.getInserts().values()) {
                    byte[] bytes = BinaryCodec.serialize(record);
                    RecordPointer ptr = storage.appendRecord(bytes);
                    ts.insert(record, ptr);
                }

                for (io.succinct.recordmaster.Record record : tc.getUpdates().values()) {
                    Object key = ts.idExtractor().apply(record);
                    TableState oldTableState = committedDbState.getTable(tableName);
                    if (oldTableState == null) {
                        oldTableState = db.getCommittedState().getTable(tableName);
                    }
                    io.succinct.recordmaster.Record oldRecord = null;
                    if (oldTableState != null) {
                        RecordPointer oldPtr = oldTableState.recordPointers().get(key);
                        if (oldPtr != null) {
                            byte[] bytes = storage.readRecord(oldPtr);
                            oldRecord = BinaryCodec.deserialize(bytes, oldTableState.entityType());
                        }
                    }
                    byte[] bytes = BinaryCodec.serialize(record);
                    RecordPointer ptr = storage.appendRecord(bytes);
                    ts.update(record, oldRecord, ptr);
                }
            }

            db.publish(nextState);
            status = TransactionStatus.COMMITTED;
        } catch (DuplicateIndexValueException e) {
            rollback();
            throw e;
        } catch (Exception e) {
            status = TransactionStatus.FAILED;
            rollback();
            throw new TransactionCommitException(txId, "COMMIT_PHASE", nextGen, "Transaction commit failed", e);
        } finally {
            releaseWriterLock();
        }
    }

    @Override
    public boolean isCommitted() {
        return status == TransactionStatus.COMMITTED;
    }

    @Override
    public boolean isRolledBack() {
        return status == TransactionStatus.ROLLED_BACK;
    }

    @Override
    public Optional<String> rollbackReason() {
        return Optional.ofNullable(changeSet.getRollbackReason());
    }

    @Override
    public void close() {
        if (status == TransactionStatus.ACTIVE) {
            rollback();
        }
    }
}
