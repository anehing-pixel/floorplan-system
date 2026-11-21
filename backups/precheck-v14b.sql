\echo '=== 1. 重复 PENDING 明细与车辆状态 ==='
SELECT a.id, a.advance_no, a.vin, a.amount, a.status, a.credit_line_id, a.idempotency_key,
       v.status AS vehicle_status, cl.total_limit, cl.used_amount, cl.available_amount
FROM advances a
LEFT JOIN vehicle_units v ON v.vin = a.vin
LEFT JOIN credit_lines cl ON cl.id = a.credit_line_id
WHERE a.vin IN ('COLA017481','COLB017481')
ORDER BY a.vin, a.id;

\echo '=== 2. credit_lines 列 ==='
SELECT column_name, data_type FROM information_schema.columns
WHERE table_name = 'credit_lines' ORDER BY ordinal_position;

\echo '=== 3. 已结清/关闭车辆对应垫款（待置 REDEEMED） ==='
SELECT a.id, a.advance_no, a.vin, a.status, v.status AS vehicle_status
FROM advances a JOIN vehicle_units v ON v.vin = a.vin
WHERE v.status IN ('SETTLED','CLOSED') AND a.status = 'PAID' ORDER BY a.vin;

\echo '=== 4. 全库同 VIN 多笔 PENDING/PROCESSING/PAID（建索引前必须为空） ==='
SELECT vin, COUNT(*) FROM advances WHERE status IN ('PENDING','PROCESSING','PAID')
GROUP BY vin HAVING COUNT(*) > 1;

\echo '=== 5. 车辆状态：IN_STOCK 且金额字段（赎回收款用） ==='
SELECT vin, status, principal_balance, advance_amount, settled_at
FROM vehicle_units WHERE status = 'IN_STOCK' ORDER BY vin LIMIT 10;
