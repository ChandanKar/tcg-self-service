-- V34: the exact (case-sensitive) EKS cluster name, separate from the environment's lowercased
-- slug (M3): 'MyCluster' was saved as name 'mycluster' and every AWS call then found nothing.
-- Existing EKS environments keep working: their cluster name is what their name already was.
ALTER TABLE environment ADD COLUMN eks_cluster_name VARCHAR(255) NULL;
UPDATE environment SET eks_cluster_name = name WHERE service_type = 'EKS';
