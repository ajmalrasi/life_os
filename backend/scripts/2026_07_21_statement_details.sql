-- Required once on an existing LifeOS Postgres database before deploying the
-- statement-details backend. create_all creates the new child table but does
-- not ALTER card_statements, so keep these additions explicit and idempotent.
ALTER TABLE card_statements
    ADD COLUMN IF NOT EXISTS details_status varchar(16) NOT NULL DEFAULT 'pending';
ALTER TABLE card_statements
    ADD COLUMN IF NOT EXISTS details_parser_version integer NOT NULL DEFAULT 0;
ALTER TABLE card_statements
    ADD COLUMN IF NOT EXISTS details_parsed_at timestamptz NULL;

CREATE TABLE IF NOT EXISTS card_statement_items (
    id serial PRIMARY KEY,
    statement_id integer NOT NULL REFERENCES card_statements(id) ON DELETE CASCADE,
    sequence integer NOT NULL,
    txn_date date NOT NULL,
    description varchar(512) NOT NULL,
    amount numeric(14, 2) NOT NULL,
    direction varchar(8) NOT NULL,
    item_type varchar(24) NOT NULL DEFAULT 'purchase',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_stmt_item_sequence UNIQUE (statement_id, sequence)
);
CREATE INDEX IF NOT EXISTS ix_card_statement_items_statement_id
    ON card_statement_items (statement_id);
CREATE INDEX IF NOT EXISTS ix_card_statement_items_txn_date
    ON card_statement_items (txn_date);
