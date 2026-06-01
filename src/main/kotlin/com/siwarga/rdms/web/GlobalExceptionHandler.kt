package com.siwarga.rdms.web

import com.siwarga.rdms.calc.PaymentRejectedException
import com.siwarga.rdms.service.ConflictException
import com.siwarga.rdms.service.NotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Maps exceptions to RFC 7807 Problem Detail responses (spec §5.1, §8). */
@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException): ProblemDetail = problem(HttpStatus.NOT_FOUND, "Resource not found", ex.message)

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ProblemDetail = problem(HttpStatus.CONFLICT, "Conflict", ex.message)

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

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ProblemDetail =
        problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", ex.message)

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ProblemDetail = problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", ex.message)

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String?,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail ?: title).apply { this.title = title }
}
