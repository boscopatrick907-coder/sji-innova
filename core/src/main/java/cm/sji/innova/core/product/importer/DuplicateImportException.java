package cm.sji.innova.core.product.importer;

import java.sql.SQLException;
/** A new duplicate candidate found under the import write lock; no product was saved. */
public final class DuplicateImportException extends SQLException {
    public DuplicateImportException(){super("Duplicate candidate requires explicit confirmation");}
}
