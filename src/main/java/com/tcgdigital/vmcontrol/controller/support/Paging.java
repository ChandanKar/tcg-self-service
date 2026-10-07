package com.tcgdigital.vmcontrol.controller.support;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Builds a validated {@link PageRequest} from request parameters: a negative page or a size
 * below 1 is a 400 ({@link ValidationException}), and a size above {@code maxSize} is clamped.
 */
public final class Paging {

    /** For endpoints without a specific limit. */
    public static final int DEFAULT_MAX_SIZE = 100;

    private Paging() {
    }

    public static PageRequest of(int page, int size, int maxSize) {
        return of(page, size, maxSize, Sort.unsorted());
    }

    public static PageRequest of(int page, int size, int maxSize, Sort sort) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1");
        }
        if (page < 0) {
            throw new ValidationException("page must be >= 0");
        }
        if (size < 1) {
            throw new ValidationException("size must be >= 1");
        }
        return PageRequest.of(page, Math.min(size, maxSize), sort);
    }
}
