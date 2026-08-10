package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentDropTableTest {

    public record DummyEntity(@Id Long id, String data) implements Record {}

    @Test
    public void testConcurrentReadersDuringDropTable(@TempDir Path dbDir) throws Exception {
        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            RecordTable<Long, DummyEntity> table = db.table(DummyEntity.class);

            db.transaction(tx -> {
                RecordTable<Long, DummyEntity> txTable = tx.table(DummyEntity.class);
                for (long i = 1; i <= 100; i++) {
                    txTable.insert(new DummyEntity(i, "data_" + i));
                }
            });

            AtomicBoolean running = new AtomicBoolean(true);
            ExecutorService pool = Executors.newFixedThreadPool(4);

            // Readers
            for (int r = 0; r < 3; r++) {
                pool.submit(() -> {
                    while (running.get()) {
                        try {
                            table.findById(50L);
                        } catch (Exception e) {
                            // If table is dropped, finding may fail, but should NEVER throw ClosedChannelException!
                            assertFalse(e instanceof java.nio.channels.ClosedChannelException, "Reader must never encounter ClosedChannelException during dropTable");
                        }
                    }
                });
            }

            Thread.sleep(50);
            db.dropTable("DummyEntity");
            running.set(false);
            pool.shutdown();
        }
    }
}
