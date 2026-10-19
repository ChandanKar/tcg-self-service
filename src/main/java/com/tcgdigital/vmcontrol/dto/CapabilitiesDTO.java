package com.tcgdigital.vmcontrol.dto;

import java.util.Map;

/**
 * What the signed-in person's role and the server's switches allow (U02-T01), so the app opens only pages
 * that will work and never asks a switched-off feature for data. The server still checks every call.
 *
 * @param admin    role ADMIN
 * @param envAdmin role ENV_ADMIN (or ADMIN)
 * @param features feature key to on/off, e.g. templates, approvals, webhooks, chat, spot, carbon, rds, leases
 */
public record CapabilitiesDTO(boolean admin, boolean envAdmin, Map<String, Boolean> features) {
}
