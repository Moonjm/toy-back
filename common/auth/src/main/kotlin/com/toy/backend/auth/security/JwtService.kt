package com.toy.backend.auth.security

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.util.Date

@Service
@Transactional(readOnly = true)
class JwtService(
    private val jwtProperties: JwtProperties,
) {
    private fun signingKey() = jwtProperties.secret.toByteArray(StandardCharsets.UTF_8)

    fun createAccessToken(
        username: String,
        authority: String,
    ): String {
        val now = Date()
        val expiry = Date(now.time + jwtProperties.accessTokenExpireMinutes * 60_000)
        val claimsSet =
            JWTClaimsSet
                .Builder()
                .subject(username)
                .claim("authority", authority)
                .issueTime(now)
                .expirationTime(expiry)
                .build()
        val signedJWT = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claimsSet)
        signedJWT.sign(MACSigner(signingKey()))
        return signedJWT.serialize()
    }

    fun parseClaims(token: String): JWTClaimsSet {
        val signedJWT = SignedJWT.parse(token)
        if (!signedJWT.verify(MACVerifier(signingKey()))) {
            throw JOSEException("JWT signature verification failed")
        }
        val claims = signedJWT.jwtClaimsSet
        if (claims.expirationTime != null && claims.expirationTime.before(Date())) {
            throw JOSEException("JWT has expired")
        }
        return claims
    }

    fun accessTokenMaxAgeSeconds(): Long = jwtProperties.accessTokenExpireMinutes * 60

    fun refreshTokenMaxAgeSeconds(): Long = jwtProperties.refreshTokenExpireDays * 24 * 60 * 60
}
