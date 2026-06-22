package com.siwarga.rdms.web

import com.siwarga.rdms.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
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

@Tag(name = "Users", description = "Application user management")
@ApiResponses(
    ApiResponse(responseCode = "401", description = "Not authenticated"),
    ApiResponse(responseCode = "403", description = "Administrator role required"),
)
@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val service: UserService,
) {
    @Operation(summary = "List users")
    @ApiResponse(responseCode = "200", description = "User list")
    @GetMapping
    fun list(): List<UserResponse> = service.list().map { it.toResponse() }

    @Operation(summary = "Get user by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "User found"),
        ApiResponse(responseCode = "404", description = "User not found"),
    )
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): UserResponse = service.get(id).toResponse()

    @Operation(summary = "Create user")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "User created"),
        ApiResponse(responseCode = "400", description = "Validation failed"),
        ApiResponse(responseCode = "409", description = "Username already exists"),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: UserCreateRequest,
    ): UserResponse = service.create(req.username, req.password, req.role, req.rtId).toResponse()

    @Operation(summary = "Update user")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "User updated"),
        ApiResponse(responseCode = "404", description = "User not found"),
    )
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: UserUpdateRequest,
    ): UserResponse = service.update(id, req.role, req.isActive, req.password, req.rtId).toResponse()

    @Operation(summary = "Delete user")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "User deleted"),
        ApiResponse(responseCode = "404", description = "User not found"),
    )
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }
}
