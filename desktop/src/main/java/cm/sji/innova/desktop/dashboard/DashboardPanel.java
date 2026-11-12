package cm.sji.innova.desktop.dashboard;

import cm.sji.innova.desktop.catalog.CatalogStyle;
import cm.sji.innova.desktop.catalog.FeedbackStrip;
import cm.sji.innova.desktop.shell.AppContext;
import cm.sji.innova.desktop.shell.Messages;
import java.awt.*;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Live local statistics. Construction/navigation is I/O-free; only the worker queries services. */
public final class DashboardPanel extends JPanel {
    @FunctionalInterface interface Source { DashboardSummary load(DashboardSummary.Period period) throws Exception; }
    private final Messages messages;
    private final Source source;
    private final Consumer<String> navigate;
    private final NumberFormat numbers;
    private final JComboBox<String> period;
    private final JButton refresh;
    private final JPanel content = CatalogStyle.panel(new BorderLayout(0, 22));
    private final JLabel feedback = CatalogStyle.label(" ", 14, false);
    private final FeedbackStrip strip = CatalogStyle.feedback(feedback, FeedbackStrip.Tone.INFO);
    private long generation;
    private CompletionStage<DashboardSummary> lastLoad = CompletableFuture.completedFuture(null);

    public DashboardPanel(AppContext context, Messages messages, Consumer<String> navigate) {
        this(messages, navigate, selected -> DashboardSummary.calculate(
                context.sales().listSaleStates(context.shopId()), context.stock().list(context.shopId()),
                selected, Clock.systemUTC()));
    }

    DashboardPanel(Messages messages, Consumer<String> navigate, Source source) {
        super(new BorderLayout(0, 18));
        this.messages = messages; this.source = source; this.navigate = navigate;
        numbers = NumberFormat.getIntegerInstance(messages.bundle().getLocale());
        setBackground(CatalogStyle.PAPER); setBorder(new EmptyBorder(26, 30, 22, 30));
        JPanel heading = CatalogStyle.panel(new BorderLayout(20, 8));
        JPanel titles = CatalogStyle.panel(new GridLayout(0, 1, 0, 6));
        titles.add(CatalogStyle.label(t("title"), 30, true));
        JLabel subtitle = CatalogStyle.label(t("subtitle"), 14, false); subtitle.setForeground(CatalogStyle.MUTED);
        titles.add(subtitle); heading.add(titles, BorderLayout.CENTER);
        JButton sale = CatalogStyle.arrowButton(t("sale"), true, false); sale.setName("dashboardSale");
        sale.addActionListener(e -> navigate.accept("sales"));
        JPanel saleAction = CatalogStyle.panel(new BorderLayout());
        saleAction.add(sale, BorderLayout.NORTH); heading.add(saleAction, BorderLayout.EAST);
        JPanel tools = CatalogStyle.panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        period = new JComboBox<>(new String[]{t("today"), t("sevenDays"), t("month")});
        period.setName("dashboardPeriod"); period.getAccessibleContext().setAccessibleName(t("period"));
        period.addActionListener(e -> reload());
        refresh = CatalogStyle.button(messages.get("catalog.refresh"), false); refresh.setName("dashboardRefresh");
        refresh.addActionListener(e -> reload()); tools.add(period); tools.add(refresh);
        JPanel north = CatalogStyle.panel(new BorderLayout(0, 20)); north.add(heading, BorderLayout.NORTH);
        north.add(tools, BorderLayout.SOUTH); add(north, BorderLayout.NORTH);
        add(CatalogStyle.scrollContent(content, 650), BorderLayout.CENTER);
        feedback.setName("dashboardFeedback"); add(strip, BorderLayout.SOUTH);
        placeholder(t("loading"));
    }

    public CompletionStage<DashboardSummary> loadCompletion() { return lastLoad; }

    public CompletionStage<DashboardSummary> reload() {
        long request = ++generation;
        var selected = DashboardSummary.Period.values()[period.getSelectedIndex()];
        CompletableFuture<DashboardSummary> completion = new CompletableFuture<>(); lastLoad = completion;
        refresh.setEnabled(false); strip.setTone(FeedbackStrip.Tone.INFO); feedback.setText(t("loading"));
        new SwingWorker<DashboardSummary, Void>() {
            @Override protected DashboardSummary doInBackground() throws Exception { return source.load(selected); }
            @Override protected void done() {
                try {
                    DashboardSummary data = get();
                    if (request == generation) {
                        display(data); strip.setTone(FeedbackStrip.Tone.INFO);
                        feedback.setText(t("policy") + " · " + t("updated") + " "
                                + DateTimeFormatter.ofPattern("HH:mm").withZone(DashboardSummary.ZONE)
                                        .format(java.time.Instant.now()));
                    }
                    completion.complete(data);
                } catch (Exception failure) {
                    Throwable cause = failure instanceof ExecutionException && failure.getCause() != null
                            ? failure.getCause() : failure;
                    if (request == generation) {
                        placeholder(t("unavailable")); strip.setTone(FeedbackStrip.Tone.ERROR);
                        feedback.setText(t("error"));
                    }
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    completion.completeExceptionally(cause);
                } finally { if (request == generation) refresh.setEnabled(true); }
            }
        }.execute();
        return completion;
    }

