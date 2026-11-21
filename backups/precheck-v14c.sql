\echo '=== 重复 PENDING 明细 ==='
SELECT a.id, a.advance_no, a.vin, a.amount, a.status, a.credit_line_id,
       v.status AS vehicle_status, cl.approved_limit, cl.used_amount
FROM advances a
LEFT JOIN vehicle_units v ON v.vin = a.vin
LEFT JOIN credit_lines cl ON cl.id = a.credit_line_id
WHERE a.vin IN ('COLA017481','COLB017481')
ORDER BY a.vin, a.id;
