package com.tcgdigital.vmcontrol.dto;

import java.time.OffsetDateTime;

/**
 * One upcoming run of a schedule rule (E19-T04): when (with the rule's offset), a readable label
 * such as "Mon 12 Oct, 20:00 IST", START or STOP, which window, and why it is skipped (if it is).
 */
public record ScheduleRunPreviewDTO(OffsetDateTime at, String label, String action, int windowIndex,
                                    boolean skipped, String skipReason) {
}
