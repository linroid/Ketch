package com.linroid.ketch.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavAuthTest {
  @Test
  fun parse_severalChallengesInOneHeader() {
    val challenges = Challenge.parse(
      listOf(
        "Digest realm=\"a, b\", qop=\"auth,auth-int\", nonce=\"n1\", algorithm=SHA-256, " +
          "Basic realm=\"files\", charset=\"UTF-8\"",
      ),
    )
    assertEquals(listOf("Digest", "Basic"), challenges.map { it.scheme })
    assertEquals("a, b", challenges[0].params["realm"])
    assertEquals("auth,auth-int", challenges[0].params["qop"])
    assertEquals("SHA-256", challenges[0].params["algorithm"])
    assertEquals("files", challenges[1].params["realm"])
    assertEquals("UTF-8", challenges[1].params["charset"])
  }

  @Test
  fun parse_headersTokensAndEscapes() {
    val challenges = Challenge.parse(
      listOf("Negotiate YII=", "Bearer", "Basic REALM=\"say \\\"hi\\\"\""),
    )
    assertEquals(listOf("Negotiate", "Bearer", "Basic"), challenges.map { it.scheme })
    assertEquals("YII=", challenges[0].params[""])
    assertTrue(challenges[1].params.isEmpty())
    assertEquals("say \"hi\"", challenges[2].params["realm"])
  }

  @Test
  fun digest_rfc7616Md5() {
    val (auth, answer) = digestOf(
      "Digest realm=\"http-auth@example.org\", qop=\"auth, auth-int\", algorithm=MD5, " +
        "nonce=\"7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v\", " +
        "opaque=\"FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS\"",
      Credentials("Mufasa", "Circle of Life"),
    )
    val header = auth.digest(answer, "GET", "/dir/index.html", RFC7616_CNONCE)
    val params = Challenge.parse(listOf(header)).single().params
    assertEquals("8ca523f5e9506fed4657c9700eebdbec", params["response"])
    assertEquals("Mufasa", params["username"])
    assertEquals("/dir/index.html", params["uri"])
    assertEquals("auth", params["qop"])
    assertEquals("00000001", params["nc"])
    assertEquals("FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS", params["opaque"])
  }

  @Test
  fun digest_rfc7616Sha256() {
    val (auth, answer) = digestOf(
      "Digest realm=\"http-auth@example.org\", qop=\"auth, auth-int\", algorithm=SHA-256, " +
        "nonce=\"7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v\", " +
        "opaque=\"FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS\"",
      Credentials("Mufasa", "Circle of Life"),
    )
    val header = auth.digest(answer, "GET", "/dir/index.html", RFC7616_CNONCE)
    assertEquals(
      "753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1",
      Challenge.parse(listOf(header)).single().params["response"],
    )
  }

  @Test
  fun digest_rfc2617() {
    val (auth, answer) = digestOf(
      "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", " +
        "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"",
      Credentials("Mufasa", "Circle Of Life"),
    )
    val header = auth.digest(answer, "GET", "/dir/index.html", "0a4f113b")
    assertEquals(
      "6629fae49393a05397450978507c4ef1",
      Challenge.parse(listOf(header)).single().params["response"],
    )
  }

  @Test
  fun digest_countsRequestsPerNonce() {
    val (auth, answer) = digestOf("Digest realm=\"r\", nonce=\"n\", qop=\"auth\"", CREDENTIALS)
    auth.digest(answer, "GET", "/", "c")
    val second = auth.digest(answer, "GET", "/", "c")
    assertEquals("00000002", Challenge.parse(listOf(second)).single().params["nc"])
  }

  @Test
  fun digest_withoutQop_usesRfc2069() {
    val (auth, answer) = digestOf("Digest realm=\"r\", nonce=\"n\"", CREDENTIALS)
    val params = Challenge.parse(listOf(auth.digest(answer, "GET", "/", "c"))).single().params
    assertNull(params["qop"])
    assertNull(params["nc"])
    // MD5("ha1:n:ha2") with ha1 = MD5("user:r:secret") and ha2 = MD5("GET:/").
    assertEquals(32, params["response"]?.length)
  }

  @Test
  fun accept_prefersDigestSha256_thenMd5_thenBasic() {
    val auth = Authenticator(CREDENTIALS)
    assertTrue(
      auth.accept(
        Challenge.parse(
          listOf(
            "Basic realm=\"r\"",
            "Digest realm=\"r\", nonce=\"a\", algorithm=MD5, qop=\"auth\"",
            "Digest realm=\"r\", nonce=\"b\", algorithm=SHA-256, qop=\"auth\"",
          ),
        ),
      ),
    )
    assertTrue(auth.authorization("GET", "/")!!.contains("algorithm=SHA-256"))

    val md5 = Authenticator(CREDENTIALS)
    md5.accept(Challenge.parse(listOf("Basic realm=\"r\"", "Digest realm=\"r\", nonce=\"a\"")))
    assertEquals("Digest", md5.scheme)

    val basic = Authenticator(CREDENTIALS)
    basic.accept(Challenge.parse(listOf("Basic realm=\"r\"")))
    assertEquals("Basic dXNlcjpzZWNyZXQ=", basic.authorization("GET", "/"))
  }

  @Test
  fun accept_skipsUnsupportedDigests() {
    val auth = Authenticator(CREDENTIALS)
    assertFalse(
      auth.accept(
        Challenge.parse(
          listOf(
            "Digest realm=\"r\", nonce=\"a\", algorithm=SHA-512-256",
            "Digest realm=\"r\", nonce=\"a\", qop=\"auth-int\"",
            "Bearer",
          ),
        ),
      ),
    )
    assertNull(auth.authorization("GET", "/"))
  }

  @Test
  fun accept_sameChallengeAgain_meansRefused_butStaleDoesNot() {
    val auth = Authenticator(CREDENTIALS)
    val first = Challenge.parse(listOf("Digest realm=\"r\", nonce=\"a\", qop=\"auth\""))
    assertTrue(auth.accept(first))
    assertFalse(auth.accept(first))
    assertTrue(
      auth.accept(Challenge.parse(listOf("Digest realm=\"r\", nonce=\"a\", stale=TRUE"))),
    )
    assertTrue(auth.accept(Challenge.parse(listOf("Digest realm=\"r\", nonce=\"b\""))))

    val basic = Authenticator(CREDENTIALS)
    assertTrue(basic.accept(Challenge.parse(listOf("Basic realm=\"r\""))))
    assertFalse(basic.accept(Challenge.parse(listOf("Basic realm=\"r\""))))
  }

  private fun digestOf(
    challenge: String,
    credentials: Credentials,
  ): Pair<Authenticator, Authenticator.Answer.Digest> {
    val parsed = Challenge.parse(listOf(challenge)).single()
    val answer = Authenticator.Answer.Digest(parsed)
    assertIs<Authenticator.Answer.Digest>(answer)
    return Authenticator(credentials) to answer
  }

  private companion object {
    val CREDENTIALS = Credentials("user", "secret")
    const val RFC7616_CNONCE = "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ"
  }
}
