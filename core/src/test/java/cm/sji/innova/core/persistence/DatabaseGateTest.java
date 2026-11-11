package cm.sji.innova.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** OFF-03a1: every connection to a gated database file enters the gate once and leaves it once. */
class DatabaseGateTest {
    @TempDir Path directory;

    private final AtomicInteger entered = new AtomicInteger();
    private final AtomicInteger left = new AtomicInteger();
    private final DatabaseGate.Gate counting = new DatabaseGate.Gate() {
        @Override public void enter() { entered.incrementAndGet(); }
        @Override public void leave() { left.incrementAndGet(); }
    };
    private Path file;

    @AfterEach
    void removeTheGate() { DatabaseGate.remove(file, counting); }

    @Test
    void aConnectionEntersOnceAndLeavesOnceEvenIfClosedTwice() throws Exception {
        file = directory.resolve("cella.db");
        new SqliteDatabase(file).initialize();
        DatabaseGate.install(file, counting);

        Connection connection = new SqliteDatabase(file).open();
        assertEquals(1, entered.get());
        assertEquals(0, left.get(), "Still open");
        assertTrue(connection.isValid(5));

        connection.close();
        connection.close();

        assertEquals(1, left.get(), "Left exactly once");
        assertTrue(connection.isClosed());
    }

    @Test
    void aConnectionThatFailsToOpenLeavesTheGate() throws Exception {
        file = directory.resolve("not-a-database");
        Files.createDirectories(file);     // a folder where the database file should be
        DatabaseGate.install(file, counting);

        assertThrows(SQLException.class, () -> new SqliteDatabase(file).open());

        assertEquals(1, entered.get());
        assertEquals(1, left.get(), "The failed attempt does not stay counted as open");
    }

    @Test
    void aGateRefusingEntryOpensNothing() throws Exception {
        file = directory.resolve("cella.db");
        new SqliteDatabase(file).initialize();
        DatabaseGate.Gate refusing = new DatabaseGate.Gate() {
            @Override public void enter() throws SQLException { throw new SQLException("Paused for a backup"); }
            @Override public void leave() { left.incrementAndGet(); }
        };
        DatabaseGate.install(file, refusing);
        try {
            assertThrows(SQLException.class, () -> new SqliteDatabase(file).open());
            assertEquals(0, left.get(), "Nothing entered, so nothing leaves");
        } finally {
            DatabaseGate.remove(file, refusing);
        }
    }

    @Test
    void withoutAGateNothingChanges() throws Exception {
        file = directory.resolve("cella.db");
        new SqliteDatabase(file).initialize();
        try (Connection connection = new SqliteDatabase(file).open()) {
            assertTrue(connection.isValid(5));
        }
        assertEquals(0, entered.get());
    }
}
