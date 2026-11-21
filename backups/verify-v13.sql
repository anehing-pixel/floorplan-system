\echo '=== Flyway 版本 ==='
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;

\echo '=== V13 后车辆状态分布 ==='
SELECT status, COUNT(*) AS cnt FROM vehicle_units GROUP BY status ORDER BY cnt DESC;

\echo '=== 拆分后的非终结车辆（核对是否与垫款指令一致）==='
SELECT v.status AS vehicle_status,
       (SELECT string_agg(DISTINCT a.status, ',' ORDER BY a.status)
          FROM advances a WHERE a.vin = v.vin) AS adv_statuses,
       COUNT(*) AS cnt
  FROM vehicle_units v
 WHERE v.status IN ('CREATED','PENDING_ADVANCE')
 GROUP BY v.status, adv_statuses
 ORDER BY v.status, adv_statuses;

\echo '=== 抽查：CREATED 车辆明细 ==='
SELECT vin, dealer_id, purchase_order_id, advanced_at, principal_balance
  FROM vehicle_units WHERE status = 'CREATED' ORDER BY vin;
