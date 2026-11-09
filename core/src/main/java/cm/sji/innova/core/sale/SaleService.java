package cm.sji.innova.core.sale;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SaleService {
    private final SaleRepository saleRepository;
    private final ProductRepository productRepository;

    public SaleService(SaleRepository saleRepository, ProductRepository productRepository) {
        this.saleRepository = Objects.requireNonNull(saleRepository, "saleRepository");
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository");
    }

    public SaleResult makeCashSale(UUID saleId, UUID shopId, UUID deviceId,
                                  String receiptNumber, List<SaleItemRequest> itemRequests) throws SQLException {
        CashSaleRequest request = new CashSaleRequest(saleId, shopId, deviceId, receiptNumber, itemRequests);
        // Resolve retries from their saved snapshots, before consulting today's catalog prices.
        Optional<Sale> stored = saleRepository.findById(shopId, saleId);
        if (stored.isPresent()) {
            request.requireMatches(stored.get());
            return new SaleResult(stored.get(), true);
        }

        Map<UUID, Product> productsById = productRepository.findByShop(shopId).stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));

        List<SaleItem> items = new ArrayList<>();
        long totalAmount = 0L;

        for (SaleItemRequest req : request.items()) {
            Product prod = productsById.get(req.productId());
            if (prod == null) {
                throw new IllegalArgumentException("Product not found in this shop: " + req.productId());
            }
            long unitPrice = req.customUnitPrice() != null ? req.customUnitPrice() : prod.unitPrice();
            // Round each line to whole FCFA, nearest with ties up; never use floating point.
            // Decimal arithmetic also avoids overflow in an intermediate long multiplication.
            long subtotal = BigDecimal.valueOf(req.quantityMilli(), 3)
                    .multiply(BigDecimal.valueOf(unitPrice))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            totalAmount = Math.addExact(totalAmount, subtotal);
            items.add(new SaleItem(UUID.randomUUID(), saleId, prod.id(), prod.reference(), prod.name(),
                    prod.unit(), req.quantityMilli(), unitPrice, subtotal));
        }

        Sale sale = new Sale(saleId, shopId, request.receiptNumber(), deviceId, PaymentMethod.CASH, totalAmount, Instant.now(), items);
        return saleRepository.recordSale(sale, request);
    }

    public VoidSaleResult voidCashSale(UUID operationId, UUID shopId, UUID saleId,
                                       UUID deviceId, UUID performedBy, String reason) throws SQLException {
        VoidSaleRequest request = new VoidSaleRequest(operationId, shopId, saleId, deviceId, performedBy, reason);
        return saleRepository.voidSale(request);
    }

    public Optional<Sale> getSale(UUID shopId, UUID saleId) throws SQLException {
        return saleRepository.findById(Objects.requireNonNull(shopId, "shopId"), Objects.requireNonNull(saleId, "saleId"));
    }

    public List<Sale> listSales(UUID shopId) throws SQLException {
        return saleRepository.listByShop(Objects.requireNonNull(shopId, "shopId"));
    }

    public Optional<SaleState> getSaleState(UUID shopId, UUID saleId) throws SQLException {
        return saleRepository.findStateById(
                Objects.requireNonNull(shopId, "shopId"),
                Objects.requireNonNull(saleId, "saleId"));
    }

    public List<SaleState> listSaleStates(UUID shopId) throws SQLException {
        return saleRepository.listStatesByShop(Objects.requireNonNull(shopId, "shopId"));
    }

    public List<SaleEvent> listSaleEvents(UUID shopId, UUID saleId) throws SQLException {
        return saleRepository.listEvents(
                Objects.requireNonNull(shopId, "shopId"),
                Objects.requireNonNull(saleId, "saleId"));
    }

    public List<SaleAuditRecord> listAuditRecords(UUID shopId, UUID saleId) throws SQLException {
        return saleRepository.listAuditRecords(
                Objects.requireNonNull(shopId, "shopId"),
                Objects.requireNonNull(saleId, "saleId"));
    }
}
