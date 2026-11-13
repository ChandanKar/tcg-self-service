package com.tcgdigital.vmcontrol.dto;

import java.util.List;

/**
 * What adding people does (U13-T02): per person NEW (gets an account), EXISTING (already uses the
 * app: access and team only) or SKIPPED with the reason, and the access rows in the bulk grant's
 * shape (NEW / EXTEND / UPGRADE / SKIP). applied is false for a preview. Counts: people by
 * outcome; grants new, changed (extended or upgraded) and skipped.
 */
public record BulkOnboardResultDTO(
        boolean applied,
        int newPeople,
        int existingPeople,
        int skippedPeople,
        int newGrants,
        int changedGrants,
        int skippedGrants,
        List<PersonResult> people,
        List<BulkGrantPlanDTO.Row> grants
) {
    public enum Outcome { NEW, EXISTING, SKIPPED }

    /**
     * initialPassword (U25-T02): the password the server generated for a NEW person who asked for one,
     * shown once to the administrator; null for everyone else and always null on a preview.
     */
    public record PersonResult(String email, String displayName, Outcome outcome, String reason, String userId,
                               String teamId, int grantsNew, int grantsChanged, int grantsSkipped,
                               String initialPassword) {

        /** Never in logs. */
        @Override
        public String toString() {
            return "PersonResult[email=" + email + ", outcome=" + outcome + ", userId=" + userId
                    + ", initialPassword=" + (initialPassword == null ? "none" : "generated") + "]";
        }
    }
}
