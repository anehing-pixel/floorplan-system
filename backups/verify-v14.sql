\echo '=== 1. Flyway 版本 ==='
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;

\echo '=== 2. 垫款状态分布（应出现 VOIDED / REDEEMED） ==='
SELECT status, COUNT(*) FROM advances GROUP BY status ORDER BY COUNT(*) DESC;

\echo '=== 3. 一车一笔有效垫款索引 ==='
SELECT indexname, indexdef FROM pg_indexes WHERE indexname IN ('uk_adv_vin_active','uk_po_vin_active');

\echo '=== 4. 同 VIN 多笔有效垫款（必须为空） ==='
SELECT vin, COUNT(*) FROM advances WHERE status IN ('PENDING','PROCESSING','PAID')
GROUP BY vin HAVING COUNT(*) > 1;

\echo '=== 5. 同一 VIN 多笔历史垫款（期望：COLA017481 / COLB017481 各 2 条） ==='
SELECT vin, COUNT(*) AS cnt, string_agg(status, ',' ORDER BY id) AS timeline
FROM advances GROUP BY vin HAVING COUNT(*) > 1 ORDER BY vin;

\echo '=== 6. 已结清车辆的垫款是否已推进到 REDEEMED ==='
SELECT a.advance_no, a.vin, a.status, a.closed_reason, v.status AS vehicle_status
FROM advances a JOIN vehicle_units v ON v.vin = a.vin
WHERE v.status IN ('SETTLED','CLOSED') ORDER BY a.vin;

\echo '=== 7. 额度占用是否与有效垫款一致（额度 239） ==='
SELECT cl.id, cl.used_amount,
       (SELECT COALESCE(SUM(a.amount),0) FROM advances a
        WHERE a.credit_line_id = cl.id AND a.status IN ('PENDING','PROCESSING','PAID')) AS active_adv_sum
FROM credit_lines cl WHERE cl.id = 239;

\echo '=== 8. 车辆状态分布 ==='
SELECT status, COUNT(*) FROM vehicle_units GROUP BY status ORDER BY COUNT(*) DESC;
