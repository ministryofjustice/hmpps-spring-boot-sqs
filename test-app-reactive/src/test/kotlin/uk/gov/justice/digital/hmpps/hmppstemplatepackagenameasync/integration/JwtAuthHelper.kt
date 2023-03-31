package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.stereotype.Component
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.Date
import java.util.UUID

@Component
class JwtAuthHelper {
  private val keyPair: KeyPair

  init {
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    keyPair = gen.generateKeyPair()
  }

  @Bean
  fun jwtDecoder(): ReactiveJwtDecoder = NimbusReactiveJwtDecoder.withPublicKey(keyPair.public as RSAPublicKey).build()

  internal fun createJwt(
    subject: String? = null,
    userId: String? = "${subject}_ID",
    scope: List<String>? = listOf(),
    roles: List<String>? = listOf(),
    expiryTime: Duration = Duration.ofHours(1),
    clientId: String = "prison-register-client",
    jwtId: String = UUID.randomUUID().toString(),
  ): String =
    mutableMapOf<String, Any>()
      .apply { subject?.let { subject -> this["user_name"] = subject } }
      .apply { this["client_id"] = clientId }
      .apply { userId?.let { userId -> this["user_id"] = userId } }
      .apply { roles?.let { roles -> this["authorities"] = roles } }
      .apply { scope?.let { scope -> this["scope"] = scope } }
      .let {
        Jwts.builder()
          .setId(jwtId)
          .setSubject(subject)
          .addClaims(it.toMap())
          .setExpiration(Date(System.currentTimeMillis() + expiryTime.toMillis()))
          .signWith(keyPair.private, SignatureAlgorithm.RS256)
          .compact()
      }
}
