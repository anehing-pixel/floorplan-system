\echo '=== 1. 垫款状态分布 ==='
SELECT status, COUNT(*) AS cnt FROM advances GROUP BY status ORDER BY cnt DESC;

\echo '=== 2. 同一 VIN 多笔非终态垫款（会挡唯一索引） ==='
SELECT vin, COUNT(*) AS cnt, string_agg(status || ':' || advance_no, ', ' ORDER BY id) AS detail
FROM advances GROUP BY vin HAVING COUNT(*) > 1 ORDER BY cnt DESC;

\echo '=== 3. 车辆状态分布 ==='
SELECT status, COUNT(*) AS cnt FROM vehicle_units GROUP BY status ORDER BY cnt DESC;

\echo '=== 4. 已结清车辆对应的垫款记录状态（验证结清后垫款是否仍为 PAID） ==='
SELECT v.vin, v.status AS vehicle_status, a.advance_no, a.status AS advance_status
FROM vehicle_units v JOIN advances a ON a.vin = v.vin
WHERE v.status IN ('SETTLED', 'CLOSED') ORDER BY v.vin LIMIT 20;

\echo '=== 5. 在库车辆是否也存在可提前还款的（IN_STOCK 且有 PAID 垫款） ==='
SELECT COUNT(*) AS in_stock_with_paid
FROM vehicle_units v JOIN advances a ON a.vin = v.vin
WHERE v.status = 'IN_STOCK' AND a.status = 'PAID';

\echo '=== 6. advances 表现有索引 ==='
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'advances';
