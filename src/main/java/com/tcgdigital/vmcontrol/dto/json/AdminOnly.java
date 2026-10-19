package com.tcgdigital.vmcontrol.dto.json;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a DTO property that only administrators may see (U01-T01), such as an AWS instance id.
 * For everyone else the property is left out of the JSON; see
 * {@link com.tcgdigital.vmcontrol.security.ProviderIdVisibility}.
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = AdminOnlySerializer.class)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public @interface AdminOnly {
}
