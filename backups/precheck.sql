SELECT status, COUNT(*) AS cnt, SUM(COALESCE(principal_balance,0)) AS principal_sum
  FROM vehicle_units
 GROUP BY status
 ORDER BY cnt DESC;

\echo '--- 待迁移清单（FINANCED 且无付款痕迹）---'
SELECT v.vin,
       v.status,
       v.advanced_at,
       (SELECT COUNT(*) FROM advances a WHERE a.vin = v.vin AND a.status = 'PAID') AS paid_cnt,
       CASE WHEN v.advanced_at IS NULL
             AND NOT EXISTS (SELECT 1 FROM advances a WHERE a.vin = v.vin AND a.status = 'PAID')
            THEN 'WILL_MIGRATE' ELSE 'KEEP_FINANCED' END AS decision
  FROM vehicle_units v
 WHERE v.status = 'FINANCED'
 ORDER BY decision, v.vin
 LIMIT 30;

\echo '--- 汇总：将迁移 / 保留 ---'
SELECT CASE WHEN v.advanced_at IS NULL
             AND NOT EXISTS (SELECT 1 FROM advances a WHERE a.vin = v.vin AND a.status = 'PAID')
            THEN 'WILL_MIGRATE' ELSE 'KEEP_FINANCED' END AS decision,
       COUNT(*) AS cnt
  FROM vehicle_units v
 WHERE v.status = 'FINANCED'
 GROUP BY 1;

\echo '--- flyway 当前版本 ---'
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
