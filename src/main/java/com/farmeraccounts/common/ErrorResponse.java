package com.farmeraccounts.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(String code, String message, Map<String, Object> details) {

    public static ErrorResponse of(ApiException e) {
        return new ErrorResponse(e.code(), e.getMessage(), e.details());
    }
}
