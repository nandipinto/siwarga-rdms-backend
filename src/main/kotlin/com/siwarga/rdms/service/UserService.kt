package com.siwarga.rdms.service

import com.siwarga.rdms.domain.AppUser
import com.siwarga.rdms.domain.Rt
import com.siwarga.rdms.domain.UserRole
import com.siwarga.rdms.errors.BadRequestException
import com.siwarga.rdms.errors.ConflictException
import com.siwarga.rdms.errors.NotFoundException
import com.siwarga.rdms.repository.AppUserRepository
import com.siwarga.rdms.repository.RtRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val appUserRepository: AppUserRepository,
    private val rtRepository: RtRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    fun list(): List<AppUser> = appUserRepository.findAll()

    fun get(id: UUID): AppUser = appUserRepository.findById(id).orElseThrow { NotFoundException("User $id not found") }

    @Transactional
    fun create(
        username: String,
        rawPassword: String,
        role: UserRole,
        rtId: UUID? = null,
    ): AppUser {
        if (appUserRepository.existsByUsername(username)) {
            throw ConflictException("Username '$username' already exists")
        }
        // New users start active; resolve their RT against the §3.5 / §4.3 rules.
        val rt = resolveRt(role = role, active = true, rtIdParam = rtId, currentRt = null, selfId = null)
        return appUserRepository.save(
            AppUser(
                username = username,
                passwordHash = passwordEncoder.encode(rawPassword),
                role = role,
                rt = rt,
            ),
        )
    }

    @Transactional
    fun update(
        id: UUID,
        role: UserRole?,
        isActive: Boolean?,
        rawPassword: String?,
        rtId: UUID? = null,
    ): AppUser {
        val user = get(id)
        val targetRole = role ?: user.role
        val targetActive = isActive ?: user.isActive

        user.rt = resolveRt(targetRole, targetActive, rtId, user.rt, user.id)
        user.role = targetRole
        user.isActive = targetActive
        if (!rawPassword.isNullOrBlank()) user.passwordHash = passwordEncoder.encode(rawPassword)
        return appUserRepository.save(user)
    }

    @Transactional
    fun delete(id: UUID) {
        val user = get(id)
        user.isActive = false // soft-disable (spec §3.5 is_active)
        user.rt = null // deactivation releases the RT for handoff (spec §3.5)
        appUserRepository.save(user)
    }

    /**
     * Resolves the RT a user should hold given its target state, enforcing the §3.5 rules:
     * administrators and deactivated supervisors hold no RT; an active supervisor requires exactly
     * one RT, subject to the strict 1:1 (an RT already held by another user → 409).
     */
    private fun resolveRt(
        role: UserRole,
        active: Boolean,
        rtIdParam: UUID?,
        currentRt: Rt?,
        selfId: UUID?,
    ): Rt? {
        if (role == UserRole.ADMINISTRATOR) return null // admins are unscoped; ignore any rtId
        if (!active) return null // deactivated supervisor releases its RT

        val rtId =
            rtIdParam ?: currentRt?.id
                ?: throw BadRequestException("An active supervisor must be assigned an RT")
        val rt = rtRepository.findById(rtId).orElseThrow { NotFoundException("RT $rtId not found") }

        val holder = appUserRepository.findByRtId(rtId)
        if (holder != null && holder.id != selfId) {
            throw ConflictException("RT '${rt.rtCode}' is already assigned to supervisor '${holder.username}'")
        }
        return rt
    }
}
