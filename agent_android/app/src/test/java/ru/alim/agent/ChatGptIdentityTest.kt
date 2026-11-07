package ru.alim.agent

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class ChatGptIdentityTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun enc(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private val jwks: JSONObject get() {
        val pub = pair.public as RSAPublicKey
        return JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "test").put("kty", "RSA")
            .put("n", enc(pub.modulus.toByteArray())).put("e", enc(pub.publicExponent.toByteArray()))))
    }
    private fun token(exp: Long = 2000, aud: String = "client", nonce: String = "nonce"): String {
        val head = enc("""{"alg":"RS256","kid":"test"}""".toByteArray())
        val body = enc(JSONObject().put("iss", "https://auth.openai.com").put("sub", "user")
            .put("aud", aud).put("nonce", nonce).put("exp", exp).put("iat", 900).toString().toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update("$head.$body".toByteArray()) }.sign()
        return "$head.$body.${enc(sig)}"
    }
    @Test fun verifiesSignedIdentity() {
        assertEquals("user", ChatGptIdentity.validate(token(), "client", "nonce", jwks, 1000).getString("sub"))
    }
    @Test fun rejectsWrongAudienceNonceAndExpiry() {
        for (t in listOf(token(exp = 999), token(aud = "other"), token(nonce = "other"))) {
            assertTrue(runCatching { ChatGptIdentity.validate(t, "client", "nonce", jwks, 1000) }.isFailure)
        }
    }
    @Test fun rejectsTampering() {
        val parts = token().split('.').toMutableList()
        parts[1] = enc("""{"sub":"attacker"}""".toByteArray())
        assertTrue(runCatching { ChatGptIdentity.validate(parts.joinToString("."), "client", "nonce", jwks, 1000) }.isFailure)
    }
    @Test fun pkceMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptIdentity.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
}
