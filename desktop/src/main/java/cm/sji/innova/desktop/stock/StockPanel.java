package cm.sji.innova.desktop.stock;

import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.catalog.FeedbackStrip;
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
import java.io.IOException;
import java.util.Optional;
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
    private final PendingReceiptStore pendingReceiptStore;
    private final PendingStockAdjustmentStore pendingAdjustmentStore;
    private boolean recoveryBlocked;

    private final JLabel statusLabel = new JLabel(" ");
    private FeedbackStrip statusFeedback;
    private final JComboBox<Product> productSelector = new JComboBox<>();
    private final JTextField quantityField = new JTextField(12);
    private final JTextField reasonField = new JTextField(20);
    private final JComboBox<String> adjustmentDirection = new JComboBox<>(new String[]{"Add", "Remove"});
    private final JTextField adjustmentQuantityField = new JTextField(12);
    private final JTextField adjustmentReasonField = new JTextField(20);
    private final JButton adjustButton = CatalogStyle.button("Apply Adjustment", true);
    private final JTextField minimumField = new JTextField(12);
    private final JButton saveMinimumButton = CatalogStyle.button("Save Minimum", false);
    private final java.util.Map<UUID, Long> minimumByProduct = new java.util.HashMap<>();
    private boolean minimumSaving;

    private final JButton receiveButton = CatalogStyle.button("",true);
    private final JButton refreshButton = CatalogStyle.button("",false);

    private final DefaultTableModel stockModel;
    private final DefaultTableModel historyModel;

    private final JTable stockTable;
    private final JTable historyTable;

    private final CompletableFuture<Void> initialLoad = new CompletableFuture<>();

    private volatile Runnable onLoadWorkerDone;
    private volatile Runnable onReceiptWorkerDone;
    private volatile Runnable onHistoryWorkerDone;
    private volatile Runnable onMinimumWorkerDone;

    private Consumer<String> errorHandler = this::displayErrorDialog;

    private PendingReceipt pendingReceipt;
    private Product pendingProduct;
    private PendingStockAdjustment pendingAdjustment;
    private Product pendingAdjustmentProduct;

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
        this(stock, products, shopId, deviceId, messages,
                new InMemoryPendingReceiptStore(), new InMemoryPendingStockAdjustmentStore(), null, null);
    }

    public StockPanel(
            StockService stock,
            ProductService products,
            UUID shopId,
            UUID deviceId,
            ResourceBundle messages,
            PendingReceiptStore pendingReceiptStore) {
        this(stock, products, shopId, deviceId, messages,
                pendingReceiptStore, new InMemoryPendingStockAdjustmentStore(), null, null);
    }

    StockPanel(
            StockService stock,
            ProductService products,
            UUID shopId,
            UUID deviceId,
            ResourceBundle messages,
            Runnable onLoadWorkerDone,
            Runnable onReceiptWorkerDone) {
        this(stock, products, shopId, deviceId, messages,
                new InMemoryPendingReceiptStore(), new InMemoryPendingStockAdjustmentStore(), onLoadWorkerDone, onReceiptWorkerDone);
    }

    public StockPanel(StockService stock, ProductService products, UUID shopId, UUID deviceId,
                      ResourceBundle messages, PendingReceiptStore pendingReceiptStore,
                      PendingStockAdjustmentStore pendingAdjustmentStore) {
        this(stock, products, shopId, deviceId, messages, pendingReceiptStore, pendingAdjustmentStore, null, null);
    }

    StockPanel(
            StockService stock,
            ProductService products,
            UUID shopId,
            UUID deviceId,
            ResourceBundle messages,
            PendingReceiptStore pendingReceiptStore,
            Runnable onLoadWorkerDone,
            Runnable onReceiptWorkerDone) {
        this(stock, products, shopId, deviceId, messages, pendingReceiptStore,
                new InMemoryPendingStockAdjustmentStore(), onLoadWorkerDone, onReceiptWorkerDone);
    }

    StockPanel(
            StockService stock,
            ProductService products,
            UUID shopId,
            UUID deviceId,
            ResourceBundle messages,
            PendingReceiptStore pendingReceiptStore,
            PendingStockAdjustmentStore pendingAdjustmentStore,
            Runnable onLoadWorkerDone,
            Runnable onReceiptWorkerDone) {

        this.stock = Objects.requireNonNull(stock, "stock");
        this.products = Objects.requireNonNull(products, "products");
        this.shopId = Objects.requireNonNull(shopId, "shopId");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.pendingReceiptStore = Objects.requireNonNull(pendingReceiptStore, "pendingReceiptStore");
        this.pendingAdjustmentStore = Objects.requireNonNull(pendingAdjustmentStore, "pendingAdjustmentStore");
        this.onLoadWorkerDone = onLoadWorkerDone;
        this.onReceiptWorkerDone = onReceiptWorkerDone;

        stockModel = new DefaultTableModel(new Object[]{
                message("stock.column.product", "Product"),
                message("stock.column.unit", "Unit"),
                message("stock.column.available", "Available"),
                message("stock.column.minimum", "Minimum"),
                message("stock.column.status", "Status")
        }, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };

        historyModel = new DefaultTableModel(new Object[]{
                message("stock.history.date", "Date"),
                message("stock.history.type", "Type"),
                message("stock.history.quantity", "Quantity"),
                message("stock.history.reason", "Reason")
        }, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
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
        setLayout(new BorderLayout(0,18));setBorder(new EmptyBorder(22,30,18,30));setBackground(CatalogStyle.PAPER);
        JPanel titles=CatalogStyle.panel(new BorderLayout(0,7));
        titles.add(CatalogStyle.label(message("stock.title","Stock Management"),28,true),BorderLayout.NORTH);
        JLabel subtitle=CatalogStyle.label(message("stock.subtitle","Monitor quantities, receive deliveries and view movements."),13,false);
        subtitle.setForeground(CatalogStyle.MUTED);titles.add(subtitle,BorderLayout.CENTER);
        JPanel header=CatalogStyle.panel(new BorderLayout(12,0));header.add(titles,BorderLayout.CENTER);
        header.add(createRefreshPanel(),BorderLayout.EAST);add(header,BorderLayout.NORTH);
        JPanel main=CatalogStyle.panel(new BorderLayout(0,16));
        JPanel forms = CatalogStyle.panel(new GridLayout(3, 1, 0, 12));
        forms.add(CatalogStyle.widthLimited(createReceiptForm(), 720));
        forms.add(CatalogStyle.widthLimited(createAdjustmentForm(), 720));
        forms.add(CatalogStyle.widthLimited(createMinimumForm(), 720));
        main.add(forms, BorderLayout.NORTH);
        main.add(createTables(),BorderLayout.CENTER);
        add(CatalogStyle.scrollContent(main,690),BorderLayout.CENTER);
        statusFeedback=CatalogStyle.feedback(statusLabel,FeedbackStrip.Tone.INFO);statusLabel.setName("stockFeedback");add(statusFeedback,BorderLayout.SOUTH);
    }

    private JPanel createReceiptForm() {
        JPanel panel = CatalogStyle.surface(new GridBagLayout());

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx=0;c.gridy=0;c.gridwidth=3;c.weightx=1;
        panel.add(CatalogStyle.label(message("stock.receive.title","Receive Stock"),18,true),c);
        c.gridwidth=1;

        productSelector.setName("stockProduct");quantityField.setName("stockQuantity");reasonField.setName("stockReason");
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

        addFormRow(panel, c, 1,
                message("stock.product", "Product"),
                productSelector);

        addFormRow(panel, c, 2,
                message("stock.quantity", "Quantity"),
                quantityField);

        addFormRow(panel, c, 3,
                message("stock.reason", "Reason / delivery reference"),
                reasonField);

        receiveButton.setText(
                message("stock.action.receive", "Receive Stock"));
        receiveButton.addActionListener(e -> submitReceipt());

        c.gridx = 1;
        c.gridy = 4;
        c.gridwidth = 2;
        panel.add(receiveButton, c);

        productSelector.addActionListener(e -> {
            if (!loadingProducts && !receiptPending) {
                loadHistoryForSelectedProduct();
                updateMinimumFieldForSelectedProduct();
            }
        });

        return panel;
    }

    private JPanel createAdjustmentForm() {
        JPanel panel = CatalogStyle.surface(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.gridy = 0; c.gridwidth = 3; c.weightx = 1;
        panel.add(CatalogStyle.label(message("stock.adjust.title", "Manual Stock Adjustment"), 18, true), c);
        c.gridwidth = 1;
        adjustmentDirection.setName("stockAdjustmentDirection");
        adjustmentQuantityField.setName("stockAdjustmentQuantity");
        adjustmentReasonField.setName("stockAdjustmentReason");
        addFormRow(panel, c, 1, message("stock.adjust.direction", "Action"), adjustmentDirection);
        addFormRow(panel, c, 2, message("stock.adjust.quantity", "Quantity"), adjustmentQuantityField);
        addFormRow(panel, c, 3, message("stock.adjust.reason", "Reason"), adjustmentReasonField);
        adjustButton.setText(message("stock.adjust.action", "Apply Adjustment"));
        adjustButton.addActionListener(e -> submitAdjustment());
        c.gridx = 1; c.gridy = 4; c.gridwidth = 2;
        panel.add(adjustButton, c);
        return panel;
    }

    private JPanel createMinimumForm() {
        JPanel panel = CatalogStyle.surface(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.gridy = 0; c.gridwidth = 3; c.weightx = 1;
        panel.add(CatalogStyle.label(message("stock.minimum.title", "Minimum Stock Threshold"), 18, true), c);
        c.gridwidth = 1;
        minimumField.setName("stockMinimum");
        addFormRow(panel, c, 1, message("stock.minimum.quantity", "Minimum quantity"), minimumField);
        saveMinimumButton.setText(message("stock.minimum.save", "Save Minimum"));
        saveMinimumButton.addActionListener(e -> saveMinimum());
        c.gridx = 1; c.gridy = 2; c.gridwidth = 2;
        panel.add(saveMinimumButton, c);
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
        component.putClientProperty("JComponent.minimumHeight",36);
        panel.add(fieldLabel, c);

        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;

        panel.add(component, c);

        c.gridwidth = 1;
    }

    private JPanel createTables() {
        stockTable.setRowHeight(40);historyTable.setRowHeight(40);
        stockTable.setName("stockLevels");historyTable.setName("stockMovements");
        JPanel panel=CatalogStyle.panel(new GridLayout(2,1,0,16));
        panel.add(CatalogStyle.tableCard(message("stock.overview.title","Stock Overview"),stockTable,message("empty.stock","No products yet")));
        panel.add(CatalogStyle.tableCard(message("stock.history.title","Movement History"),historyTable,message("empty.movements","No movements yet")));
        CatalogStyle.alignColumns(stockTable,2,3);CatalogStyle.alignColumns(historyTable,2);
        stockTable.getColumnModel().getColumn(4).setCellRenderer(CatalogStyle.statusBadge(message("stock.status.low","LOW STOCK")));
        return panel;
    }

    private JPanel createRefreshPanel() {
        JPanel panel = CatalogStyle.panel(
                new FlowLayout(FlowLayout.RIGHT,0,0));

        refreshButton.setText(
                message("action.refresh", "Refresh"));

        refreshButton.addActionListener(e -> loadProductsAndStock());

        panel.add(refreshButton);
        return panel;
    }

    public void loadProductsAndStock() {loadProductsAndStock(false);}

    private void loadProductsAndStock(boolean preserveFeedback) {
        if (receiptPending) return;
        final boolean keepFeedback=preserveFeedback||pendingReceipt!=null||pendingAdjustment!=null;
        if(!keepFeedback)statusFeedback.setTone(FeedbackStrip.Tone.INFO);
        Product previous = (Product) productSelector.getSelectedItem();
        UUID selectedId = pendingReceipt != null ? pendingReceipt.productId()
                : pendingAdjustment != null ? pendingAdjustment.productId()
                : previous == null ? null : previous.id();
        long generation = ++loadGeneration;

        refreshButton.setEnabled(false);
        receiveButton.setEnabled(false);
        adjustButton.setEnabled(false);
        saveMinimumButton.setEnabled(false);

        if(!keepFeedback)statusLabel.setText(
                message("state.loading", "Loading products and stock..."));

        new SwingWorker<InitialData, Void>() {

            @Override
            protected InitialData doInBackground() throws Exception {
                List<Product> productList = products.list(shopId);
                List<StockLevel> stockList = stock.list(shopId);
                Optional<PendingReceipt> saved = pendingReceiptStore.load();
                Optional<PendingStockAdjustment> savedAdjustment = pendingAdjustmentStore.load();
                return new InitialData(productList, stockList, saved.orElse(null), savedAdjustment.orElse(null));
            }

            @Override
            protected void done() {
                Throwable failure = null;

                try {
                    if (generation != loadGeneration) {
                        return;
                    }

                    InitialData data = get();
                    boolean pendingOperationExists = data.pendingReceipt() != null || data.pendingAdjustment() != null;
                    boolean preserveOperationFeedback = keepFeedback || pendingOperationExists;
                    if (data.pendingReceipt() != null) {
                        PendingReceipt saved = data.pendingReceipt();
                        if (!shopId.equals(saved.shopId()) || !deviceId.equals(saved.deviceId())) {
                            recoveryBlocked = true;
                            showError(message("stock.receipt.wrongOwner",
                                    "A pending receipt belongs to another shop or device. Do not submit a new receipt."));
                        } else {
                            pendingReceipt = saved;
                            pendingProduct = data.products().stream()
                                    .filter(product -> product.id().equals(saved.productId()))
                                    .findFirst().orElse(null);
                            if (pendingProduct == null || !pendingProduct.unit().equals(saved.unit())) {
                                recoveryBlocked = true;
                                showError(message("stock.receipt.invalidProduct",
                                        "The saved receipt product or unit no longer matches this shop."));
                            }
                        }
                    }

                    if (data.pendingReceipt() != null && data.pendingAdjustment() != null) {
                        recoveryBlocked = true;
                        showError(message("stock.operation.multiplePending",
                                "Both a receipt and an adjustment are pending. Preserve both files and resolve them before continuing."));
                    } else if (data.pendingAdjustment() != null) {
                        PendingStockAdjustment saved = data.pendingAdjustment();
                        if (!shopId.equals(saved.shopId()) || !deviceId.equals(saved.deviceId())) {
                            recoveryBlocked = true;
                            showError(message("stock.adjust.wrongOwner", "A pending adjustment belongs to another shop or device."));
                        } else {
                            pendingAdjustment = saved;
                            pendingAdjustmentProduct = data.products().stream()
                                    .filter(product -> product.id().equals(saved.productId()))
                                    .findFirst().orElse(null);
                            if (pendingAdjustmentProduct == null || !pendingAdjustmentProduct.unit().equals(saved.unit())) {
                                recoveryBlocked = true;
                                showError(message("stock.adjust.invalidProduct", "The saved adjustment product or unit no longer matches this shop."));
                            }
                        }
                    }

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
                    setReceiptControlsEnabled(pendingReceipt == null && pendingAdjustment == null && !recoveryBlocked && !data.products().isEmpty());
                    if (pendingReceipt != null && !recoveryBlocked) {
                        adjustmentDirection.setEnabled(false);
                        adjustmentQuantityField.setEnabled(false);
                        adjustmentReasonField.setEnabled(false);
                        productSelector.setSelectedItem(pendingProduct);
                        quantityField.setText(formatQuantity(pendingReceipt.quantityMilli()));
                        reasonField.setText(pendingReceipt.reason());
                        receiveButton.setText(message("stock.action.retry", "Retry Original Receipt"));
                        statusFeedback.setTone(FeedbackStrip.Tone.WARNING);
                        if (!preserveOperationFeedback) {
    statusLabel.setText(message("stock.receipt.pending",
            "An original receipt is pending. Retry it without changing its details."));
}
                    }

                    if (pendingAdjustment != null && !recoveryBlocked) {
                        productSelector.setSelectedItem(pendingAdjustmentProduct);
                        adjustmentDirection.setSelectedIndex(pendingAdjustment.deltaMilli() < 0 ? 1 : 0);
                        adjustmentQuantityField.setText(formatQuantity(Math.abs(pendingAdjustment.deltaMilli())));
                        adjustmentReasonField.setText(pendingAdjustment.reason());
                        adjustButton.setText(message("stock.adjust.retry", "Retry Original Adjustment"));
                        setReceiptControlsEnabled(false);
                        adjustmentDirection.setEnabled(false); adjustmentQuantityField.setEnabled(false); adjustmentReasonField.setEnabled(false);
                        statusFeedback.setTone(FeedbackStrip.Tone.WARNING);
                        if (!preserveOperationFeedback) statusLabel.setText(message("stock.adjust.pending", "An original adjustment is pending. Retry it without changing its details."));
                    }
                    minimumByProduct.clear();
                    for (StockLevel level : data.levels()) {
                        minimumByProduct.put(level.productId(), level.minimumMilli());
                    }
                    displayStock(data.levels());
                    updateMinimumFieldForSelectedProduct();

                    if (!preserveOperationFeedback&&data.products().isEmpty()) {
                        statusLabel.setText(message(
                                "stock.empty",
                                "No products found for this shop."));
                    } else if(!preserveOperationFeedback) {
                        statusLabel.setText(
                                data.products().size()
                                        + " "
                                        + message("stock.products.loaded",
                                        "product(s) loaded."));
                    }

                    receiveButton.setEnabled(!data.products().isEmpty() && !receiptPending && !recoveryBlocked && pendingAdjustment == null);
                    adjustButton.setEnabled(!data.products().isEmpty() && !receiptPending && !recoveryBlocked && pendingReceipt == null);
                    saveMinimumButton.setEnabled(!data.products().isEmpty() && !receiptPending && !recoveryBlocked
                            && pendingReceipt == null && pendingAdjustment == null);
                    minimumField.setEnabled(!receiptPending && !recoveryBlocked
                            && pendingReceipt == null && pendingAdjustment == null);

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
                    recoveryBlocked = true;

                    showError(message(
                            "error.stock.load",
                            "Unable to load products or stock")
                            + ": " + errorMessage(failure));

                } finally {
                    if (generation == loadGeneration) {
                        refreshButton.setEnabled(true);
                        if (!recoveryBlocked && pendingAdjustment == null && pendingReceipt == null) {
                            adjustButton.setEnabled(true);
                            saveMinimumButton.setEnabled(!minimumSaving && productSelector.getItemCount() > 0);
                            adjustmentDirection.setEnabled(true);
                            adjustmentQuantityField.setEnabled(true);
                            adjustmentReasonField.setEnabled(true);
                        }
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
                                CatalogStyle.localDateTime(movement.createdAt(),messages.getLocale()),
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

    private void updateMinimumFieldForSelectedProduct() {
        Product selected = (Product) productSelector.getSelectedItem();
        if (selected == null) {
            minimumField.setText("");
            return;
        }
        Long minimum = minimumByProduct.get(selected.id());
        minimumField.setText(minimum == null ? "0" : formatQuantity(minimum));
    }

    private void saveMinimum() {
        if (recoveryBlocked || receiptPending || pendingReceipt != null || pendingAdjustment != null || minimumSaving) {
            showError(message("stock.minimum.blocked",
                    "Resolve the pending stock operation before changing the minimum threshold."));
            return;
        }
        Product selected = (Product) productSelector.getSelectedItem();
        if (selected == null) {
            showError(message("error.stock.selectProduct", "Please select a product."));
            return;
        }

        final long minimum;
        try {
            minimum = parseMinimumQuantity(minimumField.getText(), selected.unit());
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            minimumField.requestFocusInWindow();
            return;
        }

        UUID selectedProductId = selected.id();
        minimumSaving = true;
        saveMinimumButton.setEnabled(false);
        minimumField.setEnabled(false);
        refreshButton.setEnabled(false);
        statusFeedback.setTone(FeedbackStrip.Tone.INFO);
        statusLabel.setText(message("stock.minimum.saving", "Saving minimum threshold..."));

        new SwingWorker<StockLevel, Void>() {
            @Override
            protected StockLevel doInBackground() throws Exception {
                return stock.setMinimum(shopId, selectedProductId, minimum);
            }

            @Override
            protected void done() {
                try {
                    StockLevel updated = get();
                    minimumByProduct.put(updated.productId(), updated.minimumMilli());
                    statusFeedback.setTone(FeedbackStrip.Tone.SUCCESS);
                    statusLabel.setText(message("stock.minimum.success", "Minimum threshold saved."));
                    updateMinimumFieldForSelectedProduct();
                    loadProductsAndStock(true);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showError(message("stock.minimum.failed", "Unable to confirm the minimum threshold."));
                } catch (ExecutionException exception) {
                    showError(message("stock.minimum.failed", "Unable to save the minimum threshold")
                            + ": " + errorMessage(exception.getCause()));
                } finally {
                    minimumSaving = false;
                    refreshButton.setEnabled(true);
                    boolean canEditMinimum = !recoveryBlocked && pendingReceipt == null
                            && pendingAdjustment == null && !receiptPending && productSelector.getItemCount() > 0;
                    saveMinimumButton.setEnabled(canEditMinimum);
                    minimumField.setEnabled(canEditMinimum);
                    Runnable callback = onMinimumWorkerDone;
                    if (callback != null) callback.run();
                }
            }
        }.execute();
    }

    private long parseMinimumQuantity(String input, String unit) {
        String value = input.strip().replace(',', '.');
        if (!value.matches("\\d+(\\.\\d{1,3})?")) {
            throw new IllegalArgumentException(message("error.stock.minimumFormat",
                    "Enter a non-negative quantity with at most 3 decimal places."));
        }
        try {
            long milli = new BigDecimal(value).setScale(3, RoundingMode.UNNECESSARY)
                    .movePointRight(3).longValueExact();
            if (("piece".equals(unit) || "bag".equals(unit)) && milli % 1000 != 0) {
                throw new IllegalArgumentException(message("error.stock.wholePieces",
                        "Piece and bag quantities must be whole numbers."));
            }
            return milli;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(message("error.stock.quantityLarge",
                    "Quantity is too large or has invalid precision."));
        }
    }

    private void submitReceipt() {
        if (pendingAdjustment != null) {
            showError(message("stock.operation.adjustmentPending", "A stock adjustment is pending. Retry it before receiving another delivery."));
            return;
        }
        if (recoveryBlocked) {
            showError(message("stock.receipt.recoveryBlocked",
                    "Receipt recovery is blocked. Resolve the saved request before continuing."));
            return;
        }
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
                    UUID.randomUUID(), shopId, selected.id(), deviceId,
                    selected.unit(), quantity, reason);
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
        saveMinimumButton.setEnabled(false);
        minimumField.setEnabled(false);

        statusFeedback.setTone(FeedbackStrip.Tone.INFO);
        statusLabel.setText(message(
                "state.saving",
                "Saving receipt..."));

        new SwingWorker<ReceiptOutcome, Void>() {

            @Override
            protected ReceiptOutcome doInBackground() throws Exception {
                // Do not allow two different durable operations in this shop data folder.
                if (pendingAdjustmentStore.load().isPresent()) throw new IOException("A stock adjustment is already pending");
                // Persist the immutable operation before any database submission.
                pendingReceiptStore.save(request);
                StockResult result = stock.receive(
                        request.operationId(), request.shopId(), request.productId(),
                        request.deviceId(), request.quantityMilli(), request.reason());
                try {
                    pendingReceiptStore.clear(request.operationId());
                    return new ReceiptOutcome(result, true);
                } catch (IOException cleanupFailure) {
                    // The service confirmed it, but the durable request remains.
                    // A retry with the same UUID is safe and will replay the result.
                    return new ReceiptOutcome(result, false);
                }
            }

            @Override
            protected void done() {
                try {
                    ReceiptOutcome outcome = get();
                    receiptPending = false;
                    if (outcome.cleared()) {
                        pendingReceipt = null;
                        pendingProduct = null;
                        setReceiptControlsEnabled(true);
                        adjustmentDirection.setEnabled(true);
                        adjustmentQuantityField.setEnabled(true);
                        adjustmentReasonField.setEnabled(true);
                        adjustButton.setEnabled(true);
                        quantityField.setText("");
                        reasonField.setText("");
                        receiveButton.setText(message("stock.action.receive", "Receive Stock"));
                        statusFeedback.setTone(FeedbackStrip.Tone.SUCCESS);
                        statusLabel.setText(outcome.result().replayed()
                                ? message("stock.receipt.replayed", "Receipt confirmed (retried operation).")
                                : message("stock.receipt.success", "Stock received successfully."));
                    } else {
                        preservePendingReceipt();
                        statusLabel.setText(message("stock.receipt.cleanupPending",
                                "Receipt was confirmed, but recovery cleanup is pending. Retry the same receipt."));
                    }
                    loadProductsAndStock(true);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    preservePendingReceipt();
                    showError(message("error.stock.uncertain",
                            "Receipt outcome uncertain. Retry the same receipt."));
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof IllegalArgumentException || cause instanceof ArithmeticException) {
                        // Clear only after a known service rejection; retain if cleanup fails.
                        try {
                            pendingReceiptStore.clear(request.operationId());
                            pendingReceipt = null;
                            pendingProduct = null;
                            receiptPending = false;
                            setReceiptControlsEnabled(true);
                            adjustmentDirection.setEnabled(true);
                            adjustmentQuantityField.setEnabled(true);
                            adjustmentReasonField.setEnabled(true);
                            adjustButton.setEnabled(true);
                            minimumField.setEnabled(true);
                            saveMinimumButton.setEnabled(true);
                            receiveButton.setText(message("stock.action.receive", "Receive Stock"));
                        } catch (IOException cleanupFailure) {
                            preservePendingReceipt();
                        }
                    } else {
                        preservePendingReceipt();
                    }
                    showError(message("error.stock.receipt",
                            "Receipt failed or its outcome is uncertain") + ": " + errorMessage(cause));
                } finally {
                    notifyReceiptWorkerDone();
                }
            }
        }.execute();
    }

    private void submitAdjustment() {
        if (recoveryBlocked || receiptPending) return;
        if (pendingReceipt != null) {
            showError(message("stock.operation.receiptPending", "A receipt is pending. Retry it before making an adjustment."));
            return;
        }
        if (pendingAdjustment == null) {
            Product selected = (Product) productSelector.getSelectedItem();
            if (selected == null) { showError(message("error.stock.selectProduct", "Please select a product.")); return; }
            String reason = adjustmentReasonField.getText().strip();
            if (reason.isEmpty()) { showError(message("error.stock.reasonRequired", "An adjustment reason is required.")); adjustmentReasonField.requestFocusInWindow(); return; }
            long magnitude;
            try { magnitude = parseQuantity(adjustmentQuantityField.getText(), selected.unit()); }
            catch (IllegalArgumentException ex) { showError(ex.getMessage()); adjustmentQuantityField.requestFocusInWindow(); return; }
            boolean decrease = adjustmentDirection.getSelectedIndex() == 1;
            long delta = decrease ? Math.negateExact(magnitude) : magnitude;
            if (decrease && !GraphicsEnvironment.isHeadless()) {
                int answer = JOptionPane.showConfirmDialog(this,
                        message("stock.adjust.confirm", "This will reduce available stock. Continue?"),
                        message("stock.adjust.title", "Manual Stock Adjustment"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) return;
            }
            pendingAdjustmentProduct = selected;
            pendingAdjustment = new PendingStockAdjustment(UUID.randomUUID(), shopId, selected.id(), deviceId,
                    selected.unit(), delta, reason);
        }
        submitPendingAdjustment();
    }

    private void submitPendingAdjustment() {
        if (pendingAdjustment == null || receiptPending) return;
        PendingStockAdjustment request = pendingAdjustment;
        receiptPending = true;
        setReceiptControlsEnabled(false);
        adjustmentDirection.setEnabled(false); adjustmentQuantityField.setEnabled(false); adjustmentReasonField.setEnabled(false);
        receiveButton.setEnabled(false); adjustButton.setEnabled(false); refreshButton.setEnabled(false);
        saveMinimumButton.setEnabled(false);
        minimumField.setEnabled(false);
        statusFeedback.setTone(FeedbackStrip.Tone.INFO);
        statusLabel.setText(message("stock.adjust.saving", "Saving stock adjustment..."));
        new SwingWorker<StockResult, Void>() {
            @Override protected StockResult doInBackground() throws Exception {
                if (pendingReceiptStore.load().isPresent()) throw new IOException("A stock receipt is already pending");
                pendingAdjustmentStore.save(request);
                StockResult result = stock.adjust(request.operationId(), request.shopId(), request.productId(),
                        request.deviceId(), request.deltaMilli(), request.reason());
                pendingAdjustmentStore.clear(request.operationId());
                return result;
            }
            @Override protected void done() {
                try {
                    StockResult result = get();
                    pendingAdjustment = null; pendingAdjustmentProduct = null; receiptPending = false;
                    adjustmentQuantityField.setText(""); adjustmentReasonField.setText("");
                    adjustmentDirection.setSelectedIndex(0);
                    adjustButton.setText(message("stock.adjust.action", "Apply Adjustment"));
                    setReceiptControlsEnabled(true); adjustmentDirection.setEnabled(true);
                    adjustmentQuantityField.setEnabled(true); adjustmentReasonField.setEnabled(true);
                    statusFeedback.setTone(FeedbackStrip.Tone.SUCCESS);
                    statusLabel.setText(message("stock.adjust.success", "Stock adjustment saved successfully."));
                    loadProductsAndStock(true);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); preservePendingAdjustment();
                    showError(message("stock.adjust.uncertain", "Adjustment outcome uncertain. Retry the same adjustment."));
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof IllegalArgumentException || cause instanceof ArithmeticException) {
                        // The service/repository rejected the operation before commit; it is safe to return to editing.
                        try {
                            pendingAdjustmentStore.clear(request.operationId());
                            pendingAdjustment = null;
                            pendingAdjustmentProduct = null;
                            receiptPending = false;
                            setReceiptControlsEnabled(true);
                            adjustmentDirection.setEnabled(true);
                            adjustmentQuantityField.setEnabled(true);
                            adjustmentReasonField.setEnabled(true);
                            adjustButton.setText(message("stock.adjust.action", "Apply Adjustment"));
                            adjustButton.setEnabled(true);
                            minimumField.setEnabled(true);
                            saveMinimumButton.setEnabled(true);
                        } catch (IOException cleanupFailure) {
                            preservePendingAdjustment();
                        }
                    } else {
                        preservePendingAdjustment();
                    }
                    showError(message("error.stock.adjust", "Adjustment failed or its outcome is uncertain") + ": " + errorMessage(cause));
                } finally { notifyReceiptWorkerDone(); }
            }
        }.execute();
    }

    private void preservePendingAdjustment() {
        statusFeedback.setTone(FeedbackStrip.Tone.WARNING);
        receiptPending = false;
        setReceiptControlsEnabled(false);
        receiveButton.setEnabled(false);
        adjustmentDirection.setEnabled(false); adjustmentQuantityField.setEnabled(false); adjustmentReasonField.setEnabled(false);
        adjustButton.setText(message("stock.adjust.retry", "Retry Original Adjustment"));
        adjustButton.setEnabled(true); saveMinimumButton.setEnabled(false); minimumField.setEnabled(false); refreshButton.setEnabled(true);
        statusLabel.setText(message("stock.adjust.pending", "The original adjustment is pending. Retry it without changing its details."));
    }

    private void preservePendingReceipt() {
        statusFeedback.setTone(FeedbackStrip.Tone.WARNING);
        receiptPending = false;

        // Do not change the pending request or its operation UUID.
        setReceiptControlsEnabled(false);
        adjustmentDirection.setEnabled(false); adjustmentQuantityField.setEnabled(false); adjustmentReasonField.setEnabled(false);
        adjustButton.setEnabled(false);
        saveMinimumButton.setEnabled(false);
        minimumField.setEnabled(false);

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
        statusFeedback.setTone(FeedbackStrip.Tone.ERROR);
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

    void setOnMinimumWorkerDone(Runnable callback) {
        onMinimumWorkerDone = callback;
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

    JTextField minimumField() {
        return minimumField;
    }

    JButton saveMinimumButton() {
        return saveMinimumButton;
    }

    JLabel statusLabel() {
        return statusLabel;
    }

    boolean hasPendingReceipt() {
        return pendingReceipt != null;
    }

    private record InitialData(List<Product> products, List<StockLevel> levels,
                               PendingReceipt pendingReceipt, PendingStockAdjustment pendingAdjustment) { }

    private record ReceiptOutcome(StockResult result, boolean cleared) { }

    /** Compatibility store for callers using the legacy constructor; production injects the file store. */
    private static final class InMemoryPendingReceiptStore implements PendingReceiptStore {
        private PendingReceipt value;
        @Override public synchronized Optional<PendingReceipt> load() { return Optional.ofNullable(value); }
        @Override public synchronized void save(PendingReceipt receipt) throws IOException {
            if (value != null && !value.equals(receipt)) throw new FilePendingReceiptStore.ConflictException();
            value = receipt;
        }
        @Override public synchronized void clear(UUID expectedOperationId) throws IOException {
            if (value != null && !value.operationId().equals(expectedOperationId))
                throw new FilePendingReceiptStore.ConflictException();
            value = null;
        }
        @Override public java.nio.file.Path file() { return java.nio.file.Path.of("memory-only"); }
    }
    private static final class InMemoryPendingStockAdjustmentStore implements PendingStockAdjustmentStore {
        private PendingStockAdjustment value;
        @Override public synchronized Optional<PendingStockAdjustment> load() { return Optional.ofNullable(value); }
        @Override public synchronized void save(PendingStockAdjustment adjustment) throws IOException {
            if (value != null && !value.equals(adjustment)) throw new FilePendingStockAdjustmentStore.ConflictException();
            value = adjustment;
        }
        @Override public synchronized void clear(UUID expectedOperationId) throws IOException {
            if (value != null && !value.operationId().equals(expectedOperationId)) throw new FilePendingStockAdjustmentStore.ConflictException();
            value = null;
        }
        @Override public java.nio.file.Path file() { return java.nio.file.Path.of("memory-only-adjustment"); }
    }

}
