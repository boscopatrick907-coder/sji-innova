package cm.sji.innova.desktop.products;

import cm.sji.innova.core.product.Product;
import cm.sji.innova.core.product.ProductService;

import javax.swing.*;
import java.awt.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ProductPanel extends JPanel {

    private final ProductService products;
    private final UUID shopId;
    private final ResourceBundle messages;

    private final JTextField nameField = new JTextField(20);
    private final JTextField modelField = new JTextField(20);
    private final JTextField buyingPriceField = new JTextField(20);
    private final JTextField sellingPriceField = new JTextField(20);
    private final JTextField categoryField = new JTextField(20);

    private final JComboBox<UnitOption> unitBox;

    private final JPanel specificationsPanel = new JPanel();
    private final List<SpecRow> specificationRows = new ArrayList<>();

    private final DefaultListModel<String> productListModel =
            new DefaultListModel<>();

    private final JList<String> productList =
            new JList<>(productListModel);

    private final JButton saveButton = new JButton();
    private final JButton refreshButton = new JButton();
    private final JButton addSpecificationButton = new JButton();
    private final JLabel statusLabel = new JLabel(" ");

    private boolean savePending = false;
    // Accessed on the EDT. Old queries may finish, but only the latest owns UI state.
    private long loadGeneration;
    private final CompletableFuture<Void> initialLoad = new CompletableFuture<>();
    private volatile Runnable onSaveWorkerDone;
    private volatile Runnable onLoadWorkerDone;
    private java.util.function.Consumer<String> errorHandler = this::displayErrorDialog;

    public ProductPanel(ProductService products, UUID shopId,
                        ResourceBundle messages) {
        this(products, shopId, messages, null, null);
    }

    public ProductPanel(ProductService products, UUID shopId) {
        this(products, shopId, defaultBundle(), null, null);
    }

    ProductPanel(ProductService products, UUID shopId,
                 ResourceBundle messages,
                 Runnable onSaveWorkerDone,
                 Runnable onLoadWorkerDone) {

        this.products = Objects.requireNonNull(products, "products");
        this.shopId = Objects.requireNonNull(shopId, "shopId");
        this.messages = messages;
        this.onSaveWorkerDone = onSaveWorkerDone;
        this.onLoadWorkerDone = onLoadWorkerDone;

        this.unitBox = new JComboBox<>(new UnitOption[]{
                new UnitOption("piece", message("unit.piece", "Piece")),
                new UnitOption("kg", message("unit.kg", "Kilogram")),
                new UnitOption("litre", message("unit.litre", "Litre"))
        });

        this.addSpecificationButton.setText(
                "+ " + message("action.addSpecification", "Add characteristic"));
        this.addSpecificationButton.addActionListener(e -> addSpecRow());

        this.saveButton.setText(message("action.save", "Save"));
        this.saveButton.addActionListener(e -> saveProduct());

        this.refreshButton.setText(message("action.refresh", "Refresh"));
        this.refreshButton.addActionListener(e -> loadProducts());

        setLayout(new BorderLayout(15, 15));
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel heading = new JLabel(message("products.title", "Products").toUpperCase());
        heading.setFont(new Font("Arial", Font.BOLD, 22));

        add(heading, BorderLayout.NORTH);

        JPanel content = new JPanel(new GridLayout(1, 2, 20, 0));

        content.add(createForm());
        content.add(createProductList());

        add(content, BorderLayout.CENTER);

        addSpecRow();
        loadProducts();
    }

    private static ResourceBundle defaultBundle() {
        try {
            return ResourceBundle.getBundle("cm.sji.innova.desktop.i18n.messages");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String message(String key, String fallback) {
        if (messages == null) {
            return fallback;
        }

        try {
            return messages.getString(key);
        } catch (java.util.MissingResourceException exception) {
            return fallback;
        }
    }

    private JPanel createForm() {

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder(
                message("products.title", "Add a new product")));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        int row = 0;

        addField(form, c, row++, message("product.name", "Name") + ":", nameField);
        addField(form, c, row++, message("product.modelReference", "Model / reference") + ":", modelField);
        addField(form, c, row++, message("product.buyingPrice", "Buying price (FCFA)") + ":", buyingPriceField);
        addField(form, c, row++, message("product.sellingPrice", "Selling price (FCFA)") + ":", sellingPriceField);
        addField(form, c, row++, message("product.category", "Category") + ":", categoryField);
        addField(form, c, row++, message("product.unit", "Unit") + ":", unitBox);

        JLabel specificationsTitle = new JLabel(
                message("product.specifications", "Specifications") + ":");
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        form.add(specificationsTitle, c);

        specificationsPanel.setLayout(
                new BoxLayout(specificationsPanel, BoxLayout.Y_AXIS));

        c.gridy = row++;
        form.add(specificationsPanel, c);

        c.gridy = row++;
        form.add(addSpecificationButton, c);

        c.gridy = row++;
        form.add(saveButton, c);

        c.gridy = row;
        form.add(statusLabel, c);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(new JScrollPane(form), BorderLayout.CENTER);
        return wrapper;
    }

    private void addField(JPanel panel, GridBagConstraints c,
                          int row, String label, JComponent field) {

        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;

        panel.add(new JLabel(label), c);

        c.gridx = 1;
        c.weightx = 1;

        panel.add(field, c);
    }

    private JPanel createProductList() {

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(
                BorderFactory.createTitledBorder(
                        message("nav.products", "Saved products")));

        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        topBar.add(refreshButton);
        panel.add(topBar, BorderLayout.NORTH);

        productList.setSelectionMode(
                ListSelectionModel.SINGLE_SELECTION);

        panel.add(new JScrollPane(productList), BorderLayout.CENTER);

        return panel;
    }

    void addSpecRow() {

        SpecRow row = new SpecRow();
        row.setEnabled(saveButton.isEnabled());
        specificationRows.add(row);

        specificationsPanel.add(row.panel);

        specificationsPanel.revalidate();
        specificationsPanel.repaint();
    }

    void setFormEnabled(boolean enabled) {
        nameField.setEnabled(enabled);
        modelField.setEnabled(enabled);
        buyingPriceField.setEnabled(enabled);
        sellingPriceField.setEnabled(enabled);
        categoryField.setEnabled(enabled);
        unitBox.setEnabled(enabled);
        addSpecificationButton.setEnabled(enabled);
        saveButton.setEnabled(enabled);
        for (SpecRow row : specificationRows) {
            row.setEnabled(enabled);
        }
    }

    private Map<String, String> getSpecifications() {

        Map<String, String> specifications = new LinkedHashMap<>();

        for (SpecRow row : specificationRows) {

            String key = row.name.getText().strip();
            String value = row.value.getText().strip();

            if (key.isEmpty() && value.isEmpty()) {
                continue;
            }

            if (key.isEmpty() || value.isEmpty()) {
                throw new IllegalArgumentException(
                        message("error.invalidSpecifications",
                                "Enter unique, nonblank names and values."));
            }

            if (specifications.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException(
                        message("error.invalidSpecifications",
                                "Duplicate characteristic: " + key));
            }
        }

        return specifications;
    }

    void saveProduct() {

        if (savePending) {
            return;
        }

        String name = nameField.getText().strip();
        String model = modelField.getText().strip();
        String buyingText = buyingPriceField.getText().strip();
        String sellingText = sellingPriceField.getText().strip();
        String category = categoryField.getText().strip();
        UnitOption selectedUnit = (UnitOption) unitBox.getSelectedItem();
        String unit = selectedUnit != null ? selectedUnit.code() : "piece";

        if (name.isEmpty()) {
            showError(message("error.requiredName", "Enter a product name"));
            return;
        }

        final long sellingPrice;
        final Long buyingPrice;
        final Map<String, String> specifications;

        try {

            if (sellingText.isEmpty()) {
                throw new IllegalArgumentException(
                        message("error.invalidPrice",
                                "Enter a nonnegative whole FCFA amount"));
            }

            sellingPrice = Long.parseLong(sellingText);

            if (sellingPrice < 0) {
                throw new IllegalArgumentException(
                        message("error.invalidPrice",
                                "Enter a nonnegative whole FCFA amount"));
            }

            if (buyingText.isEmpty()) {
                buyingPrice = null;
            } else {
                buyingPrice = Long.parseLong(buyingText);

                if (buyingPrice < 0) {
                    throw new IllegalArgumentException(
                            message("error.invalidPrice",
                                    "Enter a nonnegative whole FCFA amount"));
                }
            }

            specifications = getSpecifications();

        } catch (NumberFormatException exception) {
            showError(message("error.invalidPrice",
                    "Enter a nonnegative whole FCFA amount"));
            return;
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            return;
        }

        savePending = true;
        setFormEnabled(false);
        statusLabel.setText(message("state.saving", "Saving…"));

        SwingWorker<Product, Void> worker =
                new SwingWorker<>() {

            @Override
            protected Product doInBackground() throws SQLException {

                return products.create(
                        shopId,
                        name,
                        sellingPrice,
                        model.isEmpty() ? null : model,
                        buyingPrice,
                        category.isEmpty() ? null : category,
                        unit,
                        specifications
                );
            }

            @Override
            protected void done() {

                try {
                    get();

                    statusLabel.setText(message("state.saved", "Product saved"));

                    clearForm();
                    loadProducts();

                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showError(message("error.saveProduct",
                            "Product could not be saved"));

                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    String details = cause != null && cause.getMessage() != null
                            ? cause.getMessage()
                            : "";
                    showError(message("error.saveProduct",
                            "Product could not be saved") + ": " + details);
                } finally {
                    savePending = false;
                    setFormEnabled(true);
                    notifySaveWorkerDone();
                }
            }
        };

        worker.execute();
    }

    public void loadProducts() {
        final long generation = ++loadGeneration;

        productListModel.clear();
        productListModel.addElement(message("state.loading", "Loading…"));
        refreshButton.setEnabled(false);

        SwingWorker<List<Product>, Void> worker =
                new SwingWorker<>() {

            @Override
            protected List<Product> doInBackground()
                    throws SQLException {

                return products.list(shopId);
            }

            @Override
            protected void done() {
                Throwable loadFailure = null;
                try {
                    if (generation != loadGeneration) {
                        return; // Superseded success/failure must not overwrite a newer request.
                    }
                    List<Product> savedProducts = get();

                    productListModel.clear();

                    if (savedProducts.isEmpty()) {
                        productListModel.addElement(
                                message("state.emptyProducts",
                                        "No products yet"));
                        return;
                    }

                    for (Product product : savedProducts) {
                        productListModel.addElement(formatProductDisplay(product));
                    }

                } catch (InterruptedException exception) {
                    loadFailure = exception;
                    Thread.currentThread().interrupt();
                    productListModel.clear();
                    productListModel.addElement(
                            message("error.loadProducts",
                                    "Products could not be loaded"));
                    showError(message("error.loadProducts",
                            "Products could not be loaded"));

                } catch (ExecutionException exception) {
                    loadFailure = exception.getCause() == null ? exception : exception.getCause();
                    productListModel.clear();
                    productListModel.addElement(
                            message("error.loadProducts",
                                    "Products could not be loaded"));
                    Throwable cause = exception.getCause();
                    String details = cause != null && cause.getMessage() != null
                            ? cause.getMessage()
                            : "";
                    showError(message("error.loadProducts",
                            "Products could not be loaded") + ": " + details);
                } finally {
                    if (generation == loadGeneration) {
                        refreshButton.setEnabled(true);
                    }
                    // Completion hooks still fire for stale workers so tests can await cleanup.
                    notifyLoadWorkerDone();
                    if (generation == 1) {
                        if (loadFailure == null) initialLoad.complete(null);
                        else initialLoad.completeExceptionally(loadFailure);
                    }
                }
            }
        };

        worker.execute();
    }

    /** Await off the EDT before startup checks dispose their temporary database. */
    public CompletionStage<Void> initialLoadCompletion() {
        return initialLoad.minimalCompletionStage();
    }

    private String formatProductDisplay(Product product) {
        String buyingLabel = message("product.buyingPrice", "Buying price (FCFA)");
        String sellingLabel = message("product.sellingPrice", "Selling price (FCFA)");
        String categoryLabel = message("product.category", "Category");
        String unitLabel = message("product.unit", "Unit");
        String specsLabel = message("product.specifications", "Specifications");

        String buying = product.costPrice() == null
                ? message("price.unknown", "Unknown")
                : product.costPrice() + " FCFA";

        String selling = product.unitPrice() + " FCFA";
        String unitDisplay = formatUnitDisplay(product.unit());

        StringBuilder sb = new StringBuilder("<html><body style='padding: 2px;'>");
        sb.append("<b>").append(escapeHtml(product.name())).append("</b>");

        if (product.modelReference() != null && !product.modelReference().isBlank()) {
            sb.append(" &nbsp;<span style='color: gray;'>[")
              .append(escapeHtml(product.modelReference()))
              .append("]</span>");
        }

        sb.append("<br>");
        sb.append(sellingLabel).append(": <b>").append(selling).append("</b>");
        sb.append(" &nbsp;|&nbsp; ");
        sb.append(buyingLabel).append(": <b>").append(buying).append("</b>");
        sb.append(" &nbsp;|&nbsp; ");
        sb.append(unitLabel).append(": <b>").append(escapeHtml(unitDisplay)).append("</b>");

        if (product.category() != null && !product.category().isBlank()) {
            sb.append(" &nbsp;|&nbsp; ");
            sb.append(categoryLabel).append(": <b>").append(escapeHtml(product.category())).append("</b>");
        }

        if (product.specifications() != null && !product.specifications().isEmpty()) {
            sb.append("<br>");
            sb.append(specsLabel).append(": ");
            List<String> specsList = new ArrayList<>();
            product.specifications().forEach((k, v) ->
                    specsList.add(escapeHtml(k) + "=" + escapeHtml(v)));
            sb.append(String.join(", ", specsList));
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private String formatUnitDisplay(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case "piece" -> message("unit.piece", "Piece");
            case "kg" -> message("unit.kg", "Kilogram");
            case "litre" -> message("unit.litre", "Litre");
            default -> code;
        };
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }

    void clearForm() {

        nameField.setText("");
        modelField.setText("");
        buyingPriceField.setText("");
        sellingPriceField.setText("");
        categoryField.setText("");
        unitBox.setSelectedIndex(0);

        specificationsPanel.removeAll();
        specificationRows.clear();
        addSpecRow();
    }

    void setErrorHandler(java.util.function.Consumer<String> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    private void showError(String message) {
        if (errorHandler != null) {
            errorHandler.accept(message);
        }
    }

    private void displayErrorDialog(String message) {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(
                        this,
                        message,
                        message("products.title", "Products"),
                        JOptionPane.ERROR_MESSAGE
                );
            }
        } catch (HeadlessException ignored) {
            // Handled cleanly in headless environments
        }
    }

    void setOnSaveWorkerDone(Runnable onSaveWorkerDone) {
        this.onSaveWorkerDone = onSaveWorkerDone;
    }

    void setOnLoadWorkerDone(Runnable onLoadWorkerDone) {
        this.onLoadWorkerDone = onLoadWorkerDone;
    }

    private void notifySaveWorkerDone() {
        Runnable callback = this.onSaveWorkerDone;
        if (callback != null) {
            callback.run();
        }
    }

    private void notifyLoadWorkerDone() {
        Runnable callback = this.onLoadWorkerDone;
        if (callback != null) {
            callback.run();
        }
    }

    // Package-private accessors for testing:
    JTextField nameField() { return nameField; }
    JTextField modelField() { return modelField; }
    JTextField buyingPriceField() { return buyingPriceField; }
    JTextField sellingPriceField() { return sellingPriceField; }
    JTextField categoryField() { return categoryField; }
    JComboBox<UnitOption> unitBox() { return unitBox; }
    JButton saveButton() { return saveButton; }
    JButton refreshButton() { return refreshButton; }
    JButton addSpecificationButton() { return addSpecificationButton; }
    JLabel statusLabel() { return statusLabel; }
    DefaultListModel<String> productListModel() { return productListModel; }
    JList<String> productList() { return productList; }
    List<SpecRow> specificationRows() { return specificationRows; }
    boolean isSavePending() { return savePending; }

    public static final class UnitOption {
        private final String code;
        private final String displayName;

        public UnitOption(String code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }

        public String code() {
            return code;
        }

        public String displayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    class SpecRow {

        final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));

        final JTextField name = new JTextField(7);
        final JTextField value = new JTextField(7);
        final JButton removeButton = new JButton();

        SpecRow() {

            panel.add(new JLabel(message("specification.name", "Characteristic") + ":"));
            panel.add(name);

            panel.add(new JLabel(message("specification.value", "Value") + ":"));
            panel.add(value);

            removeButton.setText(message("action.removeSpecification", "Remove"));

            removeButton.addActionListener(e -> {

                specificationRows.remove(this);
                specificationsPanel.remove(panel);

                specificationsPanel.revalidate();
                specificationsPanel.repaint();
            });

            panel.add(removeButton);
        }

        void setEnabled(boolean enabled) {
            name.setEnabled(enabled);
            value.setEnabled(enabled);
            removeButton.setEnabled(enabled);
        }

        JTextField nameField() { return name; }
        JTextField valueField() { return value; }
        JButton removeButton() { return removeButton; }
    }
}
