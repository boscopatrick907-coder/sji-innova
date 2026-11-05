package cm.sji.innova.desktop.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingCheckoutStoreTest {
    @TempDir Path directory;

    private PendingCheckoutStore store() { return new PendingCheckoutStore(directory.resolve("pending-checkout.txt")); }

    private static PendingCheckout sale() {
        return PendingCheckout.start(UUID.randomUUID(), UUID.randomUUID(), List.of(
                new BasketLine(UUID.randomUUID(), "Café = moulu\nnoir # été", "P-0001", "kg", 1_500, 333),
                new BasketLine(UUID.randomUUID(), "Riz - Basmati 25 kg", "", "bag", 2_000, 28_000)),
                LocalDate.of(2026, 11, 5));
    }

    @Test
    void whatIsSavedComesBackExactlyAfterARestart() throws Exception {
        PendingCheckout sale = sale();
        store().save(sale);
        assertEquals(Optional.of(sale), store().load());        // a new store object, like a restart
        assertFalse(Files.exists(directory.resolve("pending-checkout.txt.tmp")));
    }

    @Test
    void savingAgainReplacesTheOldFile() throws Exception {
        store().save(sale());
        PendingCheckout second = sale();
        store().save(second);
        assertEquals(Optional.of(second), store().load());
    }

    @Test
    void savingCreatesTheDataFolderWhenItIsMissing() throws Exception {
        PendingCheckoutStore store = new PendingCheckoutStore(directory.resolve("new/folder/pending.txt"));
        store.save(sale());
        assertTrue(store.load().isPresent());
    }

    @Test
    void noFileMeansNoPendingSale() throws Exception {
        assertEquals(Optional.empty(), store().load());
        store().clear();                                        // clearing twice is not an error
        assertEquals(Optional.empty(), store().load());
    }

    @Test
    void clearRemovesTheFile() throws Exception {
        store().save(sale());
        store().clear();
        assertEquals(Optional.empty(), store().load());
    }

    @Test
    void aDamagedFileIsReportedAndLeftUntouched() throws Exception {
        Path file = store().file();
        String[] damagedContents = {
            "not our file",
            "format=2\n",
            "format=1\nlineCount=1\n",
            "format=1\nlineCount=abc\n",
            "format=1\nsaleId=\\uZZZZ\n"
        };
        for (String content : damagedContents) {
            Files.writeString(file, content);
            assertThrows(IOException.class, () -> store().load(), content);
            assertEquals(content, Files.readString(file));
        }
    }

    @Test
    void wrongValuesInsideTheFileCountAsDamage() throws Exception {
        store().save(sale());
        Path file = store().file();
        String good = Files.readString(file);
        for (String[] change : new String[][] {
                {"line.0.quantityMilli=1500", "line.0.quantityMilli=-1"},
                {"line.1.unit=bag", "line.1.unit=box"},
                {"line.1.quantityMilli=2000", "line.1.quantityMilli=1"},
                {"lineCount=2", "lineCount=0"},
                {"lineCount=2", "lineCount=3"},
                {"line.0.unitPrice=333", "line.0.unitPrice=" + Long.MAX_VALUE}}) {
            assertTrue(good.contains(change[0]), change[0]);
            Files.writeString(file, good.replace(change[0], change[1]));
            assertThrows(IOException.class, () -> store().load(), change[1]);
        }
    }

    @Test
    void settingAsideKeepsTheContentUnderANewName() throws Exception {
        Path file = store().file();
        Files.writeString(file, "not our file");
        Path kept = store().setAside();
        assertEquals("not our file", Files.readString(kept));
        assertFalse(Files.exists(file));
        assertEquals(Optional.empty(), store().load());
    }

    @Test
    void aFolderThatCannotBeWrittenReportsAFailure() throws Exception {
        Path notAFolder = Files.writeString(directory.resolve("a-file"), "x");
        PendingCheckoutStore store = new PendingCheckoutStore(notAFolder.resolve("pending.txt"));
        assertThrows(IOException.class, () -> store.save(sale()));
    }

    @Test
    void eachSaleGetsItsOwnIdAndAReceiptMadeFromIt() {
        PendingCheckout first = sale();
        PendingCheckout second = sale();
        assertNotEquals(first.saleId(), second.saleId());
        assertNotEquals(first.receiptNumber(), second.receiptNumber());
        assertTrue(first.receiptNumber().matches("REC-20261105-[0-9A-F]{12}"), first.receiptNumber());
    }

    @Test
    void theSubmittedPriceTravelsWithTheRequest() {
        PendingCheckout sale = sale();
        assertEquals(333L, sale.requests().getFirst().customUnitPrice());
        assertEquals(1_500, sale.requests().getFirst().quantityMilli());
    }

    @Test
    void aSaleMustHaveLinesAndEachVariantOnlyOnce() {
        UUID shop = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 11, 5);
        assertThrows(IllegalArgumentException.class, () -> PendingCheckout.start(shop, device, List.of(), today));
        BasketLine line = new BasketLine(UUID.randomUUID(), "Rice", "P-0001", "kg", 1_000, 100);
        assertThrows(IllegalArgumentException.class,
                () -> PendingCheckout.start(shop, device, List.of(line, line), today));
    }
}