    private void placeholder(String text) {
        content.removeAll(); JPanel card = CatalogStyle.surface(new GridBagLayout());
        card.add(CatalogStyle.label(text, 16, false)); content.add(card, BorderLayout.NORTH);
        content.revalidate(); content.repaint();
    }

    private void display(DashboardSummary data) {
        content.removeAll();
        JPanel metrics = CatalogStyle.panel(new GridLayout(1, 3, 16, 0));
        metrics.add(metric(t("revenue"), numbers.format(data.revenue()) + " FCFA",
                numbers.format(data.paidReceipts()) + " " + t("paid"), true, "dashboardRevenue"));
        metrics.add(metric(t("average"), data.average().map(v -> numbers.format(v) + " FCFA").orElse("—"),
                t("averageNote"), false, "dashboardAverage"));
        metrics.add(metric(t("low"), numbers.format(data.lowStock().size()), t("lowNote"), false, "dashboardLow"));
        JPanel charts = CatalogStyle.panel(new GridLayout(1, 2, 20, 0));
        JPanel chart = CatalogStyle.surface(new BorderLayout(0, 12));
        chart.add(CatalogStyle.label(t("trend"), 18, true), BorderLayout.NORTH);
        chart.add(new RevenueChart(data), BorderLayout.CENTER);
        DateTimeFormatter dates = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        JLabel range = CatalogStyle.label(data.from().format(dates) + " → " + data.through().format(dates), 12, false);
        range.setForeground(CatalogStyle.MUTED); chart.add(range, BorderLayout.SOUTH); charts.add(chart);
        JPanel low = CatalogStyle.surface(new BorderLayout(0, 12));
        JPanel rows = CatalogStyle.panel(new GridLayout(0, 1, 0, 10));
        data.lowStock().stream().limit(4).forEach(stock -> {
            JPanel row = CatalogStyle.panel(new BorderLayout(10, 5));
            JLabel name = CatalogStyle.label(stock.productName(), 14, true);
            name.putClientProperty("html.disable", true); name.setToolTipText(stock.productName());
            row.add(name, BorderLayout.CENTER);
            String unitKey = "unit." + stock.unit();
            String unit = messages.bundle().containsKey(unitKey) ? messages.get(unitKey) : stock.unit();
            JLabel quantity = CatalogStyle.label(numbers.format(BigDecimal.valueOf(stock.quantityMilli(), 3)) + " " + unit, 14, false);
            // Quantity decimals are formatted separately: integer money formatting must not round stock.
            quantity.setText(NumberFormat.getNumberInstance(messages.bundle().getLocale())
                    .format(BigDecimal.valueOf(stock.quantityMilli(), 3)) + " " + unit);
            quantity.setForeground(new Color(0x906526)); row.add(quantity, BorderLayout.EAST); rows.add(row);
        });
        if (data.lowStock().isEmpty()) rows.add(CatalogStyle.label(t("stockGood"), 14, false));
        JPanel lowContent = CatalogStyle.panel(new BorderLayout(0, 18));
        lowContent.add(CatalogStyle.label(t("replenish"), 18, true), BorderLayout.NORTH);
        lowContent.add(rows, BorderLayout.CENTER); low.add(lowContent, BorderLayout.NORTH);
        JButton stock = CatalogStyle.arrowButton(t("stock"), false, false); stock.addActionListener(e -> navigate.accept("stock"));
        low.add(stock, BorderLayout.SOUTH); charts.add(low);
        charts.setPreferredSize(new Dimension(600, 300));
        JPanel lower = CatalogStyle.surface(new BorderLayout(0, 14));
        lower.add(CatalogStyle.label(t("top"), 18, true), BorderLayout.NORTH);
        JPanel best = CatalogStyle.panel(new GridLayout(0, 1, 0, 12));
        for (var product : data.topProducts()) {
            JPanel row = CatalogStyle.panel(new BorderLayout(14, 0));
            JLabel name = CatalogStyle.label(product.name() + " · " + product.reference(), 14, false);
            name.putClientProperty("html.disable", true); name.setToolTipText(name.getText()); row.add(name, BorderLayout.CENTER);
            row.add(CatalogStyle.label(numbers.format(product.revenue()) + " FCFA", 14, true), BorderLayout.EAST); best.add(row);
        }
        if (data.topProducts().isEmpty()) best.add(CatalogStyle.label(t("noSales"), 14, false));
        lower.add(best, BorderLayout.CENTER);
        JLabel voids = CatalogStyle.label(t("voids") + " : " + numbers.format(data.cancellations()), 13, false);
        voids.setForeground(CatalogStyle.MUTED); lower.add(voids, BorderLayout.SOUTH);
        JPanel body = CatalogStyle.panel(new BorderLayout(0, 20)); body.add(charts, BorderLayout.NORTH); body.add(lower, BorderLayout.CENTER);
        content.add(metrics, BorderLayout.NORTH); content.add(body, BorderLayout.CENTER);
        content.revalidate(); content.repaint();
    }

