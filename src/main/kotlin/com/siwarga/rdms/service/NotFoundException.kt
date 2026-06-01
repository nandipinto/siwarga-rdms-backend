package com.siwarga.rdms.service

/** Thrown when a referenced resource does not exist (mapped to HTTP 404 in the web layer). */
class NotFoundException(
    message: String,
) : RuntimeException(message)

/** Thrown for business-rule conflicts (mapped to HTTP 409). */
class ConflictException(
    message: String,
) : RuntimeException(message)
