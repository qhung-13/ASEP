-- An observed MR identity is an external fact, not proof that its head is verified.
-- Preserve V1/V2 checksums; replace V1's table-level IID/CONFIRMED equivalence.
ALTER TABLE code_delivery.operations DROP CONSTRAINT operations_check;
ALTER TABLE code_delivery.operations ADD CONSTRAINT operations_mr_identity_state_check CHECK (
    (mr_state = 'CONFIRMED' AND mr_iid IS NOT NULL)
    OR mr_state IN ('UNKNOWN', 'BLOCKED', 'MANUAL_RECONCILIATION_REQUIRED')
    OR (mr_state IN ('PLANNED', 'IN_PROGRESS') AND mr_iid IS NULL)
);
