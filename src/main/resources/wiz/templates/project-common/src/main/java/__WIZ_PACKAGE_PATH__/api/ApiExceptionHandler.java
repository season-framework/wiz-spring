package __WIZ_PACKAGE_ROOT__.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import __WIZ_PACKAGE_ROOT__.api.model.ApiError;
import __WIZ_PACKAGE_ROOT__.service.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException exception, HttpServletRequest request) {
        return error(exception.status(), exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return error(HttpStatus.BAD_REQUEST, "요청 값을 확인해주세요.", request, fields);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> conflict(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "이미 존재하거나 참조 중인 데이터입니다.", request, Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformedJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "JSON 요청 본문을 확인해주세요.", request, Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> invalidParameter(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                exception.getName() + " 요청 값을 확인해주세요.",
                request,
                Map.of(exception.getName(), "invalid value"));
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                fields));
    }
}
