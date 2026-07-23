package io.succinct.recordmaster.core;

import io.succinct.recordmaster.Record;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;
import java.util.zip.CRC32C;

public final class SnapshotManager {

    private static final int MAGIC = 0x524d534e; // RMSN

    public interface RecordBytesWriter {
        byte[] getRecordBytes(String tableName, Object key, RecordPointer ptr) throws Exception;
    }

    public interface RecordBytesLoader {
        void loadRecord(String tableName, TableState ts, Record record, byte[] recBytes) throws Exception;
    }

    public static void writeSnapshot(Path directory, DatabaseState dbState, RecordBytesWriter writer) throws Exception {
        long gen = dbState.generation();
        Path tmpPath = directory.resolve("snapshot." + gen + ".tmp");
        Path finalPath = directory.resolve("snapshot." + gen);

        try (FileOutputStream fos = new FileOutputStream(tmpPath.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             CheckedOutputStream cos = new CheckedOutputStream(bos, new CRC32C());
             DataOutputStream dos = new DataOutputStream(cos)) {

            dos.writeInt(MAGIC);
            dos.writeLong(gen);
            dos.writeInt(dbState.tables().size());

            for (TableState ts : dbState.tables().values()) {
                dos.writeUTF(ts.tableName());
                dos.writeUTF(ts.idType().getName());
                dos.writeUTF(ts.entityType().getName());

                // Write Index metadata
                List<IndexMetadata> idxMetas = ts.indexMetadataList();
                dos.writeInt(idxMetas.size());
                for (IndexMetadata meta : idxMetas) {
                    dos.writeUTF(meta.indexName());
                    dos.writeUTF(meta.fieldName());
                    dos.writeBoolean(meta.unique());
                    dos.writeBoolean(meta.ordered());
                }

                // Write Records
                Map<Object, RecordPointer> pointers = ts.recordPointers();
                dos.writeInt(pointers.size());
                for (Map.Entry<Object, RecordPointer> entry : pointers.entrySet()) {
                    byte[] recBytes = writer.getRecordBytes(ts.tableName(), entry.getKey(), entry.getValue());
                    dos.writeInt(recBytes.length);
                    dos.write(recBytes);
                }
            }

            dos.flush();
            long checksum = cos.getChecksum().getValue();
            
            // Write checksum at the very end of file
            dos.writeLong(checksum);
            dos.flush();
            fos.getFD().sync();
        }

        // Rename atomically
        Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        // Delete older snapshots
        try (var stream = Files.list(directory)) {
            stream.filter(p -> p.getFileName().toString().startsWith("snapshot."))
                  .filter(p -> !p.getFileName().toString().equals("snapshot." + gen))
                  .forEach(p -> {
                      try {
                          Files.deleteIfExists(p);
                      } catch (IOException e) {
                          // ignore
                      }
                  });
        }
    }

    @SuppressWarnings("unchecked")
    public static DatabaseState readSnapshot(Path directory, RecordBytesLoader loader) throws Exception {
        Path snapshotPath = findLatestSnapshot(directory);
        if (snapshotPath == null) {
            return null;
        }

        long length = Files.size(snapshotPath);
        if (length < 20) {
            throw new IOException("Corrupt snapshot: file too small");
        }

        long fileChecksum;
        long computedChecksum;

        try (FileInputStream fis = new FileInputStream(snapshotPath.toFile());
             BufferedInputStream bis = new BufferedInputStream(fis);
             CheckedInputStream cis = new CheckedInputStream(bis, new CRC32C());
             DataInputStream dis = new DataInputStream(cis)) {

            int magic = dis.readInt();
            if (magic != MAGIC) {
                throw new IOException("Corrupt snapshot: magic mismatch");
            }

            long gen = dis.readLong();
            int tableCount = dis.readInt();

            DatabaseState dbState = new DatabaseState(gen);

            for (int t = 0; t < tableCount; t++) {
                String tableName = dis.readUTF();
                String idClassName = dis.readUTF();
                String entityClassName = dis.readUTF();

                Class<?> idType = Class.forName(idClassName);
                Class<? extends Record> entityType = (Class<? extends Record>) Class.forName(entityClassName);

                // Read index metadata
                int indexCount = dis.readInt();
                List<IndexMetadata> indexMetadataList = new ArrayList<>();
                for (int i = 0; i < indexCount; i++) {
                    String idxName = dis.readUTF();
                    String fieldName = dis.readUTF();
                    boolean unique = dis.readBoolean();
                    boolean ordered = dis.readBoolean();

                    // Resolve accessor function
                    java.lang.reflect.Method accessor = entityType.getMethod(fieldName);
                    accessor.setAccessible(true);
                    Function<Record, Object> extractor = r -> {
                        try {
                            return accessor.invoke(r);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    };

                    indexMetadataList.add(new IndexMetadata(idxName, fieldName, unique, ordered, extractor));
                }

                // Resolve primary key extractor
                java.lang.reflect.Method idAccessor = null;
                for (java.lang.reflect.Method m : entityType.getMethods()) {
                    if (m.isAnnotationPresent(io.succinct.recordmaster.annotations.Id.class) || m.getName().equalsIgnoreCase("id")) {
                        idAccessor = m;
                        break;
                    }
                }
                if (idAccessor == null && entityType.getRecordComponents().length > 0) {
                    idAccessor = entityType.getMethod(entityType.getRecordComponents()[0].getName());
                }
                if (idAccessor == null) {
                    throw new IllegalStateException("Cannot find ID accessor for " + entityClassName);
                }
                idAccessor.setAccessible(true);
                java.lang.reflect.Method finalIdAccessor = idAccessor;
                Function<Record, Object> idExtractor = r -> {
                    try {
                        return finalIdAccessor.invoke(r);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };

                TableState ts = new TableState(tableName, idType, entityType, idExtractor, indexMetadataList);

                // Read records (read here to update checksum validation, we load them in the second pass)
                int recordCount = dis.readInt();
                for (int r = 0; r < recordCount; r++) {
                    int len = dis.readInt();
                    byte[] temp = new byte[len];
                    dis.readFully(temp);
                }

                dbState.tables().put(tableName, ts);
            }

            // Verify checksum
            computedChecksum = cis.getChecksum().getValue();
            fileChecksum = dis.readLong();
        }

        if (computedChecksum != fileChecksum) {
            throw new IOException("Corrupt snapshot: Checksum failure");
        }

        return readSnapshotDatabaseState(snapshotPath, loader);
    }

    private static Path findLatestSnapshot(Path directory) throws IOException {
        if (!Files.exists(directory)) return null;
        try (var stream = Files.list(directory)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("snapshot."))
                         .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                         .max(Comparator.comparingLong(p -> {
                             String name = p.getFileName().toString();
                             try {
                                 return Long.parseLong(name.substring("snapshot.".length()));
                             } catch (NumberFormatException e) {
                                 return -1;
                             }
                          })).orElse(null);
        }
    }

    @SuppressWarnings("unchecked")
    private static DatabaseState readSnapshotDatabaseState(Path snapshotPath, RecordBytesLoader loader) throws Exception {
        try (FileInputStream fis = new FileInputStream(snapshotPath.toFile());
             BufferedInputStream bis = new BufferedInputStream(fis);
             DataInputStream dis = new DataInputStream(bis)) {

            dis.readInt(); // magic
            long gen = dis.readLong();
            int tableCount = dis.readInt();

            DatabaseState dbState = new DatabaseState(gen);

            for (int t = 0; t < tableCount; t++) {
                String tableName = dis.readUTF();
                String idClassName = dis.readUTF();
                String entityClassName = dis.readUTF();

                Class<?> idType = Class.forName(idClassName);
                Class<? extends Record> entityType = (Class<? extends Record>) Class.forName(entityClassName);

                int indexCount = dis.readInt();
                List<IndexMetadata> indexMetadataList = new ArrayList<>();
                for (int i = 0; i < indexCount; i++) {
                    String idxName = dis.readUTF();
                    String fieldName = dis.readUTF();
                    boolean unique = dis.readBoolean();
                    boolean ordered = dis.readBoolean();

                    java.lang.reflect.Method accessor = entityType.getMethod(fieldName);
                    accessor.setAccessible(true);
                    Function<Record, Object> extractor = r -> {
                        try {
                            return accessor.invoke(r);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    };

                    indexMetadataList.add(new IndexMetadata(idxName, fieldName, unique, ordered, extractor));
                }

                java.lang.reflect.Method idAccessor = null;
                for (java.lang.reflect.Method m : entityType.getMethods()) {
                    if (m.isAnnotationPresent(io.succinct.recordmaster.annotations.Id.class) || m.getName().equalsIgnoreCase("id")) {
                        idAccessor = m;
                        break;
                    }
                }
                if (idAccessor == null && entityType.getRecordComponents().length > 0) {
                    idAccessor = entityType.getMethod(entityType.getRecordComponents()[0].getName());
                }
                idAccessor.setAccessible(true);
                java.lang.reflect.Method finalIdAccessor = idAccessor;
                Function<Record, Object> idExtractor = r -> {
                    try {
                        return finalIdAccessor.invoke(r);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };

                TableState ts = new TableState(tableName, idType, entityType, idExtractor, indexMetadataList);

                int recordCount = dis.readInt();
                for (int r = 0; r < recordCount; r++) {
                    int len = dis.readInt();
                    byte[] recBytes = new byte[len];
                    dis.readFully(recBytes);
                    Record record = BinaryCodec.deserialize(recBytes, entityType);
                    loader.loadRecord(tableName, ts, record, recBytes);
                }

                dbState.tables().put(tableName, ts);
            }
            return dbState;
        }
    }
}

class CheckedInputStream extends FilterInputStream {
    private final CRC32C crc;
    public CheckedInputStream(InputStream in, CRC32C crc) {
        super(in);
        this.crc = crc;
    }
    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b != -1) {
            crc.update(b);
        }
        return b;
    }
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int read = in.read(b, off, len);
        if (read > 0) {
            crc.update(b, off, read);
        }
        return read;
    }
    public CRC32C getChecksum() {
        return crc;
    }
}

class CheckedOutputStream extends FilterOutputStream {
    private final CRC32C crc;
    public CheckedOutputStream(OutputStream out, CRC32C crc) {
        super(out);
        this.crc = crc;
    }
    @Override
    public void write(int b) throws IOException {
        out.write(b);
        crc.update(b);
    }
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        crc.update(b, off, len);
    }
    public CRC32C getChecksum() {
        return crc;
    }
}
