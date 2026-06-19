package com.siwarga.rdms.web

import com.siwarga.rdms.calc.PaymentRejectedException
import com.siwarga.rdms.errors.BadRequestException
import com.siwarga.rdms.errors.ConflictException
import com.siwarga.rdms.errors.ForbiddenException
import com.siwarga.rdms.errors.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Maps exceptions to RFC 7807 Problem Detail responses (spec §5.1, §8). */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException): ProblemDetail = problem(HttpStatus.NOT_FOUND, "Resource not found", ex.message)

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ProblemDetail = problem(HttpStatus.CONFLICT, "Conflict", ex.message)

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(ex: BadRequestException): ProblemDetail = problem(HttpStatus.BAD_REQUEST, "Bad request", ex.message)

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(ex: ForbiddenException): ProblemDetail = problem(HttpStatus.FORBIDDEN, "Access denied", ex.message)

    @ExceptionHandler(PaymentRejectedException::class)
    fun handlePaymentRejected(ex: PaymentRejectedException): ProblemDetail = problem(HttpStatus.BAD_REQUEST, "Payment rejected", ex.message)

    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentials(ex: BadCredentialsException): ProblemDetail =
        problem(HttpStatus.UNAUTHORIZED, "Authentication failed", "Invalid username or password")

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, "Access denied", "You do not have permission to perform this action")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ProblemDetail {
        val detail = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid")
        val fieldErrors = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        detail.setProperty("errors", fieldErrors)
        return detail
    }

    /** Concurrent read-modify-write detected by @Version optimistic locking — client should retry. */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLock(ex: OptimisticLockingFailureException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "Conflict", "The resource was modified concurrently; please retry")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ProblemDetail =
        problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", ex.message)

    /** Malformed path/query value (e.g. a non-UUID id) — a client error, not a 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Bad request", "Parameter '${ex.name}' has an invalid value")

    /** Unparseable or missing request body. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(ex: HttpMessageNotReadableException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Bad request", "Request body is malformed or missing")

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ProblemDetail {
        // Log the real cause server-side; never leak internal exception messages to clients.
        log.error("Unhandled exception", ex)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred")
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String?,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail ?: title).apply { this.title = title }
}
