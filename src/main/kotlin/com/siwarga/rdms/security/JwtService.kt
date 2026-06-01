package com.siwarga.rdms.security

import com.siwarga.rdms.config.JwtProperties
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

@Service
class JwtService(
    private val props: JwtProperties,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(props.secret.toByteArray(StandardCharsets.UTF_8))

    fun generate(
        username: String,
        role: String,
    ): String {
        val now = Date()
        val expiry = Date(now.time + props.expiryMinutes * 60_000)
        return Jwts
            .builder()
            .subject(username)
            .claim("role", role)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(key)
            .compact()
    }

    /** Returns the username (subject) if the token is valid, else null. */
    fun validate(token: String): String? =
        runCatching {
            Jwts
                .parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload.subject
        }.getOrNull()
}
