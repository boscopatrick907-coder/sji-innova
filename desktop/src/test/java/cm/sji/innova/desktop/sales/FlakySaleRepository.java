package cm.sji.innova.desktop.sales;

import cm.sji.innova.core.sale.CashSaleRequest;
import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleRepository;
import cm.sji.innova.core.sale.SaleResult;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Test helper: the real repository, which can also fail just before or just after really saving. */
final class FlakySaleRepository implements SaleRepository {
    private final SaleRepository real;
    final AtomicInteger calls = new AtomicInteger();
    volatile UUID lastSaleId;
    volatile boolean failBeforeSaving;
    volatile boolean failAfterSaving;

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
}
