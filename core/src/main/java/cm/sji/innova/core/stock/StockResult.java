package cm.sji.innova.core.stock;

/** A retry returns the original movement and the current (possibly later) stock. */
public record StockResult(StockMovement movement, StockLevel stock, boolean replayed) { }
