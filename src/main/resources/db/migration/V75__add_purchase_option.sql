-- E31-T01: whether an EC2 instance or EKS node group runs on demand or on Spot, from inventory
-- (Instance.instanceLifecycle) and EKS sync (Nodegroup.capacityType).
ALTER TABLE vm_inventory_snapshot ADD COLUMN purchase_option VARCHAR(20) NOT NULL DEFAULT 'ON_DEMAND';
ALTER TABLE vm ADD COLUMN purchase_option VARCHAR(20) NOT NULL DEFAULT 'ON_DEMAND';
