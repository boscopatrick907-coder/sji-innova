package cm.sji.innova.desktop.sales;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns what the cashier types into an exact quantity, and back into text.
 *
 * <p>A quantity is a whole number of thousandths in a {@code long}: 1000 means 1 piece, bag, kg or
 * litre, and 1500 means 1.5 kg. Text goes through {@link BigDecimal}, never {@code double}, so
 * nothing is ever rounded by accident. A comma or a dot can separate the decimals.
 */
public final class Quantities {
    /** The internal value of exactly one piece, bag, kg or litre. */
    public static final long ONE = 1000L;

    private static final Pattern NUMBER = Pattern.compile("[0-9]+([.,][0-9]+)?");
    private static final int LONGEST_NUMBER = 30;

    /** What is wrong with a typed quantity; the screen turns it into a translated message. */
    public enum Problem { BLANK, NOT_A_NUMBER, NOT_POSITIVE, MUST_BE_WHOLE, TOO_MANY_DECIMALS, TOO_LARGE }

    public static final class InvalidQuantityException extends Exception {
        private final Problem problem;

        InvalidQuantityException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }

        public Problem problem() { return problem; }
    }

    private Quantities() { }

    /** Pieces and bags can only be whole; kg and litres allow three decimals. */
    public static boolean isWholeUnit(String unit) {
        return switch (unit) {
            case "piece", "bag" -> true;
            case "kg", "litre" -> false;
            default -> throw new IllegalArgumentException("Unknown unit: " + unit);
        };
    }

    public static long parseMilli(String text, String unit) throws InvalidQuantityException {
        boolean whole = isWholeUnit(unit);
        String typed = text == null ? "" : text.strip();
        if (typed.isEmpty()) throw new InvalidQuantityException(Problem.BLANK);
        if (!NUMBER.matcher(typed).matches()) throw new InvalidQuantityException(Problem.NOT_A_NUMBER);
        if (typed.length() > LONGEST_NUMBER) throw new InvalidQuantityException(Problem.TOO_LARGE);
        // "1,000" could mean one or one thousand, so whole units accept digits only.
        boolean hasSeparator = typed.contains(",") || typed.contains(".");
        if (whole && hasSeparator) throw new InvalidQuantityException(Problem.MUST_BE_WHOLE);

        BigDecimal milli = new BigDecimal(typed.replace(',', '.')).movePointRight(3);
        if (milli.signum() == 0) throw new InvalidQuantityException(Problem.NOT_POSITIVE);
        if (milli.stripTrailingZeros().scale() > 0) throw new InvalidQuantityException(Problem.TOO_MANY_DECIMALS);
        try {
            return milli.longValueExact();
        } catch (ArithmeticException tooLarge) {
            throw new InvalidQuantityException(Problem.TOO_LARGE);
        }
    }

    /** Text for the screen. It has no thousands separator, so the cashier can edit and re-enter it. */
    public static String format(long quantityMilli, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMaximumFractionDigits(3);
        format.setGroupingUsed(false);
        return format.format(BigDecimal.valueOf(quantityMilli, 3));
    }
}
