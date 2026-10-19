-- S10: downgrades and next-cycle upgrades wait on the line and apply at the next renewal.

ALTER TABLE subscriptions_subscription
    ADD COLUMN pending_package_id uuid,
    ADD CONSTRAINT subscriptions_subscription_pending_package_fk FOREIGN KEY (tenant_id, pending_package_id)
        REFERENCES packages_package (tenant_id, id);
