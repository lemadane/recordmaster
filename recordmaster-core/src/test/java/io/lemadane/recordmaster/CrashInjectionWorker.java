package io.lemadane.recordmaster;

import io.lemadane.recordmaster.annotations.Id;
import io.lemadane.recordmaster.annotations.Index;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

public class CrashInjectionWorker {

    public record Account(
        @Id Long id,
        @Index(unique = true) String email,
        Long balance
    ) implements Record {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.exit(1);
        }

        Path dbDir = Paths.get(args[0]);
        Random random = new Random();

        try (RecordDatabase db = RecordDatabase.open(dbDir)) {
            RecordTable<Long, Account> table = db.table(Account.class);

            long maxId = table.query().list().stream().mapToLong(Account::id).max().orElse(0L);
            long counter = maxId + 1;

            while (true) {
                final long id = counter++;
                // Perform insert transaction
                db.transaction(tx -> {
                    RecordTable<Long, Account> txTable = tx.table(Account.class);
                    txTable.insert(new Account(id, "user" + id + "@example.com", id * 100));
                });

                // Randomly perform update or checkpoint/compact
                if (id % 5 == 0) {
                    db.transaction(tx -> {
                        RecordTable<Long, Account> txTable = tx.table(Account.class);
                        Account existing = txTable.findById(id).orElse(null);
                        if (existing != null) {
                            txTable.update(new Account(existing.id(), existing.email(), existing.balance() + 500));
                        }
                    });
                }

                if (id % 10 == 0) {
                    db.compact();
                }

                Thread.sleep(random.nextInt(5) + 1);
            }
        }
    }
}
