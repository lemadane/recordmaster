package io.succinct.recordmaster.core;

import io.succinct.recordmaster.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.*;
import java.util.function.Function;

public final class RecordTransactionImpl implements RecordTransaction {

    private final long txId;
    private final RecordDatabase db;
    private final DatabaseState committedDbState;
    private final TransactionChangeSet changeSet;
    private volatile TransactionStatus status;
    private boolean lockReleased = false;

    public RecordTransactionImpl(long txId, RecordDatabase db, DatabaseState committedDbState) {
        this.txId = txId;
        this.db = db;
        this.committedDbState = committedDbState;
        this.changeSet = new TransactionChangeSet(txId);
        this.status = TransactionStatus.ACTIVE;
    }

    public TransactionChangeSet getChangeSet() {
        return changeSet;
    }

    public DatabaseState getCommittedDbState() {
        return committedDbState;
    }

    @Override
    public long transactionId() {
        return txId;
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
    public boolean isCommitted() {
        return status == TransactionStatus.COMMITTED;
    }

    @Override
    public boolean isRolledBack() {
        return status == TransactionStatus.ROLLED_BACK;
    }

    @Override
    public boolean isRollbackOnly() {
        return changeSet.isRollbackOnly();
    }

    @Override
    public void setRollbackOnly() {
        checkActive();
        changeSet.setRollbackOnly(true);
    }

    @Override
    public void setRollbackOnly(String reason) {
        checkActive();
        changeSet.setRollbackReason(reason);
    }

    @Override
    public Optional<String> rollbackReason() {
        return Optional.ofNullable(changeSet.getRollbackReason());
    }

    private void checkActive() {
        if (status != TransactionStatus.ACTIVE) {
            throw new InvalidTransactionStateException("Transaction " + txId + " is not active (current status: " + status + ")");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <ID, T extends io.succinct.recordmaster.Record> RecordTable<ID, T> table(Class<T> entityType) {
        checkActive();
        return db.resolveTableForTransaction(entityType, this);
    }

    @Override
    public <ID, T extends io.succinct.recordmaster.Record> RecordTable<ID, T> table(
            String tableName, Class<ID> idType, Class<T> entityType, Function<T, ID> idExtractor) {
        checkActive();
        db.registerTableMetadata(tableName, idType, entityType, idExtractor);
        return new RecordTableImpl<>(tableName, idType, entityType, idExtractor, db, this);
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
            UniqueIndexValidator.validate(committedDbState, changeSet);

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

                if (tc.isCleared()) {
                    ts.clear();
                }

                for (Object key : tc.getDeletes()) {
                    ts.delete(key);
                }

                for (io.succinct.recordmaster.Record record : tc.getInserts().values()) {
                    ts.insert(record);
                }

                for (io.succinct.recordmaster.Record record : tc.getUpdates().values()) {
                    Object key = ts.idExtractor().apply(record);
                    TableState oldTableState = committedDbState.getTable(tableName);
                    if (oldTableState == null) {
                        oldTableState = db.getCommittedState().getTable(tableName);
                    }
                    io.succinct.recordmaster.Record oldRecord = oldTableState != null ? oldTableState.records().get(key) : null;
                    ts.update(record, oldRecord);
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
    public void rollback() {
        if (status == TransactionStatus.COMMITTED) {
            throw new InvalidTransactionStateException("Cannot rollback: Transaction is already committed");
        }
        if (status == TransactionStatus.ROLLED_BACK) {
            return;
        }

        status = TransactionStatus.ROLLING_BACK;
        try {
            changeSet.clear();
            db.getWalManager().appendRollbackMarker(txId, db.currentGeneration(), changeSet.getRollbackReason());
            status = TransactionStatus.ROLLED_BACK;
        } catch (Exception e) {
            throw new TransactionRollbackException(txId, "Failed to rollback transaction", e);
        } finally {
            releaseWriterLock();
        }
    }

    private void releaseWriterLock() {
        if (!lockReleased) {
            db.releaseWriterLock();
            lockReleased = true;
        }
    }

    @Override
    public void close() {
        try {
            if (status == TransactionStatus.ACTIVE) {
                rollback();
            }
        } finally {
            releaseWriterLock();
        }
    }
}
