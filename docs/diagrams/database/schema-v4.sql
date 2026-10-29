-- Documentation snapshot of an EMPTY schema-v4 database.
-- Generated from SqliteDatabase.initialize() at main 5ff771a.
-- Not an upgrade script; use the Java migration runner for existing databases.
PRAGMA foreign_keys = ON;

CREATE TABLE product_specifications (
    product_id TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK(length(trim(name)) > 0),
    value TEXT NOT NULL CHECK(length(trim(value)) > 0),
    PRIMARY KEY (product_id, name)
);

CREATE TABLE products (
    id TEXT PRIMARY KEY NOT NULL,
    shop_id TEXT NOT NULL,
    name TEXT NOT NULL CHECK(length(trim(name)) > 0),
    unit_price INTEGER NOT NULL CHECK(typeof(unit_price) = 'integer' AND unit_price >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL CHECK(version >= 1)
, model_reference TEXT, cost_price INTEGER CHECK(cost_price IS NULL OR (typeof(cost_price) = 'integer' AND cost_price >= 0)), category TEXT, unit TEXT NOT NULL DEFAULT 'piece' CHECK(unit IN ('piece', 'kg', 'litre')), current_stock_milli INTEGER NOT NULL DEFAULT 0 CHECK(typeof(current_stock_milli) = 'integer'), min_stock_milli INTEGER NOT NULL DEFAULT 0 CHECK(typeof(min_stock_milli) = 'integer' AND min_stock_milli >= 0));

CREATE TABLE sale_items (
    id TEXT PRIMARY KEY NOT NULL,
    sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,
    product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    product_name TEXT NOT NULL,
    unit TEXT NOT NULL,
    quantity_milli INTEGER NOT NULL CHECK(quantity_milli > 0),
    unit_price INTEGER NOT NULL CHECK(unit_price >= 0),
    subtotal INTEGER NOT NULL CHECK(subtotal >= 0)
);

CREATE TABLE sales (
    id TEXT PRIMARY KEY NOT NULL,
    shop_id TEXT NOT NULL,
    receipt_number TEXT NOT NULL CHECK(length(trim(receipt_number)) > 0),
    device_id TEXT NOT NULL,
    payment_method TEXT NOT NULL CHECK(payment_method IN ('cash', 'momo', 'credit')),
    total_amount INTEGER NOT NULL CHECK(total_amount >= 0),
    created_at TEXT NOT NULL
);

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
);

CREATE INDEX products_shop_id ON products(shop_id);

CREATE INDEX sale_items_sale ON sale_items(sale_id);

CREATE INDEX sales_shop_created ON sales(shop_id, created_at, id);

CREATE INDEX stock_movements_product ON stock_movements(shop_id, product_id, created_at, id);

CREATE TRIGGER stock_movements_no_delete BEFORE DELETE ON stock_movements BEGIN SELECT RAISE(ABORT, 'Stock movements are append-only'); END;

CREATE TRIGGER stock_movements_no_update BEFORE UPDATE ON stock_movements BEGIN SELECT RAISE(ABORT, 'Stock movements are append-only'); END;

PRAGMA user_version = 4;
