-- U19-T05: on-demand prices read from the AWS Price List API (pricing:GetProducts), per service,
-- region and type, so a VM in any commercial region has a price, not only those in the bundled list.
-- unit_price is NULL when AWS lists no such product there (asked again after a day).
CREATE TABLE price_cache (
    price_cache_id VARCHAR(36) NOT NULL PRIMARY KEY,
    service_code VARCHAR(32) NOT NULL,
    region_code VARCHAR(32) NOT NULL,
    price_key VARCHAR(128) NOT NULL,
    unit_price DECIMAL(14, 6) NULL,
    unit VARCHAR(16) NULL,
    fetched_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_price_cache UNIQUE (service_code, region_code, price_key)
);
