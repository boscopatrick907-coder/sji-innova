package cm.sji.innova.core.stock;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public interface StockRepository {
    StockResult record(StockMovement movement) throws SQLException;
    StockLevel current(UUID shopId, UUID productId) throws SQLException;
    List<StockLevel> list(UUID shopId) throws SQLException;
    List<StockMovement> history(UUID shopId, UUID productId) throws SQLException;
    StockLevel setMinimum(UUID shopId, UUID productId, long minimumMilli) throws SQLException;
}
