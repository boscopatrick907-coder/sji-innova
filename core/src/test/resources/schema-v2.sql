CREATE TABLE products (
                            id TEXT PRIMARY KEY NOT NULL,
                            shop_id TEXT NOT NULL,
                            name TEXT NOT NULL CHECK(length(trim(name)) > 0),
                            unit_price INTEGER NOT NULL CHECK(typeof(unit_price) = 'integer' AND unit_price >= 0),
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            version INTEGER NOT NULL CHECK(version >= 1)
                        );

CREATE INDEX products_shop_id ON products(shop_id);
ALTER TABLE products ADD COLUMN model_reference TEXT;
ALTER TABLE products ADD COLUMN cost_price INTEGER CHECK(cost_price IS NULL OR (typeof(cost_price) = 'integer' AND cost_price >= 0));
ALTER TABLE products ADD COLUMN category TEXT;
ALTER TABLE products ADD COLUMN unit TEXT NOT NULL DEFAULT 'piece' CHECK(unit IN ('piece', 'kg', 'litre'));
CREATE TABLE product_specifications (
    product_id TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK(length(trim(name)) > 0),
    value TEXT NOT NULL CHECK(length(trim(value)) > 0),
    PRIMARY KEY (product_id, name)
);
PRAGMA user_version = 2;
