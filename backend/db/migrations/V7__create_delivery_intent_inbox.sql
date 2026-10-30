-- Authenticated Go transport acceptance, not mutation/workflow authority.
CREATE TABLE code_delivery.delivery_intent_inbox (
    message_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    message_hash char(64) NOT NULL CHECK (message_hash ~ '^[0-9a-f]{64}$'),
    request_json jsonb NOT NULL CHECK (jsonb_typeof(request_json)='object'),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
GRANT SELECT,INSERT ON code_delivery.delivery_intent_inbox TO asep_delivery_runtime;
