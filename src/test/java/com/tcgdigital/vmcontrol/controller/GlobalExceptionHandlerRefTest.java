package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.exception.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** U17-T01: a 500 carries a short ref that is also in the log; a 4xx carries none. */
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerRefTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void serverErrorHasARefThatIsLogged(CapturedOutput output) {
        ResponseEntity<Map<String, Object>> response = handler.handleRuntimeException(new IllegalStateException("boom"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        String ref = (String) response.getBody().get("ref");
        assertThat(ref).matches("[0-9a-f]{8}");
        assertThat(response.getBody().get("message")).isEqualTo("Something failed on the server. Try again; if it keeps happening, give your administrator the ref.");
        assertThat(output.getAll()).contains("[ref " + ref + "]").contains("boom");
    }

    @Test
    void anyExceptionGetsItsOwnRef() {
        Object first = handler.handleException(new Exception("one")).getBody().get("ref");
        Object second = handler.handleException(new Exception("two")).getBody().get("ref");

        assertThat(first).isNotNull().isNotEqualTo(second);
    }

    @Test
    void clientErrorHasNoRef() {
        ResponseEntity<Map<String, Object>> response = handler.handleConflict(new ConflictException("taken"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).doesNotContainKey("ref");
    }
}
