package io.lemadane.recordmaster;

public class DuplicateIndexValueException extends RecordMasterException {
    private final String tableName;
    private final String indexName;
    private final Object duplicateValue;

    public DuplicateIndexValueException(String tableName, String indexName, Object duplicateValue, String message) {
        super(message);
        this.tableName = tableName;
        this.indexName = indexName;
        this.duplicateValue = duplicateValue;
    }

    public String tableName() {
        return tableName;
    }

    public String indexName() {
        return indexName;
    }

    public Object duplicateValue() {
        return duplicateValue;
    }
}
