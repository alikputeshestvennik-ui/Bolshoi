package ru.alim.agent

import java.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

/** Pure OAuth/OIDC checks. No credentials are logged or accepted without verification. */
internal object ChatGptIdentity {
    fun random(): String = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun challenge(verifier: String): String = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun decode(value: String) = Base64.getUrlDecoder().decode(value)

    fun validate(token: String, clientId: String, nonce: String, jwks: JSONObject, now: Long = System.currentTimeMillis() / 1000): JSONObject {
        val parts = token.split('.')
        require(parts.size == 3) { "Некорректный ID token" }
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "Неподдерживаемая подпись ID token" }
        val keys = jwks.getJSONArray("keys")
        val key = (0 until keys.length()).map { keys.getJSONObject(it) }.singleOrNull {
            it.optString("kid") == header.optString("kid") && it.optString("kty") == "RSA"
        } ?: error("Ключ подписи OpenAI не найден")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decode(key.getString("n"))), BigInteger(1, decode(key.getString("e")))))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(signature.verify(decode(parts[2]))) { "Подпись OpenAI не прошла проверку" }
        val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        require(claims.optString("iss") == "https://auth.openai.com") { "Неверный issuer" }
        val aud = claims.opt("aud")
        val audiences = if (aud is org.json.JSONArray) (0 until aud.length()).map { aud.getString(it) } else listOf(aud as? String)
        require(clientId in audiences) { "ID token выдан другому приложению" }
        if (audiences.size > 1 || claims.has("azp")) require(claims.optString("azp") == clientId)
        require(claims.optLong("exp") > now) { "ID token истёк" }
        require(claims.optLong("iat") <= now + 60) { "ID token выдан в будущем" }
        if (claims.has("nbf")) require(claims.getLong("nbf") <= now + 60)
        require(nonce.isNotBlank() && MessageDigest.isEqual(claims.optString("nonce").toByteArray(), nonce.toByteArray())) { "Неверный nonce" }
        require(claims.optString("sub").isNotBlank()) { "Нет идентификатора аккаунта" }
        return claims
    }
}
