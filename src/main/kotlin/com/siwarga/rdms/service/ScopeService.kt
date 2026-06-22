package com.siwarga.rdms.service

import com.siwarga.rdms.domain.AppUser
import com.siwarga.rdms.domain.House
import com.siwarga.rdms.domain.UserRole
import com.siwarga.rdms.errors.ForbiddenException
import com.siwarga.rdms.repository.AppUserRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Resolves the authenticated caller's RT scope and enforces row-level confinement for supervisors
 * (spec §4.3). A supervisor's effective RT is derived solely from their own user record — never from
 * request parameters. Administrators are unscoped (cluster-wide).
 */
@Service
class ScopeService(
    private val appUserRepository: AppUserRepository,
) {
    /** The authenticated user, or 403 if the principal cannot be resolved. */
    fun currentUser(): AppUser {
        val username =
            SecurityContextHolder.getContext().authentication?.name
                ?: throw ForbiddenException("No authenticated user")
        return appUserRepository.findByUsername(username)
            ?: throw ForbiddenException("Authenticated user '$username' not found")
    }

    /**
     * RT a supervisor is confined to. `null` for administrators (unscoped). A supervisor with no RT
     * assigned (a data error per §3.5) is denied — fail closed — rather than seeing cluster-wide data.
     */
    fun supervisorRtId(): UUID? {
        val user = currentUser()
        if (user.role != UserRole.SUPERVISOR) return null
        return user.rt?.id ?: throw ForbiddenException("Supervisor has no RT assigned")
    }

    /**
     * Effective RT filter for list/report/dashboard queries: a supervisor's own RT always overrides
     * any client-supplied value; an administrator keeps the requested filter (which may be null).
     */
    fun effectiveRtId(requestedRtId: UUID?): UUID? = supervisorRtId() ?: requestedRtId

    /** Throws 403 when a supervisor accesses a resource whose house is outside their RT. */
    fun assertHouseInScope(house: House) {
        val rtId = supervisorRtId() ?: return
        if (house.rt.id != rtId) {
            throw ForbiddenException("Resource is outside your RT")
        }
    }
}
