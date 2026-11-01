-- How the buyer paid the amount received, and an optional reference
-- (UPI transaction id, NEFT/IMPS UTR or account number).
ALTER TABLE sales ADD COLUMN payment_method VARCHAR(10)
    CHECK (payment_method IS NULL OR payment_method IN ('CASH', 'UPI', 'NEFT'));
ALTER TABLE sales ADD COLUMN payment_reference VARCHAR(100);
