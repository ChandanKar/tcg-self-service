package com.tcgdigital.vmcontrol.controller.support;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagingTest {

    @Test
    void negativePageIsRejected() {
        assertThatThrownBy(() -> Paging.of(-1, 10, 100))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("page");
    }

    @Test
    void zeroSizeIsRejected() {
        assertThatThrownBy(() -> Paging.of(0, 0, 100))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("size");
    }

    @Test
    void sizeAboveMaximumIsClamped() {
        assertThat(Paging.of(2, 5000, 100).getPageSize()).isEqualTo(100);
    }

    @Test
    void validRequestKeepsPageSizeAndSort() {
        Sort sort = Sort.by("name");
        PageRequest request = Paging.of(3, 25, 100, sort);

        assertThat(request.getPageNumber()).isEqualTo(3);
        assertThat(request.getPageSize()).isEqualTo(25);
        assertThat(request.getSort()).isEqualTo(sort);
    }

    @Test
    void maxSizeBelowOneIsAProgrammingError() {
        assertThatThrownBy(() -> Paging.of(0, 10, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
