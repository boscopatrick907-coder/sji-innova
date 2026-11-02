package cm.sji.innova.core.sale;

import java.util.Locale;

public enum SaleStatus {
    PAID("paid"),
    VOIDED("voided");

    private final String code;

    SaleStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static SaleStatus fromCode(String code) {
        return valueOf(code.toUpperCase(Locale.ROOT));
    }
}

