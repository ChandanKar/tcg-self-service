-- V32: consecutive state-sync results of NOT_FOUND for a VM (M7). A VM is deactivated only after
-- vm.state.sync.not-found-threshold consecutive NOT_FOUND results (default 3), not after one
-- (e.g. a wrong region edit). Reset to 0 whenever the instance is found again or the VM is reactivated.
ALTER TABLE vm ADD COLUMN not_found_count INT NOT NULL DEFAULT 0;
