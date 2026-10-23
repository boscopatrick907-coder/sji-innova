CREATE TABLE products (
                            id TEXT PRIMARY KEY NOT NULL,
                            shop_id TEXT NOT NULL,
                            name TEXT NOT NULL CHECK(length(trim(name)) > 0),
                            unit_price INTEGER NOT NULL CHECK(typeof(unit_price) = 'integer' AND unit_price >= 0),
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            version INTEGER NOT NULL CHECK(version >= 1)
                        );
