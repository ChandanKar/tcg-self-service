package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.Size;

/** An owner's approve/deny of a lease extension request (E15-T04). */
public class DecideLeaseExtensionDTO {

    @Size(max = 500, message = "Notes are at most 500 characters")
    private String notes;

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
