package uk.gov.justice.digital.hmpps.templatepackagenameasync.config

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
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.reactive.resource.NoResourceFoundException
import org.springframework.web.server.MethodNotAllowedException
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

@RestControllerAdvice
class HmppsTemplateKotlinExceptionHandler {
  @ExceptionHandler(ValidationException::class)
  fun handleValidationException(e: ValidationException): Mono<ResponseEntity<ErrorResponse>> = Mono.just(
    ResponseEntity
      .status(BAD_REQUEST)
      .body(
        ErrorResponse(
          status = BAD_REQUEST,
          userMessage = "Validation failure: ${e.message}",
          developerMessage = e.message,
        ),
      ),
  ).doOnNext { log.info("Validation exception: {}", e.message) }

  @ExceptionHandler(NoResourceFoundException::class)
  fun handleNoResourceFoundException(e: NoResourceFoundException): Mono<ResponseEntity<ErrorResponse>> = Mono.just(
    ResponseEntity
      .status(NOT_FOUND)
      .body(
        ErrorResponse(
          status = NOT_FOUND,
          userMessage = "No resource found failure: ${e.message}",
          developerMessage = e.message,
        ),
      ),
  ).doOnNext { log.info("No resource found exception: {}", e.message) }

  @ExceptionHandler(AccessDeniedException::class)
  fun handleAccessDeniedException(e: AccessDeniedException): Mono<ResponseEntity<ErrorResponse>> = ReactiveSecurityContextHolder.getContext()
    .map { isAuthenticated(it.authentication) }
    // An unauthenticated (anonymous) caller hitting a @PreAuthorize-protected method on an otherwise
    // permitAll path has no credentials at all, so should receive a 401 rather than a 403 - mirroring
    // what Spring Security's ExceptionTranslationFilter would have done had this exception not been
    // handled here first.
    .defaultIfEmpty(false)
    .map { authenticated ->
      val status = if (authenticated) FORBIDDEN else UNAUTHORIZED
      ResponseEntity
        .status(status)
        .body(
          ErrorResponse(
            status = status,
            userMessage = "Forbidden: ${e.message}",
            developerMessage = e.message,
          ),
        )
    }
    .doOnNext { log.debug("{} returned: {}", it.statusCode.value(), e.message) }

  private fun isAuthenticated(authentication: Authentication?): Boolean = authentication != null && authentication !is AnonymousAuthenticationToken

  @ExceptionHandler(ResponseStatusException::class)
  fun handleResponseStatusException(e: ResponseStatusException): Mono<ResponseEntity<ErrorResponse>> = Mono.just(
    ResponseEntity
      .status(e.statusCode)
      .body(
        ErrorResponse(
          status = e.statusCode.value(),
          userMessage = e.reason,
          developerMessage = e.message,
        ),
      ),
  ).doOnNext { log.info("Response status exception: {}", e.message) }

  @ExceptionHandler(MethodNotAllowedException::class)
  fun handleMethodNotAllowedException(e: MethodNotAllowedException): Mono<ResponseEntity<ErrorResponse>> = Mono.just(
    ResponseEntity
      .status(METHOD_NOT_ALLOWED)
      .body(
        ErrorResponse(
          status = METHOD_NOT_ALLOWED,
          userMessage = "HTTP method ${e.httpMethod} is not supported for this endpoint.",
          developerMessage = e.message,
        ),
      ),
  ).doOnNext { log.error("Endpoint called with the wrong method exception: {}", e.message) }

  @ExceptionHandler(Exception::class)
  fun handleException(e: Exception): Mono<ResponseEntity<ErrorResponse>> = Mono.just(
    ResponseEntity
      .status(INTERNAL_SERVER_ERROR)
      .body(
        ErrorResponse(
          status = INTERNAL_SERVER_ERROR,
          userMessage = "Unexpected error: ${e.message}",
          developerMessage = e.message,
        ),
      ),
  ).doOnNext { log.error("Unexpected exception", e) }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
