-- V4: 保险出险理赔金额列
-- JdbcInsurancePolicyRepository 在理赔时会写 claimed_amount（已理赔金额），
-- V2 建表时遗漏该列，导致投保/理赔 500（PSQLException: column does not exist）。
ALTER TABLE insurance_policies
    ADD COLUMN IF NOT EXISTS claimed_amount BIGINT NOT NULL DEFAULT 0;
