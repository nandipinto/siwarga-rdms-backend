package com.siwarga.rdms.service

import com.siwarga.rdms.domain.AppUser
import com.siwarga.rdms.domain.UserRole
import com.siwarga.rdms.errors.ConflictException
import com.siwarga.rdms.errors.NotFoundException
import com.siwarga.rdms.repository.AppUserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val appUserRepository: AppUserRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    fun list(): List<AppUser> = appUserRepository.findAll()

    fun get(id: UUID): AppUser = appUserRepository.findById(id).orElseThrow { NotFoundException("User $id not found") }

    @Transactional
    fun create(
        username: String,
        rawPassword: String,
        role: UserRole,
    ): AppUser {
        if (appUserRepository.existsByUsername(username)) {
            throw ConflictException("Username '$username' already exists")
        }
        return appUserRepository.save(
            AppUser(
                username = username,
                passwordHash = passwordEncoder.encode(rawPassword),
                role = role,
            ),
        )
    }

    @Transactional
    fun update(
        id: UUID,
        role: UserRole?,
        isActive: Boolean?,
        rawPassword: String?,
    ): AppUser {
        val user = get(id)
        if (role != null) user.role = role
        if (isActive != null) user.isActive = isActive
        if (!rawPassword.isNullOrBlank()) user.passwordHash = passwordEncoder.encode(rawPassword)
        return appUserRepository.save(user)
    }

    @Transactional
    fun delete(id: UUID) {
        val user = get(id)
        user.isActive = false // soft-disable (spec §3.5 is_active)
        appUserRepository.save(user)
    }
}
