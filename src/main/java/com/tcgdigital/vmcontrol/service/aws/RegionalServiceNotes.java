package com.tcgdigital.vmcontrol.service.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region-specific services, handled gracefully (U19-T06). Some AWS services are not in every region
 * (Compute Optimizer in some opt-in regions), need opting in, or live in a region of their own (the
 * carbon export's bucket, the EKS split-cost Athena data). Their callers report what went wrong here,
 * classified in plain words, instead of failing a job or raising an alert; System health lists the
 * notes (one per service, account and region; a success clears it). Kept in memory: the next run of
 * each job states it again after a restart.
 */
@Service
public class RegionalServiceNotes {

    public enum Kind {
        /** The service has no endpoint in that region. */
        NOT_IN_REGION,
        /** The account has not turned the service on (Compute Optimizer enrolment, an opt-in region). */
        NOT_OPTED_IN,
        /** A permission or an SCP refuses the call there. */
        ACCESS_DENIED,
        /** The resource lives in another region than the one configured (an S3 bucket). */
        WRONG_REGION,
        /** Anything else; the message says what AWS answered. */
        OTHER
    }

    public record Note(String service, String accountRef, String region, Kind kind, String message, Instant at) { }

    private static final Set<String> DENIED = Set.of("AccessDenied", "AccessDeniedException", "UnauthorizedOperation",
            "AuthFailure", "UnauthorizedException", "UnrecognizedClientException", "InvalidClientTokenId");
    private static final Set<String> OPT_IN = Set.of("OptInRequired", "OptInRequiredException", "SubscriptionRequiredException");
    private static final Set<String> MOVED = Set.of("PermanentRedirect", "AuthorizationHeaderMalformed", "IllegalLocationConstraintException");

    private final Map<String, Note> notes = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Records why {@code service} did not work in the account and region; returns the note. */
    public Note record(String service, String accountRef, String region, Throwable e) {
        Kind kind = classify(e);
        Note note = new Note(service, accountRef, region, kind, sentence(service, region, kind, e), clock.instant());
        notes.put(key(service, accountRef, region), note);
        return note;
    }

    /** Records a note in the caller's own words (a check that is not an exception). */
    public Note record(String service, String accountRef, String region, Kind kind, String message) {
        Note note = new Note(service, accountRef, region, kind, message, clock.instant());
        notes.put(key(service, accountRef, region), note);
        return note;
    }

    /** The service worked there: its note goes. */
    public void clear(String service, String accountRef, String region) {
        notes.remove(key(service, accountRef, region));
    }

    /** Every note, by service then region. */
    public List<Note> notes() {
        return notes.values().stream()
                .sorted(Comparator.comparing(Note::service).thenComparing(n -> String.valueOf(n.region())))
                .toList();
    }

    /** What kind of problem the failure is. */
    public static Kind classify(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof UnknownHostException) {
                return Kind.NOT_IN_REGION;
            }
            if (t instanceof AwsServiceException aws) {
                String code = aws.awsErrorDetails() == null ? null : aws.awsErrorDetails().errorCode();
                if (code != null && OPT_IN.contains(code)) return Kind.NOT_OPTED_IN;
                if (code != null && DENIED.contains(code)) return Kind.ACCESS_DENIED;
                if (code != null && MOVED.contains(code) || aws.statusCode() == 301) return Kind.WRONG_REGION;
                if (aws.getClass().getSimpleName().startsWith("OptInRequired")) return Kind.NOT_OPTED_IN;
            }
            String text = t.getMessage();
            if (text != null && (text.contains("is not available in this region") || text.contains("not supported in this region")
                    || text.contains("Unable to execute HTTP request") && t.getCause() instanceof UnknownHostException)) {
                return Kind.NOT_IN_REGION;
            }
        }
        return Kind.OTHER;
    }

    /** The bucket's own region, when S3 says where it is (x-amz-bucket-region). */
    public static String bucketRegion(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AwsServiceException aws && aws.awsErrorDetails() != null && aws.awsErrorDetails().sdkHttpResponse() != null) {
                var header = aws.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("x-amz-bucket-region");
                if (header.isPresent()) return header.get();
            }
        }
        return null;
    }

    private static String sentence(String service, String region, Kind kind, Throwable e) {
        String where = region == null ? "" : " in " + region;
        return switch (kind) {
            case NOT_IN_REGION -> service + " is not offered" + where + "; that region is left out for it.";
            case NOT_OPTED_IN -> service + " is not turned on for the account" + where + "; turn it on in the AWS console to use it.";
            case ACCESS_DENIED -> service + " refused the call" + where + "; the IAM policy or an SCP does not allow it (see docs/aws-iam-permissions.md).";
            case WRONG_REGION -> {
                String actual = bucketRegion(e);
                yield service + "'s data is not" + where + (actual == null ? "" : "; it is in " + actual) + ". Set the region it is in.";
            }
            case OTHER -> service + where + " failed: " + shorten(e == null ? null : e.getMessage());
        };
    }

    private static String shorten(String text) {
        if (text == null || text.isBlank()) return "no reason given";
        return text.length() > 240 ? text.substring(0, 240) : text;
    }

    private static String key(String service, String accountRef, String region) {
        return service + "|" + accountRef + "|" + region;
    }
}
