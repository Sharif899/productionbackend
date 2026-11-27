package com.sharif899.prodbackend.web.dto;

import java.util.Map;

/**
 * The single envelope every response uses, success or error, so
 * clients only ever have to handle one shape.
 */
public record ApiResponse<T>(boolean success, T data, ErrorDetail error) {

    public record ErrorDetail(String code, String message, Map<String, String> fieldErrors) {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(ErrorDetail error) {
        return new ApiResponse<>(false, null, error);
    }
}
