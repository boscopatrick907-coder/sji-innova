package cm.sji.innova.desktop.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasketTest {
    private static BasketLine line(String unit, long quantityMilli, long unitPrice) {
        return new BasketLine(UUID.randomUUID(), "Rice", "P-0001", unit, quantityMilli, unitPrice);
    }

    @Test
    void eachLineRoundsHalvesUpToWholeFcfa() {
        assertEquals(500, line("kg", 1_500, 333).total());      // 499.5 goes up
        assertEquals(1, line("kg", 500, 1).total());            // exact half goes up
        assertEquals(0, line("kg", 499, 1).total());            // below half goes down
        assertEquals(0, line("kg", 1, 1).total());
        assertEquals(2_000, line("piece", 2_000, 1_000).total());
    }

    @Test
    void theTotalAddsRoundedLinesNotExactOnes() {
        Basket basket = new Basket();
        basket.add(line("kg", 500, 1));
        basket.add(line("kg", 500, 1));
        basket.add(line("kg", 500, 1));
        assertEquals(3, basket.total());                        // 1 + 1 + 1, not 1.5 rounded to 2
    }

    @Test
    void addingTheSameVariantAgainAddsToItsLine() {
        Basket basket = new Basket();
        BasketLine kilo = line("kg", 1_000, 100);
        basket.add(kilo);
        basket.add(kilo);
        assertEquals(1, basket.lines().size());
        assertEquals(2_000, basket.lines().getFirst().quantityMilli());
    }

    @Test
    void quantityCanBeChangedAndLinesRemoved() {
        Basket basket = new Basket();
        BasketLine first = line("kg", 1_000, 100);
        BasketLine second = line("piece", 1_000, 50);
        basket.add(first);
        basket.add(second);
        basket.setQuantity(first.productId(), 2_500);
        assertEquals(300, basket.total());
        basket.remove(first.productId());
        assertEquals(List.of(second), basket.lines());
        basket.clear();
        assertTrue(basket.isEmpty());
    }

    @Test
    void aLineThatIsTooLargeLeavesTheBasketUnchanged() {
        Basket basket = new Basket();
        BasketLine line = line("piece", 1_000, Long.MAX_VALUE);
        basket.add(line);
        assertThrows(ArithmeticException.class, () -> basket.setQuantity(line.productId(), 2_000));
        assertEquals(List.of(line), basket.lines());
    }

    @Test
    void aTotalThatIsTooLargeLeavesTheBasketUnchanged() {
        Basket basket = new Basket();
        BasketLine first = line("piece", 1_000, Long.MAX_VALUE);
        basket.add(first);
        assertThrows(ArithmeticException.class, () -> basket.add(line("piece", 1_000, 1)));
        assertEquals(List.of(first), basket.lines());
    }

    @Test
    void wrongQuantitiesCannotReachALine() {
        assertThrows(IllegalArgumentException.class, () -> line("piece", 1_500, 100));
        assertThrows(IllegalArgumentException.class, () -> line("bag", 1, 100));
        assertThrows(IllegalArgumentException.class, () -> line("kg", 0, 100));
        assertThrows(IllegalArgumentException.class, () -> line("kg", 1_000, -1));
        assertThrows(IllegalArgumentException.class, () -> line("box", 1_000, 100));
    }

    @Test
    void savedLinesComeBackExactlyAsTheyWere() {
        Basket basket = new Basket();
        basket.add(line("kg", 1_000, 100));
        List<BasketLine> saved = List.of(line("kg", 750, 400), line("piece", 2_000, 300));
        basket.replaceWith(saved);
        assertEquals(saved, basket.lines());
    }
}
