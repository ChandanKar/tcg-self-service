package com.tcgdigital.vmcontrol.dto.json;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a free-text DTO property that can quote AWS resource ids, such as an error message (U01-T03).
 * Administrators get the text as stored; everyone else gets it with the ids masked
 * ({@link com.tcgdigital.vmcontrol.service.support.ProviderIdRedactor}).
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = RedactingStringSerializer.class)
public @interface RedactProviderIds {
}
