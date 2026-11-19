package com.quirogaj.vistio.data

import java.net.URL
import java.security.MessageDigest
import java.util.UUID

internal object CameraDigest {
    private val parameter = Regex("""([\w-]+)\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^,\s]+))""")
    private val quotedEscape = Regex("""\\(.)""")

    fun authorization(challenge: String, url: URL, username: String, password: String): String? {
        if (!challenge.startsWith("Digest ", ignoreCase = true)) return null
        val parameters = parameter.findAll(challenge.substringAfter(' ')).associate {
            it.groupValues[1].lowercase() to
                (it.groups[2]?.value?.replace(quotedEscape, "$1") ?: it.groupValues[3])
        }
        val realm = parameters["realm"] ?: return null
        val nonce = parameters["nonce"] ?: return null
        val algorithm = when (parameters["algorithm"]?.uppercase()) {
            null, "MD5" -> "MD5"
            "SHA-256" -> "SHA-256"
            else -> return null
        }
        val qop = parameters["qop"]
        if (qop != null && qop.split(',').none { it.trim().equals("auth", true) }) return null

        val target = url.path.ifEmpty { "/" } + (url.query?.let { "?$it" } ?: "")
        val cnonce = UUID.randomUUID().toString()
        val count = "00000001"
        fun hash(value: String): String = MessageDigest.getInstance(algorithm)
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val a1 = hash("$username:$realm:$password")
        val a2 = hash("GET:$target")
        val response = hash(if (qop == null) "$a1:$nonce:$a2" else "$a1:$nonce:$count:$cnonce:auth:$a2")
        return buildList {
            add("username=${quote(username)}")
            add("realm=${quote(realm)}")
            add("nonce=${quote(nonce)}")
            add("uri=${quote(target)}")
            add("response=${quote(response)}")
            add("algorithm=$algorithm")
            parameters["opaque"]?.let { add("opaque=${quote(it)}") }
            if (qop != null) {
                add("qop=auth")
                add("nc=$count")
                add("cnonce=${quote(cnonce)}")
            }
        }.joinToString(", ", prefix = "Digest ")
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
