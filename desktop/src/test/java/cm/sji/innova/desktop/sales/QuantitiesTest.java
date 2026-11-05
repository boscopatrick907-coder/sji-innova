package cm.sji.innova.desktop.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cm.sji.innova.desktop.sales.Quantities.InvalidQuantityException;
import cm.sji.innova.desktop.sales.Quantities.Problem;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class QuantitiesTest {
    private static void assertProblem(Problem expected, String typed, String unit) {
        InvalidQuantityException failure =
                assertThrows(InvalidQuantityException.class, () -> Quantities.parseMilli(typed, unit));
        assertEquals(expected, failure.problem(), typed);
    }

    @Test
    void wholeNumbersWorkForEveryUnit() throws Exception {
        assertEquals(2_000, Quantities.parseMilli("2", "piece"));
        assertEquals(3_000, Quantities.parseMilli(" 3 ", "bag"));
        assertEquals(4_000, Quantities.parseMilli("4", "kg"));
        assertEquals(5_000, Quantities.parseMilli("5", "litre"));
    }

    @Test
    void kilosAndLitresAcceptACommaOrADotAndUpToThreeDecimals() throws Exception {
        assertEquals(1_500, Quantities.parseMilli("1,5", "kg"));
        assertEquals(1_500, Quantities.parseMilli("1.5", "kg"));
        assertEquals(250, Quantities.parseMilli("0,250", "litre"));
        assertEquals(1, Quantities.parseMilli("0.001", "kg"));
        assertEquals(1_234, Quantities.parseMilli("1.2340", "kg"));
    }

    @Test
    void piecesAndBagsMustBeWhole() {
        assertProblem(Problem.MUST_BE_WHOLE, "1,5", "piece");
        assertProblem(Problem.MUST_BE_WHOLE, "2.5", "bag");
        // Could mean one or one thousand: refused rather than guessed.
        assertProblem(Problem.MUST_BE_WHOLE, "1,000", "piece");
    }

    @Test
    void invalidTextIsRefused() {
        assertProblem(Problem.BLANK, "   ", "kg");
        assertProblem(Problem.BLANK, null, "kg");
        for (String typed : new String[] {"abc", "-1", "+1", "1e3", "1,2,3", "1.", ",5", "1 000", "١"}) {
            assertProblem(Problem.NOT_A_NUMBER, typed, "kg");
        }
        assertProblem(Problem.NOT_POSITIVE, "0", "piece");
        assertProblem(Problem.NOT_POSITIVE, "0,000", "kg");
        assertProblem(Problem.TOO_MANY_DECIMALS, "1.2345", "kg");
    }

    @Test
    void numbersThatDoNotFitAreRefused() throws Exception {
        assertEquals(Long.MAX_VALUE, Quantities.parseMilli("9223372036854775.807", "kg"));
        assertProblem(Problem.TOO_LARGE, "9223372036854775.808", "kg");
        assertProblem(Problem.TOO_LARGE, "9".repeat(31), "piece");
    }

    @Test
    void unknownUnitsAreAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> Quantities.parseMilli("1", "box"));
    }

    @Test
    void formattedTextCanBeTypedBackIn() throws Exception {
        assertEquals("1,5", Quantities.format(1_500, Locale.FRENCH));
        assertEquals("1.5", Quantities.format(1_500, Locale.ENGLISH));
        assertEquals("2", Quantities.format(2_000, Locale.FRENCH));
        assertEquals("1000", Quantities.format(1_000_000, Locale.FRENCH));
        assertEquals(1_234_567, Quantities.parseMilli(Quantities.format(1_234_567, Locale.FRENCH), "kg"));
    }
}
