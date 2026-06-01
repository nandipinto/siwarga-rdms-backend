package com.siwarga.rdms.security

import com.siwarga.rdms.repository.AppUserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class AppUserDetailsService(
    private val repository: AppUserRepository,
) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        val user =
            repository.findByUsername(username)
                ?: throw UsernameNotFoundException("User '$username' not found")
        return User
            .builder()
            .username(user.username)
            .password(user.passwordHash)
            .disabled(!user.isActive)
            .authorities(SimpleGrantedAuthority("ROLE_${user.role.name}"))
            .build()
    }
}
