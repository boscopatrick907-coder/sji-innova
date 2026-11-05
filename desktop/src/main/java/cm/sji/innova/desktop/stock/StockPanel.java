package cm.sji.innova.desktop.stock;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;
import cm.sji.innova.core.stock.StockLevel;
import cm.sji.innova.core.stock.StockMovement;
import cm.sji.innova.core.stock.StockResult;
import cm.sji.innova.core.stock.StockService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public final class StockPanel extends JPanel {

    private final StockService stock;
    private final ProductService products;
    private final UUID shopId;
    private final UUID deviceId;
    private final ResourceBundle messages;

    private final JLabel statusLabel = new JLabel(" ");
    private final JComboBox<Product> productSelector = new JComboBox<>();
    private final JTextField quantityField = new JTextField(12);
    private final JTextField reasonField = new JTextField(20);

    private final JButton receiveButton = new JButton();
    private final JButton refreshButton = new JButton();

    private final DefaultTableModel stockModel;
    private final DefaultTableModel historyModel;

    private final JTable stockTable;
    private final JTable historyTable;

    private final CompletableFuture<Void> initialLoad = new CompletableFuture<>();

    private volatile Runnable onLoadWorkerDone;
    private volatile Runnable onReceiptWorkerDone;
    private volatile Runnable onHistoryWorkerDone;

    private Consumer<String> errorHandler = this::displayErrorDialog;

    private PendingReceipt pendingReceipt;
    private Product pendingProduct;

    private long loadGeneration;
    private long historyGeneration;

    private boolean loadingProducts;
    private boolean receiptPending;

    public StockPanel(
            StockService stock,
            ProductService products,
            UUID shopId,
            UUID deviceId,
            ResourceBundle messages) {

        this(stock, products, shopId, deviceId, messages, null, null);
    }

    StockPanel(
            StockService stock,
            ProductService products,
            UUID shopId,
            UUID deviceId,
            ResourceBundle messages,
            Runnable onLoadWorkerDone,
            Runnable onReceiptWorkerDone) {

        this.stock = Objects.requireNonNull(stock, "stock");
        this.products = Objects.requireNonNull(products, "products");
        this.shopId = Objects.requireNonNull(shopId, "shopId");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.messages = Objects.requireNonNull(messages, "messages");

        this.onLoadWorkerDone = onLoadWorkerDone;
        this.onReceiptWorkerDone = onReceiptWorkerDone;

        stockModel = new DefaultTableModel(new Object[]{
                message("stock.column.product", "Product"),
                message("stock.column.unit", "Unit"),
                message("stock.column.available", "Available"),
                message("stock.column.minimum", "Minimum"),
                message("stock.column.status", "Status")
        }, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        historyModel = new DefaultTableModel(new Object[]{
                message("stock.history.date", "Date"),
                message("stock.history.type", "Type"),
                message("stock.history.quantity", "Quantity"),
                message("stock.history.reason", "Reason")
        }, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        stockTable = new JTable(stockModel);
        historyTable = new JTable(historyModel);

        createInterface();
        loadProductsAndStock();
    }

    private String message(String key, String fallback) {
        try {
            return messages.getString(key);
        } catch (MissingResourceException exception) {
            return fallback;
        }
    }

    private void createInterface() {
        setLayout(new BorderLayout(15, 15));
        setBorder(new EmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel(
                message("stock.title", "Stock Management"));
        title.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 25));
        setBackground(cm.sji.innova.desktop.catalog.CatalogStyle.PAPER);

        JLabel subtitle = new JLabel(message(
                "stock.subtitle",
                "Monitor quantities, receive deliveries and view movements."));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.add(title);
        header.add(Box.createVerticalStrut(5));
        header.add(subtitle);

        add(header, BorderLayout.NORTH);

        JPanel main = new JPanel(new BorderLayout(10, 10));

        main.add(createReceiptForm(), BorderLayout.NORTH);
        main.add(createTables(), BorderLayout.CENTER);
        main.add(createRefreshPanel(), BorderLayout.SOUTH);

        add(main, BorderLayout.CENTER);

        statusLabel.setForeground(Color.DARK_GRAY);
        add(statusLabel, BorderLayout.SOUTH);
    }

    private JPanel createReceiptForm() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                message("stock.receive.title", "Receive Stock")));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;

        productSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean selected,
                    boolean focused) {

                super.getListCellRendererComponent(
                        list, value, index, selected, focused);

                if (value instanceof Product product) {
                    setText((product.reference() == null ? "" : product.reference() + " · ")
                            + product.name() + " (" + message("unit." + product.unit(), product.unit()) + ")");
                }

                return this;
            }
        });

        addFormRow(panel, c, 0,
                message("stock.product", "Product"),
                productSelector);

        addFormRow(panel, c, 1,
                message("stock.quantity", "Quantity"),
                quantityField);

        addFormRow(panel, c, 2,
                message("stock.reason", "Reason / delivery reference"),
                reasonField);

        receiveButton.setText(
                message("stock.action.receive", "Receive Stock"));
        receiveButton.addActionListener(e -> submitReceipt());

        c.gridx = 1;
        c.gridy = 3;
        c.gridwidth = 2;
        panel.add(receiveButton, c);

        productSelector.addActionListener(e -> {
            if (!loadingProducts && !receiptPending) {
                loadHistoryForSelectedProduct();
            }
        });

        return panel;
    }

    private void addFormRow(
            JPanel panel,
            GridBagConstraints c,
            int row,
            String label,
            JComponent component) {

        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0;

        JLabel fieldLabel = new JLabel(label + ":");
        fieldLabel.setLabelFor(component);
        component.getAccessibleContext().setAccessibleName(label);
        panel.add(fieldLabel, c);

        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;

        panel.add(component, c);

        c.gridwidth = 1;
    }

    private JPanel createTables() {
        stockTable.setRowHeight(28);
        historyTable.setRowHeight(26);

        JScrollPane stockScroll = new JScrollPane(stockTable);
        stockScroll.setBorder(BorderFactory.createTitledBorder(
                message("stock.overview.title", "Stock Overview")));

        JScrollPane historyScroll = new JScrollPane(historyTable);
        historyScroll.setBorder(BorderFactory.createTitledBorder(
                message("stock.history.title", "Movement History")));

        JPanel panel = new JPanel(new GridLayout(2, 1, 0, 10));
        panel.add(stockScroll);
        panel.add(historyScroll);

        return panel;
    }

    private JPanel createRefreshPanel() {
        JPanel panel = new JPanel(
                new FlowLayout(FlowLayout.RIGHT));

        refreshButton.setText(
                message("action.refresh", "Refresh"));

        refreshButton.addActionListener(e -> loadProductsAndStock());

        panel.add(refreshButton);
        return panel;
    }

    public void loadProductsAndStock() {
        if (receiptPending) return;
        Product previous = (Product) productSelector.getSelectedItem();
        UUID selectedId = pendingReceipt != null ? pendingReceipt.productId()
                : previous == null ? null : previous.id();
        long generation = ++loadGeneration;

        refreshButton.setEnabled(false);
        receiveButton.setEnabled(false);

        statusLabel.setText(
                message("state.loading", "Loading products and stock..."));

        new SwingWorker<InitialData, Void>() {

            @Override
            protected InitialData doInBackground() throws Exception {
                List<Product> productList = products.list(shopId);
                List<StockLevel> stockList = stock.list(shopId);

                return new InitialData(productList, stockList);
            }

            @Override
            protected void done() {
                Throwable failure = null;

                try {
                    if (generation != loadGeneration) {
                        return;
                    }

                    InitialData data = get();

                    loadingProducts = true;
                    productSelector.removeAllItems();

                    for (Product product : data.products()) {
                        productSelector.addItem(product);
                    }

                    if (selectedId != null) {
                        boolean found = false;
                        for (int i = 0; i < productSelector.getItemCount(); i++) {
                            if (selectedId.equals(productSelector.getItemAt(i).id())) {
                                productSelector.setSelectedIndex(i); found = true; break;
                            }
                        }
                        if (!found && pendingProduct != null) {
                            productSelector.addItem(pendingProduct);
                            productSelector.setSelectedItem(pendingProduct);
                        }
                    }
                    loadingProducts = false;
                    setReceiptControlsEnabled(pendingReceipt == null && !data.products().isEmpty());

                    displayStock(data.levels());

                    if (data.products().isEmpty()) {
                        statusLabel.setText(message(
                                "stock.empty",
                                "No products found for this shop."));
                    } else {
                        statusLabel.setText(
                                data.products().size()
                                        + " "
                                        + message("stock.products.loaded",
                                        "product(s) loaded."));
                    }

                    receiveButton.setEnabled(
                            !data.products().isEmpty() && !receiptPending);

                    loadHistoryForSelectedProduct();

                } catch (InterruptedException exception) {
                    failure = exception;
                    Thread.currentThread().interrupt();
                    showError(message(
                            "error.stock.load",
                            "Stock loading was interrupted."));

                } catch (ExecutionException exception) {
                    failure = exception.getCause() == null
                            ? exception : exception.getCause();

                    loadingProducts = false;

                    showError(message(
                            "error.stock.load",
                            "Unable to load products or stock")
                            + ": " + errorMessage(failure));

                } finally {
                    if (generation == loadGeneration) {
                        refreshButton.setEnabled(true);
                    }

                    notifyLoadWorkerDone();

                    if (generation == 1) {
                        if (failure == null) {
                            initialLoad.complete(null);
                        } else {
                            initialLoad.completeExceptionally(failure);
                        }
                    }
                }
            }
        }.execute();
    }

    private void displayStock(List<StockLevel> levels) {
        stockModel.setRowCount(0);

        for (StockLevel level : levels) {
            stockModel.addRow(new Object[]{
                    level.productName(),
                    message("unit." + level.unit(), level.unit()),
                    formatQuantity(level.quantityMilli()),
                    formatQuantity(level.minimumMilli()),
                    level.lowStock()
                            ? message("stock.status.low", "LOW STOCK")
                            : message("stock.status.available", "AVAILABLE")
            });
        }
    }

    private void loadHistoryForSelectedProduct() {
        Product selected = (Product) productSelector.getSelectedItem();

        long generation = ++historyGeneration;

        if (selected == null) {
            historyModel.setRowCount(0);

            Runnable callback = onHistoryWorkerDone;
            if (callback != null) {
                callback.run();
            }

            return;
        }

        UUID productId = selected.id();

        historyModel.setRowCount(0);

        new SwingWorker<List<StockMovement>, Void>() {

            @Override
            protected List<StockMovement> doInBackground() throws Exception {
                return stock.history(shopId, productId);
            }

            @Override
            protected void done() {
                try {
                    if (generation != historyGeneration) {
                        return;
                    }

                    List<StockMovement> movements = get();

                    historyModel.setRowCount(0);

                    // Keep the order returned by StockService.
                    for (StockMovement movement : movements) {
                        historyModel.addRow(new Object[]{
                                movement.createdAt(),
                                message("stock.type." + movement.type().name().toLowerCase(java.util.Locale.ROOT), movement.type().name()),
                                formatQuantity(movement.quantityMilli()),
                                movement.reason()
                        });
                    }

                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();

                    showError(message(
                            "error.stock.history",
                            "Movement history loading was interrupted."));

                } catch (ExecutionException exception) {
                    showError(message(
                            "error.stock.history",
                            "Unable to load movement history")
                            + ": " + errorMessage(exception.getCause()));

                } finally {
                    Runnable callback = onHistoryWorkerDone;

                    if (callback != null) {
                        callback.run();
                    }
                }
            }
        }.execute();
    }

    private void submitReceipt() {
        if (receiptPending) {
            return;
        }

        if (pendingReceipt == null) {
            Product selected = (Product) productSelector.getSelectedItem();

            if (selected == null) {
                showError(message(
                        "error.stock.selectProduct",
                        "Please select a product."));
                return;
            }

            String reason = reasonField.getText().strip();

            if (reason.isEmpty()) {
                showError(message(
                        "error.stock.reasonRequired",
                        "A delivery reason or reference is required."));
                reasonField.requestFocusInWindow();
                return;
            }

            long quantity;

            try {
                quantity = parseQuantity(
                        quantityField.getText(),
                        selected.unit());

            } catch (IllegalArgumentException exception) {
                showError(exception.getMessage());
                quantityField.requestFocusInWindow();
                return;
            }

            pendingProduct = selected;
            pendingReceipt = new PendingReceipt(
                    UUID.randomUUID(),
                    selected.id(),
                    selected.unit(),
                    quantity,
                    reason);
        }

        submitPendingReceipt();
    }

    private void submitPendingReceipt() {
        if (pendingReceipt == null || receiptPending) {
            return;
        }

        PendingReceipt request = pendingReceipt;

        receiptPending = true;
        setReceiptControlsEnabled(false);

        receiveButton.setEnabled(false);
        refreshButton.setEnabled(false);

        statusLabel.setText(message(
                "state.saving",
                "Saving receipt..."));

        new SwingWorker<StockResult, Void>() {

            @Override
            protected StockResult doInBackground() throws Exception {
                return stock.receive(
                        request.operationId(),
                        shopId,
                        request.productId(),
                        deviceId,
                        request.quantityMilli(),
                        request.reason());
            }

            @Override
            protected void done() {
                try {
                    StockResult result = get();

                    // The service confirmed this exact receipt.
                    pendingReceipt = null;
                    pendingProduct = null;
                    receiptPending = false;

                    setReceiptControlsEnabled(true);

                    quantityField.setText("");
                    reasonField.setText("");

                    receiveButton.setText(message(
                            "stock.action.receive",
                            "Receive Stock"));

                    statusLabel.setText(result.replayed()
                            ? message("stock.receipt.replayed",
                            "Receipt confirmed (retried operation).")
                            : message("stock.receipt.success",
                            "Stock received successfully."));

                    loadProductsAndStock();

                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    preservePendingReceipt();

                    showError(message(
                            "error.stock.uncertain",
                            "Receipt outcome uncertain. Retry the same receipt."));

                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof IllegalArgumentException || cause instanceof ArithmeticException) {
                        // These are deterministic service rejections, not uncertain commits.
                        pendingReceipt = null;
                        pendingProduct = null;
                        receiptPending = false;
                        setReceiptControlsEnabled(true);
                        receiveButton.setEnabled(true);
                        refreshButton.setEnabled(true);
                        receiveButton.setText(message("stock.action.receive", "Receive Stock"));
                    } else {
                        preservePendingReceipt();
                    }
                    showError(message(
                            "error.stock.receipt",
                            "Receipt failed or its outcome is uncertain")
                            + ": " + errorMessage(cause));

                } finally {
                    notifyReceiptWorkerDone();
                }
            }
        }.execute();
    }

    private void preservePendingReceipt() {
        receiptPending = false;

        // Do not change the pending request or its operation UUID.
        setReceiptControlsEnabled(false);

        receiveButton.setText(message(
                "stock.action.retry",
                "Retry Original Receipt"));

        receiveButton.setEnabled(true);
        refreshButton.setEnabled(true);

        statusLabel.setText(message(
                "stock.receipt.pending",
                "The original receipt is pending. Retry it without changing its details."));
    }

    private void setReceiptControlsEnabled(boolean enabled) {
        productSelector.setEnabled(enabled);
        quantityField.setEnabled(enabled);
        reasonField.setEnabled(enabled);
    }

    private long parseQuantity(String input, String unit) {
        String value = input.strip().replace(',', '.');

        if (!value.matches("\\d+(\\.\\d{1,3})?")) {
            throw new IllegalArgumentException(message(
                    "error.stock.quantityFormat",
                    "Enter a positive quantity with at most 3 decimal places."));
        }

        try {
            BigDecimal decimal = new BigDecimal(value);

            if (decimal.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(message(
                        "error.stock.quantityPositive",
                        "Quantity must be greater than zero."));
            }

            long milli = decimal
                    .setScale(3, RoundingMode.UNNECESSARY)
                    .movePointRight(3)
                    .longValueExact();

            if (("piece".equals(unit) || "bag".equals(unit)) && milli % 1000 != 0) {
                throw new IllegalArgumentException(message(
                        "error.stock.wholePieces",
                        "Piece and bag quantities must be whole numbers."));
            }

            return milli;

        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(message(
                    "error.stock.quantityLarge",
                    "Quantity is too large or has invalid precision."));
        }
    }

    private String formatQuantity(long milli) {
        return BigDecimal.valueOf(milli, 3)
                .stripTrailingZeros()
                .toPlainString();
    }

    private void showError(String text) {
        statusLabel.setText(text);

        if (errorHandler != null) {
            errorHandler.accept(text);
        }
    }

    private String errorMessage(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }

        String text = error.getMessage();

        return text == null || text.isBlank()
                ? error.getClass().getSimpleName()
                : text;
    }

    private void displayErrorDialog(String text) {
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(
                    this,
                    text,
                    message("stock.title", "Stock Management"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void notifyLoadWorkerDone() {
        Runnable callback = onLoadWorkerDone;
        if (callback != null) {
            callback.run();
        }
    }

    private void notifyReceiptWorkerDone() {
        Runnable callback = onReceiptWorkerDone;
        if (callback != null) {
            callback.run();
        }
    }

    public CompletionStage<Void> initialLoadCompletion() {
        return initialLoad.minimalCompletionStage();
    }

    void setErrorHandler(Consumer<String> handler) {
        errorHandler = Objects.requireNonNull(handler);
    }

    void setOnLoadWorkerDone(Runnable callback) {
        onLoadWorkerDone = callback;
    }

    void setOnReceiptWorkerDone(Runnable callback) {
        onReceiptWorkerDone = callback;
    }

    void setOnHistoryWorkerDone(Runnable callback) {
        onHistoryWorkerDone = callback;
    }

    // Package-private accessors for StockPanel tests.
    JTable stockTable() {
        return stockTable;
    }

    JTable historyTable() {
        return historyTable;
    }

    JComboBox<Product> productSelector() {
        return productSelector;
    }

    JTextField quantityField() {
        return quantityField;
    }

    JTextField reasonField() {
        return reasonField;
    }

    JButton receiveButton() {
        return receiveButton;
    }

    JButton refreshButton() {
        return refreshButton;
    }

    JLabel statusLabel() {
        return statusLabel;
    }

    boolean hasPendingReceipt() {
        return pendingReceipt != null;
    }

    private record InitialData(
            List<Product> products,
            List<StockLevel> levels) {
    }

    private record PendingReceipt(
            UUID operationId,
            UUID productId,
            String unit,
            long quantityMilli,
            String reason) {
    }
}
