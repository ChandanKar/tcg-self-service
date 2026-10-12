package com.tcgdigital.vmcontrol.dto;

/** One pre-check of a safe resize as the Apply dialog lists it (E30-T07). */
public record ResizeCheckDTO(String name, boolean passed, String message) {
}
