package com.nspawnmgr.web;

import com.nspawnmgr.cli.ContainerCliException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

@RestControllerAdvice(basePackageClasses = ContainerApiController.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    /**
     * Without this, a failed machinectl/wrapper-script/SSH operation reaches the client as a bare
     * 500 with no body (Spring's default error attributes omit the exception message unless
     * server.error.include-message is turned on) — the admin sees "Internal Server Error" with no
     * way to tell a checksum mismatch from a network outage from a missing sudo password. This
     * surfaces the real message (already written to not leak anything more sensitive than a shell
     * command's own stderr) so the UI's existing "Failed: " + response.text() handling actually
     * shows something actionable.
     *
     * <p>Logged here explicitly (ERROR, with stack trace) because an exception caught by an
     * {@code @ExceptionHandler} is, by definition, no longer "unhandled" — it won't get the default
     * stack-trace-to-catalina.out logging Tomcat/Spring Boot normally does for an exception that
     * reaches the container itself, so without this line these failures would leave no server-side
     * record at all, only the transient message shown in the admin's browser at the time.
     */
    @ExceptionHandler(ContainerCliException.class)
    public ResponseEntity<String> handleCliFailure(ContainerCliException e) {
        log.error("Container CLI operation failed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
    }

    /**
     * Same rationale as {@link #handleCliFailure}: without this, a failed {@code @Valid} check
     * (e.g. a too-short password) reaches the client as a bare 400 with no body at all — Spring's
     * default validation-error handling omits field error details unless configured on. This
     * surfaces which field failed and why (bean validation's own message, e.g. "size must be
     * between 8 and 128") instead of an opaque JSON blob.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidationFailure(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(message);
    }

    /**
     * In practice this handler never actually runs for a real multipart upload -
     * {@link MaxUploadSizeExceededException} is thrown by {@code DispatcherServlet.checkMultipart()}
     * *before* a handler method is ever dispatched to, which is before any
     * {@code @ExceptionHandler}/{@code @ControllerAdvice} machinery gets a chance to see it (that
     * machinery only wraps handler execution). Confirmed live: it instead escapes to Spring Boot's
     * own {@code ErrorPageFilter}, landing on the generic Whitelabel error page with none of this
     * method's message. {@link MultipartSizeLimitFilter} is the real fix (a plain servlet filter,
     * positioned to catch the exception before {@code ErrorPageFilter} does) - this method is kept
     * anyway as a backstop for the (currently theoretical) case of some other code path throwing
     * this same exception mid-request, and so both places share one message to update together.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(MultipartSizeLimitFilter.UPLOAD_TOO_LARGE_MESSAGE);
    }
}
