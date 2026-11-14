-- U26-T05: rightsizing judged CPU on the highest 5-minute AVERAGE, about half the real peak (V01 check C2:
-- a VM averaging 32% at its busiest 5 minutes peaked at 68%). Each sample now also keeps CloudWatch's
-- Maximum statistic of CPUUtilization for its period, and each day the highest of those. Null for rows
-- stored before this change: rightsizing then falls back to the averages and says so.
ALTER TABLE vm_metric_sample ADD COLUMN cpu_maximum DECIMAL(7,3) NULL;
ALTER TABLE vm_metric_daily ADD COLUMN max_cpu_maximum DECIMAL(7,3) NULL;
