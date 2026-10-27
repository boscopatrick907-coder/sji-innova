package cm.sji.innova.core.sale;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductRepository;
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
        Objects.requireNonNull(saleId, "saleId");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(receiptNumber, "receiptNumber");
        if (itemRequests == null || itemRequests.isEmpty()) {
            throw new IllegalArgumentException("Sale must contain at least one item");
        }

        Map<UUID, Product> productsById = productRepository.findByShop(shopId).stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));

        List<SaleItem> items = new ArrayList<>();
        long totalAmount = 0L;

        for (SaleItemRequest req : itemRequests) {
            Product prod = productsById.get(req.productId());
            if (prod == null) {
                throw new IllegalArgumentException("Product not found in this shop: " + req.productId());
            }
            long unitPrice = req.customUnitPrice() != null ? req.customUnitPrice() : prod.unitPrice();
            // Quantity is in milli-units (e.g. 2000 milli = 2 pieces).
            // Subtotal in whole FCFA = (quantityMilli * unitPrice) / 1000
            long subtotal = Math.multiplyExact(req.quantityMilli(), unitPrice) / 1000L;
            totalAmount = Math.addExact(totalAmount, subtotal);
            items.add(new SaleItem(UUID.randomUUID(), saleId, prod.id(), prod.name(),
                    prod.unit(), req.quantityMilli(), unitPrice, subtotal));
        }

        Sale sale = new Sale(saleId, shopId, receiptNumber, deviceId, PaymentMethod.CASH, totalAmount, Instant.now(), items);
        return saleRepository.recordSale(sale);
    }

    public Optional<Sale> getSale(UUID shopId, UUID saleId) throws SQLException {
        return saleRepository.findById(Objects.requireNonNull(shopId), Objects.requireNonNull(saleId));
    }

    public List<Sale> listSales(UUID shopId) throws SQLException {
        return saleRepository.listByShop(Objects.requireNonNull(shopId));
    }
}
