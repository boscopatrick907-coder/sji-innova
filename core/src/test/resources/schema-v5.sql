-- Frozen schema from main before families; statements separated by marker.
CREATE TABLE products (
    id TEXT PRIMARY KEY NOT NULL,
    shop_id TEXT NOT NULL,
    name TEXT NOT NULL CHECK(length(trim(name)) > 0),
    unit_price INTEGER NOT NULL CHECK(typeof(unit_price) = 'integer' AND unit_price >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL CHECK(version >= 1)
, model_reference TEXT, cost_price INTEGER CHECK(cost_price IS NULL OR (typeof(cost_price) = 'integer' AND cost_price >= 0)), category TEXT, unit TEXT NOT NULL DEFAULT 'piece' CHECK(unit IN ('piece', 'kg', 'litre')), current_stock_milli INTEGER NOT NULL DEFAULT 0 CHECK(typeof(current_stock_milli) = 'integer'), min_stock_milli INTEGER NOT NULL DEFAULT 0 CHECK(typeof(min_stock_milli) = 'integer' AND min_stock_milli >= 0))
-- statement
CREATE INDEX products_shop_id ON products(shop_id)
-- statement
CREATE TABLE product_specifications (
    product_id TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK(length(trim(name)) > 0),
    value TEXT NOT NULL CHECK(length(trim(value)) > 0),
    PRIMARY KEY (product_id, name)
)
-- statement
CREATE TABLE "stock_movements" (
    id TEXT PRIMARY KEY NOT NULL,
    shop_id TEXT NOT NULL,
    product_id TEXT NOT NULL REFERENCES products(id),
    device_id TEXT NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('delivery', 'adjustment', 'sale', 'void')),
    quantity_milli INTEGER NOT NULL CHECK(typeof(quantity_milli) = 'integer' AND quantity_milli != 0),
    reason TEXT NOT NULL CHECK(length(trim(reason)) > 0),
    created_at TEXT NOT NULL,
    CHECK((type != 'delivery' AND type != 'void') OR quantity_milli > 0),
    CHECK(type != 'sale' OR quantity_milli < 0)
)
-- statement
CREATE INDEX stock_movements_product ON stock_movements(shop_id, product_id, created_at, id)
-- statement
CREATE TRIGGER stock_movements_no_update BEFORE UPDATE ON stock_movements BEGIN SELECT RAISE(ABORT, 'Stock movements are append-only'); END
-- statement
CREATE TRIGGER stock_movements_no_delete BEFORE DELETE ON stock_movements BEGIN SELECT RAISE(ABORT, 'Stock movements are append-only'); END
-- statement
CREATE TABLE sales (
    id TEXT PRIMARY KEY NOT NULL,
    shop_id TEXT NOT NULL,
    receipt_number TEXT NOT NULL CHECK(length(trim(receipt_number)) > 0),
    device_id TEXT NOT NULL,
    payment_method TEXT NOT NULL CHECK(payment_method IN ('cash', 'momo', 'credit')),
    total_amount INTEGER NOT NULL CHECK(total_amount >= 0),
    created_at TEXT NOT NULL
)
-- statement
CREATE INDEX sales_shop_created ON sales(shop_id, created_at, id)
-- statement
CREATE TABLE sale_items (
    id TEXT PRIMARY KEY NOT NULL,
    sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,
    product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    product_name TEXT NOT NULL,
    unit TEXT NOT NULL,
    quantity_milli INTEGER NOT NULL CHECK(quantity_milli > 0),
    unit_price INTEGER NOT NULL CHECK(unit_price >= 0),
    subtotal INTEGER NOT NULL CHECK(subtotal >= 0)
)
-- statement
CREATE INDEX sale_items_sale ON sale_items(sale_id)
-- statement
CREATE UNIQUE INDEX products_shop_id_id ON products(shop_id, id)
-- statement
CREATE UNIQUE INDEX sales_shop_id_id ON sales(shop_id, id)
-- statement
CREATE UNIQUE INDEX sale_items_sale_product_id ON sale_items(sale_id, id, product_id)
-- statement
CREATE UNIQUE INDEX stock_movements_shop_product_id ON stock_movements(shop_id, product_id, id)
-- statement
CREATE TRIGGER sales_no_update BEFORE UPDATE ON sales BEGIN SELECT RAISE(ABORT, 'Sales are append-only'); END
-- statement
CREATE TRIGGER sales_no_delete BEFORE DELETE ON sales BEGIN SELECT RAISE(ABORT, 'Sales are append-only'); END
-- statement
CREATE TRIGGER sale_items_no_update BEFORE UPDATE ON sale_items BEGIN SELECT RAISE(ABORT, 'Sale items are append-only'); END
-- statement
CREATE TRIGGER sale_items_no_delete BEFORE DELETE ON sale_items BEGIN SELECT RAISE(ABORT, 'Sale items are append-only'); END
-- statement
CREATE TABLE sale_events (
    id TEXT PRIMARY KEY NOT NULL,
    sale_id TEXT NOT NULL,
    shop_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('created', 'voided')),
    performed_by TEXT CHECK(performed_by IS NULL OR length(trim(performed_by)) > 0),
    reason TEXT NOT NULL CHECK(length(trim(reason)) > 0),
    created_at TEXT NOT NULL,
    CHECK(type != 'voided' OR performed_by IS NOT NULL),
    UNIQUE(sale_id, type),
    UNIQUE(shop_id, sale_id, id),
    FOREIGN KEY (shop_id, sale_id) REFERENCES sales(shop_id, id) ON DELETE RESTRICT
)
-- statement
CREATE INDEX sale_events_shop_sale_created ON sale_events(shop_id, sale_id, created_at, id)
-- statement
CREATE TRIGGER sale_events_no_update BEFORE UPDATE ON sale_events BEGIN SELECT RAISE(ABORT, 'Sale events are append-only'); END
-- statement
CREATE TRIGGER sale_events_no_delete BEFORE DELETE ON sale_events BEGIN SELECT RAISE(ABORT, 'Sale events are append-only'); END
-- statement
CREATE TABLE sale_void_items (
    sale_item_id TEXT PRIMARY KEY NOT NULL,
    void_event_id TEXT NOT NULL,
    shop_id TEXT NOT NULL,
    sale_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    stock_movement_id TEXT NOT NULL UNIQUE,
    quantity_milli INTEGER NOT NULL CHECK(typeof(quantity_milli) = 'integer' AND quantity_milli > 0),
    FOREIGN KEY (shop_id, sale_id, void_event_id) REFERENCES sale_events(shop_id, sale_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (sale_id, sale_item_id, product_id) REFERENCES sale_items(sale_id, id, product_id) ON DELETE RESTRICT,
    FOREIGN KEY (shop_id, product_id, stock_movement_id) REFERENCES stock_movements(shop_id, product_id, id) ON DELETE RESTRICT
)
-- statement
CREATE INDEX sale_void_items_sale ON sale_void_items(shop_id, sale_id)
-- statement
CREATE TRIGGER sale_void_items_no_update BEFORE UPDATE ON sale_void_items BEGIN SELECT RAISE(ABORT, 'Sale void items are append-only'); END
-- statement
CREATE TRIGGER sale_void_items_no_delete BEFORE DELETE ON sale_void_items BEGIN SELECT RAISE(ABORT, 'Sale void items are append-only'); END
-- statement
CREATE TABLE audit_log (
    id TEXT PRIMARY KEY NOT NULL,
    shop_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    performed_by TEXT NOT NULL CHECK(length(trim(performed_by)) > 0),
    action TEXT NOT NULL CHECK(length(trim(action)) > 0),
    target_table TEXT NOT NULL CHECK(length(trim(target_table)) > 0),
    target_id TEXT NOT NULL CHECK(length(trim(target_id)) > 0),
    event_id TEXT NOT NULL UNIQUE,
    reason TEXT NOT NULL CHECK(length(trim(reason)) > 0),
    old_value TEXT NOT NULL CHECK(length(trim(old_value)) > 0),
    new_value TEXT NOT NULL CHECK(length(trim(new_value)) > 0),
    created_at TEXT NOT NULL,
    FOREIGN KEY (shop_id, target_id, event_id) REFERENCES sale_events(shop_id, sale_id, id) ON DELETE RESTRICT
)
-- statement
CREATE INDEX audit_log_shop_target ON audit_log(shop_id, target_table, target_id, created_at, id)
-- statement
CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON audit_log BEGIN SELECT RAISE(ABORT, 'Audit log is append-only'); END
-- statement
CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON audit_log BEGIN SELECT RAISE(ABORT, 'Audit log is append-only'); END
-- statement
PRAGMA user_version=5