package com.siwarga.rdms.web

import com.siwarga.rdms.service.UserService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val service: UserService,
) {
    @GetMapping
    fun list(): List<UserResponse> = service.list().map { it.toResponse() }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): UserResponse = service.get(id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: UserCreateRequest,
    ): UserResponse = service.create(req.username, req.password, req.role).toResponse()

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: UserUpdateRequest,
    ): UserResponse = service.update(id, req.role, req.isActive, req.password).toResponse()

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }
}
