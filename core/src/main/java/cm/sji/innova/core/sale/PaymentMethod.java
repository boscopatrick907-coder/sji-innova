package cm.sji.innova.core.sale;

import java.util.Locale;

public enum PaymentMethod {
    CASH, MOMO, CREDIT;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PaymentMethod fromCode(String code) {
        if (code == null) throw new IllegalArgumentException("Payment method cannot be null");
        return valueOf(code.strip().toUpperCase(Locale.ROOT));
    }
}
