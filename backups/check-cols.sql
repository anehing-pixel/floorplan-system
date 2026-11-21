SELECT column_name, data_type
  FROM information_schema.columns
 WHERE table_name = 'vehicle_units'
   AND column_name IN ('status','principal_balance','advanced_at','created_at','updated_at','dealer_id','purchase_order_id')
 ORDER BY column_name;
