package io.succinct.recordmaster.core;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TransactionChangeSet {
    private final long transactionId;
    private final Map<String, TableChangeSet> tableChanges = new LinkedHashMap<>();
    private final IndexChangeSet indexChanges = new IndexChangeSet();
    private boolean rollbackOnly = false;
    private String rollbackReason;

    public TransactionChangeSet(long transactionId) {
        this.transactionId = transactionId;
    }

    public long getTransactionId() {
        return transactionId;
    }

    public TableChangeSet getTableChanges(String tableName) {
        return tableChanges.computeIfAbsent(tableName, k -> new TableChangeSet());
    }

    public Map<String, TableChangeSet> getAllTableChanges() {
        return tableChanges;
    }

    public IndexChangeSet getIndexChanges() {
        return indexChanges;
    }

    public boolean isRollbackOnly() {
        return rollbackOnly;
    }

    public void setRollbackOnly(boolean rollbackOnly) {
        this.rollbackOnly = rollbackOnly;
    }

    public String getRollbackReason() {
        return rollbackReason;
    }

    public void setRollbackReason(String rollbackReason) {
        this.rollbackReason = rollbackReason;
        this.rollbackOnly = true;
    }

    public void clear() {
        tableChanges.clear();
        indexChanges.clear();
        rollbackOnly = false;
        rollbackReason = null;
    }
}
