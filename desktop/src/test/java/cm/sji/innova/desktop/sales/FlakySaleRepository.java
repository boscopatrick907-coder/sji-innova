package cm.sji.innova.desktop.sales;

import cm.sji.innova.core.sale.CashSaleRequest;
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleAuditRecord;
import cm.sji.innova.core.sale.SaleRepository;
import cm.sji.innova.core.sale.SaleResult;
import cm.sji.innova.core.sale.SaleState;
import cm.sji.innova.core.sale.VoidSaleRequest;
import cm.sji.innova.core.sale.VoidSaleResult;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Test helper: the real repository, which can also fail just before or just after really saving. */
final class FlakySaleRepository implements SaleRepository {
    private final SaleRepository real;
    final AtomicInteger calls = new AtomicInteger();
    volatile UUID lastSaleId;
    volatile boolean failBeforeSaving;
    volatile boolean failAfterSaving;
    volatile boolean failReadingHistory;
    /** When set, runs once just after a sale is really saved, before the answer goes back. */
    volatile Callable<?> afterSaving;
    /** When set, the next history read reports here, then waits for {@link #releaseHistoryRead}. */
    volatile CountDownLatch historyReadReached;
    volatile CountDownLatch releaseHistoryRead;

    FlakySaleRepository(SaleRepository real) { this.real = real; }

    @Override public SaleResult recordSale(Sale sale) throws SQLException { return real.recordSale(sale); }

    @Override public SaleResult recordSale(Sale sale, CashSaleRequest request) throws SQLException {
        calls.incrementAndGet();
        lastSaleId = sale.id();
        if (failBeforeSaving) {
            failBeforeSaving = false;
            throw new SQLException("Simulated failure before saving");
        }
        SaleResult result = real.recordSale(sale, request);
        Callable<?> hook = afterSaving;
        if (hook != null) {
            afterSaving = null;
            try {
                hook.call();
            } catch (Exception failure) {
                throw new SQLException("The test's after-saving step failed", failure);
            }
        }
        if (failAfterSaving) {
            failAfterSaving = false;
            throw new SQLException("Simulated failure after saving");
        }
        return result;
    }

    @Override public Optional<Sale> findById(UUID shopId, UUID saleId) throws SQLException {
        return real.findById(shopId, saleId);
    }

    @Override public List<Sale> listByShop(UUID shopId) throws SQLException { return real.listByShop(shopId); }

    @Override public VoidSaleResult voidSale(VoidSaleRequest request) throws SQLException {
        return real.voidSale(request);
    }

    @Override public Optional<SaleState> findStateById(UUID shopId, UUID saleId) throws SQLException {
        return real.findStateById(shopId, saleId);
    }

    @Override public List<SaleAuditRecord> listAuditRecords(UUID shopId, UUID saleId) throws SQLException {
        return real.listAuditRecords(shopId, saleId);
    }

    @Override public List<SaleState> listStatesByShop(UUID shopId) throws SQLException {
        if (failReadingHistory) throw new SQLException("Simulated failure while reading the history");
        List<SaleState> states = real.listStatesByShop(shopId);
        CountDownLatch reached = historyReadReached;
        if (reached != null) {
            historyReadReached = null;
            reached.countDown();
            try {
                releaseHistoryRead.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while holding a read", interrupted);
            }
        }
        return states;
    }
}
