package com.tcgdigital.vmcontrol.service.aws;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpResponse;

import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/** Region-specific services (U19-T06): a failure is classified and worded for System health; a success clears it. */
class RegionalServiceNotesTest {

    private static AwsServiceException aws(String code, int status) {
        return AwsServiceException.builder().message(code).statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(code).build()).build();
    }

    @Test
    void failuresAreClassified() {
        assertThat(RegionalServiceNotes.classify(SdkClientException.builder().message("Unable to execute HTTP request")
                .cause(new UnknownHostException("compute-optimizer.me-central-1.amazonaws.com")).build()))
                .isEqualTo(RegionalServiceNotes.Kind.NOT_IN_REGION);
        assertThat(RegionalServiceNotes.classify(aws("OptInRequiredException", 403))).isEqualTo(RegionalServiceNotes.Kind.NOT_OPTED_IN);
        assertThat(RegionalServiceNotes.classify(new RuntimeException("x", aws("AccessDeniedException", 403))))
                .isEqualTo(RegionalServiceNotes.Kind.ACCESS_DENIED);
        assertThat(RegionalServiceNotes.classify(aws("PermanentRedirect", 301))).isEqualTo(RegionalServiceNotes.Kind.WRONG_REGION);
        assertThat(RegionalServiceNotes.classify(new IllegalStateException("boom"))).isEqualTo(RegionalServiceNotes.Kind.OTHER);
    }

    @Test
    void notesAreWordedKeptPerServiceAndRegionAndClearedOnSuccess() {
        RegionalServiceNotes notes = new RegionalServiceNotes();
        notes.record("Compute Optimizer", "acct", "me-central-1", SdkClientException.builder().message("Unable to execute HTTP request")
                .cause(new UnknownHostException("x")).build());
        notes.record("Compute Optimizer", "acct", "eu-west-1", aws("OptInRequiredException", 403));
        notes.record("Compute Optimizer", "acct", "eu-west-1", aws("OptInRequiredException", 403)); // the same note, not two

        assertThat(notes.notes()).hasSize(2);
        assertThat(notes.notes().get(1).message()).isEqualTo("Compute Optimizer is not offered in me-central-1; that region is left out for it.");
        assertThat(notes.notes().get(0).message())
                .isEqualTo("Compute Optimizer is not turned on for the account in eu-west-1; turn it on in the AWS console to use it.");

        notes.clear("Compute Optimizer", "acct", "eu-west-1");
        assertThat(notes.notes()).extracting(RegionalServiceNotes.Note::region).containsExactly("me-central-1");
    }

    @Test
    void aBucketInAnotherRegionSaysWhereItIs() {
        AwsServiceException moved = AwsServiceException.builder().message("moved").statusCode(301)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("PermanentRedirect")
                        .sdkHttpResponse(SdkHttpResponse.builder().statusCode(301).putHeader("x-amz-bucket-region", "eu-central-1").build())
                        .build()).build();
        RegionalServiceNotes notes = new RegionalServiceNotes();

        RegionalServiceNotes.Note note = notes.record("Carbon export (S3)", "acct", "us-east-1", moved);

        assertThat(note.kind()).isEqualTo(RegionalServiceNotes.Kind.WRONG_REGION);
        assertThat(note.message()).isEqualTo("Carbon export (S3)'s data is not in us-east-1; it is in eu-central-1. Set the region it is in.");
    }
}