    private JPanel metric(String title, String value, String note, boolean accent, String name) {
        JPanel card = CatalogStyle.surface(new BorderLayout(0, 13));
        JLabel caption = CatalogStyle.label(title, 14, false); caption.setForeground(CatalogStyle.MUTED);
        JLabel amount = CatalogStyle.amountLabel(32); amount.setHorizontalAlignment(SwingConstants.LEFT); amount.setText(value); amount.setName(name);
        JLabel detail = CatalogStyle.label(note, 12, false); detail.setForeground(CatalogStyle.MUTED);
        card.add(caption, BorderLayout.NORTH); card.add(amount, BorderLayout.CENTER); card.add(detail, BorderLayout.SOUTH);
        card.setPreferredSize(new Dimension(200, 142));
        if (accent) card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(3, 0, 0, 0, new Color(0xBDD0A9)), card.getBorder()));
        return card;
    }

    private String t(String key) { return messages.get("dashboard." + key); }

    /** Doubles are only pixel proportions; all displayed values and financial sums remain exact. */
    private final class RevenueChart extends JPanel {
        private final DashboardSummary data;
        RevenueChart(DashboardSummary data) {
            this.data = data; setOpaque(false); setPreferredSize(new Dimension(280, 200));
            setName("dashboardTrend");
            getAccessibleContext().setAccessibleName(t("trend"));
            getAccessibleContext().setAccessibleDescription(data.days().stream()
                    .map(day -> day.date() + ": " + numbers.format(day.revenue()) + " FCFA")
                    .collect(java.util.stream.Collectors.joining("; ")));
            setToolTipText(t("policy"));
        }
        @Override public String getToolTipText(java.awt.event.MouseEvent event) {
            if (getWidth() <= 20 || event.getX() < 10) return null;
            int cell = Math.max(1, (getWidth() - 20) / data.days().size());
            int index = Math.min(data.days().size() - 1, (event.getX() - 10) / cell);
            var day = data.days().get(index);
            return day.date().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " · "
                    + numbers.format(day.revenue()) + " FCFA";
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            int left = 10, top = 28, bottom = getHeight() - 35, usable = Math.max(1, getWidth() - 20);
            long max = data.days().stream().mapToLong(DashboardSummary.Day::revenue).max().orElse(0);
            if (max == 0) {
                g.setColor(CatalogStyle.MUTED); g.drawString(t(data.paidReceipts() == 0 ? "noSales" : "zeroRevenue"), 10, Math.max(35, getHeight() / 2)); g.dispose(); return;
            }
            int count = data.days().size(), cell = Math.max(1, usable / count), bar = Math.min(36, Math.max(3, cell - 8));
            for (int i = 0; i < count; i++) {
                var day = data.days().get(i);
                int height = (int) Math.round((double) day.revenue() / max * Math.max(1, bottom - top));
                int x = left + i * cell + (cell - bar) / 2;
                g.setColor(i == count - 1 ? CatalogStyle.GREEN : new Color(0xD9E5CC));
                g.fillRoundRect(x, bottom - height, bar, height, 7, 7);
                if (count <= 7 || i == 0 || i == count - 1 || i % 7 == 0) {
                    String label = day.date().format(DateTimeFormatter.ofPattern("dd/MM"));
                    g.setColor(CatalogStyle.MUTED); g.drawString(label, left + i * cell, bottom + 21);
                }
            }
            g.setColor(CatalogStyle.MUTED); g.drawString(numbers.format(max) + " FCFA", left, 14); g.dispose();
        }
    }
}
