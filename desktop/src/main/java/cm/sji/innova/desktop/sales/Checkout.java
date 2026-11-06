package cm.sji.innova.desktop.sales;

import cm.sji.innova.core.sale.Sale;
import cm.sji.innova.core.sale.SaleResult;
import cm.sji.innova.core.sale.SaleService;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The safe way to send a sale, without any screen code. The rule: <b>save the details first, send
 * second, forget them only once the answer is certain.</b> If anything goes wrong in between, the
 * details are still on disk and the same sale can be sent again without creating a second one.
 * Every method here can be slow: call them off the Swing event thread.
 */
public final class Checkout {
    private static final Logger LOG = Logger.getLogger(Checkout.class.getName());

    /** What happened when a sale was sent, retried or cancelled. */
    public sealed interface Outcome {
        /** The sale is in the database. These are its saved details. */
        record Saved(Sale sale) implements Outcome { }
        /** The details could not be saved, so the sale was not sent. */
        record NotSent(IOException cause) implements Outcome { }
        /** The sale was refused and is certainly not in the database. */
        record Rejected(Reason reason, RuntimeException cause) implements Outcome { }
        /** There is no certain answer: the sale may or may not be saved, and the details are kept. */
        record Uncertain(Exception cause) implements Outcome { }
        /** The cashier cancelled, and the sale is certainly not in the database. */
        record Discarded() implements Outcome { }
    }

    public enum Reason { NOT_ENOUGH_STOCK, REFUSED }

    /** What was found when the screen opened. Finding something never sends it. */
    public sealed interface Recovery {
        record Nothing() implements Recovery { }
        /** The earlier sale did reach the database. */
        record AlreadySaved(Sale sale) implements Recovery { }
        /** The earlier sale is not in the database: the cashier decides what happens next. */
        record Unfinished(PendingCheckout pending) implements Recovery { }
        /** The file cannot be used. It was left untouched. */
        record Damaged(Path file, Exception cause) implements Recovery { }
        /** Another process is actively confirming this data folder's checkout. */
        record Busy() implements Recovery { }
    }

    private final SaleService sales;
    private final PendingCheckoutStore store;
    private final UUID shopId;

    public Checkout(SaleService sales, PendingCheckoutStore store, UUID shopId) {
        this.sales = Objects.requireNonNull(sales, "sales");
        this.store = Objects.requireNonNull(store, "store");
        this.shopId = Objects.requireNonNull(shopId, "shopId");
    }

    /** First attempt: saves the details, then sends. If saving fails, nothing is sent. */
    public Outcome submit(PendingCheckout pending) {
        Outcome answer = null;
        boolean persisted = false;
        try(var lease=store.acquire()) {
            if(!shopId.equals(pending.shopId()))return new Outcome.Rejected(Reason.REFUSED,new IllegalArgumentException("Wrong shop"));
            store.save(pending);
            persisted = true;
            answer = send(pending);
            return answer;
        } catch (IOException failure) {
            if (answer != null) return answer;
            return persisted ? new Outcome.Uncertain(failure) : new Outcome.NotSent(failure);
        }
    }

    /** Sends the same sale again. SaleService returns the saved sale if the first attempt worked. */
    public Outcome retry(PendingCheckout pending) {
        try(var lease=store.acquire()) {
            store.requireOwner(pending);
            if(store.load().isEmpty()) {
                var saved=sales.getSale(pending.shopId(),pending.saleId());
                return saved.<Outcome>map(Outcome.Saved::new).orElseGet(()->new Outcome.Uncertain(new IOException("The pending checkout file is missing")));
            }
            return send(pending);
        }catch(IOException|SQLException failure){return new Outcome.Uncertain(failure);}
    }

    /** Cancels only after checking the database: a sale that was really saved is reported as saved. */
    public Outcome discard(PendingCheckout pending) {
        try(var lease=store.acquire()) {
            store.requireOwner(pending);
            Optional<Sale> saved = sales.getSale(pending.shopId(), pending.saleId());
            if (saved.isPresent()) {
                forgetPending(pending.saleId());
                return new Outcome.Saved(saved.get());
            }
            store.clear(pending.saleId());
            return new Outcome.Discarded();
        } catch (SQLException | IOException | RuntimeException failure) {
            return new Outcome.Uncertain(failure);
        }
    }

    /** Looks for a sale left by an earlier run. It reads, and never sends anything. */
    public Recovery recover() {
        try(var lease=store.acquire()) {
            return recoverLocked();
        }catch(PendingCheckoutStore.BusyException busy){return new Recovery.Busy();}
        catch(IOException failure){return new Recovery.Damaged(store.file(),failure);}
    }
    private Recovery recoverLocked() {
        PendingCheckout pending;
        try {
            Optional<PendingCheckout> loaded = store.load();
            if (loaded.isEmpty()) return new Recovery.Nothing();
            pending = loaded.get();
        } catch (IOException damaged) {
            return new Recovery.Damaged(store.file(), damaged);
        }
        if (!shopId.equals(pending.shopId())) {
            return new Recovery.Damaged(store.file(), new IOException("The file belongs to another shop"));
        }
        try {
            Optional<Sale> saved = sales.getSale(pending.shopId(), pending.saleId());
            if (saved.isPresent()) {
                forgetPending(pending.saleId());
                return new Recovery.AlreadySaved(saved.get());
            }
        } catch (SQLException | RuntimeException lookupFailed) {
            LOG.log(Level.WARNING, lookupFailed, () -> "Could not check sale " + pending.receiptNumber());
        }
        return new Recovery.Unfinished(pending);
    }

    public Path setAsideDamagedFile() throws IOException { return store.setAside(); }

    private Outcome send(PendingCheckout pending) {
        try {
            SaleResult result = sales.makeCashSale(pending.saleId(), pending.shopId(), pending.deviceId(),
                    pending.receiptNumber(), pending.requests());
            forgetPending(pending.saleId());
            return new Outcome.Saved(result.sale());
        } catch (IllegalStateException notEnoughStock) {
            return afterRefusal(pending, Reason.NOT_ENOUGH_STOCK, notEnoughStock);
        } catch (IllegalArgumentException | ArithmeticException refused) {
            return afterRefusal(pending, Reason.REFUSED, refused);
        } catch (SQLException | RuntimeException unknown) {
            return new Outcome.Uncertain(unknown);
        }
    }

    /** Trusts a refusal only after checking that the sale is really not in the database. */
    private Outcome afterRefusal(PendingCheckout pending, Reason reason, RuntimeException cause) {
        try {
            Optional<Sale> saved = sales.getSale(pending.shopId(), pending.saleId());
            forgetPending(pending.saleId());
            if (saved.isPresent()) return new Outcome.Saved(saved.get());
            return new Outcome.Rejected(reason, cause);
        } catch (SQLException | RuntimeException lookupFailed) {
            lookupFailed.addSuppressed(cause);
            return new Outcome.Uncertain(lookupFailed);
        }
    }

    /**
     * The answer is already certain here, so a file that cannot be removed is not an error. If it
     * stays, the next opening finds it, checks the database and cleans up.
     */
    private void forgetPending(UUID saleId) {
        try {
            store.clear(saleId);
        } catch (IOException failure) {
            LOG.log(Level.WARNING, failure, () -> "Could not remove " + store.file());
        }
    }
}
