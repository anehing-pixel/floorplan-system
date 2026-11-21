\echo '=== 车辆状态分布 ==='
SELECT status, COUNT(*) AS cnt FROM vehicle_units GROUP BY status ORDER BY cnt DESC;

\echo '=== 非终结车辆与其垫款记录（决定 V13 落 CREATED 还是 PENDING_ADVANCE）==='
SELECT v.vin,
       v.status AS vehicle_status,
       v.advanced_at,
       (SELECT string_agg(DISTINCT a.status, ',' ORDER BY a.status)
          FROM advances a WHERE a.vin = v.vin) AS adv_statuses
  FROM vehicle_units v
 WHERE v.status IN ('PENDING_ADVANCE', 'FINANCED', 'IN_STOCK', 'SOLD_PENDING')
 ORDER BY v.status, v.vin;

\echo '=== 汇总：按"有无垫款记录"分组 ==='
SELECT v.status,
       CASE WHEN EXISTS (SELECT 1 FROM advances a WHERE a.vin = v.vin AND a.status = 'PAID') THEN 'HAS_PAID'
            WHEN EXISTS (SELECT 1 FROM advances a WHERE a.vin = v.vin AND a.status IN ('PENDING','PROCESSING')) THEN 'HAS_ACTIVE'
            WHEN EXISTS (SELECT 1 FROM advances a WHERE a.vin = v.vin AND a.status = 'FAILED') THEN 'HAS_FAILED_ONLY'
            ELSE 'NO_ADVANCE' END AS adv_bucket,
       COUNT(*) AS cnt
  FROM vehicle_units v
 GROUP BY v.status, adv_bucket
 ORDER BY v.status, adv_bucket;

\echo '=== 垫款状态分布 ==='
SELECT status, COUNT(*) AS cnt FROM advances GROUP BY status ORDER BY cnt DESC;
