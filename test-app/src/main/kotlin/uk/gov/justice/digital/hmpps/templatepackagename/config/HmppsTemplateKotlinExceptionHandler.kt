package uk.gov.justice.digital.hmpps.templatepackagename.config

import jakarta.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus.BAD_REQUEST
import org.springframework.http.HttpStatus.FORBIDDEN
import org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
import org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED
import org.springframework.http.HttpStatus.NOT_FOUND
import org.springframework.http.HttpStatus.UNAUTHORIZED
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

@RestControllerAdvice
class HmppsTemplateKotlinExceptionHandler {
  @ExceptionHandler(ValidationException::class)
  fun handleValidationException(e: ValidationException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        userMessage = "Validation failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Validation exception: {}", e.message) }

  @ExceptionHandler(NoResourceFoundException::class)
  fun handleNoResourceFoundException(e: NoResourceFoundException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(NOT_FOUND)
    .body(
      ErrorResponse(
        status = NOT_FOUND,
        userMessage = "No resource found failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("No resource found exception: {}", e.message) }

  @ExceptionHandler(AccessDeniedException::class)
  fun handleAccessDeniedException(e: AccessDeniedException): ResponseEntity<ErrorResponse> {
    // An unauthenticated (anonymous) caller hitting a @PreAuthorize-protected method on an otherwise
    // permitAll path has no credentials at all, so should receive a 401 rather than a 403 - mirroring
    // what Spring Security's ExceptionTranslationFilter would have done had this exception not been
    // handled here first.
    val authentication = SecurityContextHolder.getContext().authentication
    val status = if (authentication == null || authentication is AnonymousAuthenticationToken) UNAUTHORIZED else FORBIDDEN
    val reason = if (status == UNAUTHORIZED) "Unauthorized" else "Forbidden"
    return ResponseEntity
      .status(status)
      .body(
        ErrorResponse(
          status = status,
          userMessage = "$reason: ${e.message}",
          developerMessage = e.message,
        ),
      ).also { log.debug("{} returned: {}", status.value(), e.message) }
  }

  @ExceptionHandler(ResponseStatusException::class)
  fun handleResponseStatusException(e: ResponseStatusException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(e.statusCode)
    .body(
      ErrorResponse(
        status = e.statusCode.value(),
        userMessage = e.reason,
        developerMessage = e.message,
      ),
    ).also { log.info("Response status exception: {}", e.message) }

  @ExceptionHandler(Exception::class)
  fun handleException(e: Exception): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(INTERNAL_SERVER_ERROR)
    .body(
      ErrorResponse(
        status = INTERNAL_SERVER_ERROR,
        userMessage = "Unexpected error: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.error("Unexpected exception", e) }

  @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
  fun handleMethodNotAllowedException(e: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(METHOD_NOT_ALLOWED)
    .body(
      ErrorResponse(
        status = METHOD_NOT_ALLOWED,
        userMessage = "HTTP method ${e.method} is not supported for this endpoint.",
        developerMessage = e.message,
      ),
    ).also { log.error("Endpoint called with the wrong method exception", e) }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
