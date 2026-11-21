\echo '=== 同 VIN 多笔垫款的存量（加唯一约束前必须先看清）==='
SELECT vin, COUNT(*) AS cnt, string_agg(status, ',' ORDER BY id) AS statuses
  FROM advances
 GROUP BY vin
HAVING COUNT(*) > 1
 ORDER BY cnt DESC;

\echo '=== 全表规模与状态分布 ==='
SELECT status, COUNT(*) FROM advances GROUP BY status ORDER BY 2 DESC;

\echo '=== 是否有重复 VIN 且都是非作废态（会顶住唯一索引）==='
SELECT COUNT(*) AS dup_vin_groups FROM (
  SELECT vin FROM advances WHERE status <> 'VOIDED' GROUP BY vin HAVING COUNT(*) > 1
) t;
