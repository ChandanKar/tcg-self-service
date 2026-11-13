package com.tcgdigital.vmcontrol.dto;

/** An email domain used by active people and how many use it (U25-T03), e.g. tcgdigital.com, 643. */
public record EmailDomainCountDTO(String domain, long count) {
}
