package eu.studio742.imago.core.immich

import eu.studio742.imago.core.model.ClientCertificate
import eu.studio742.imago.core.model.ClientCertificateInfo
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.Base64

/** A client certificate ready to be presented in a TLS handshake. */
class ClientKey(val privateKey: PrivateKey, val chain: List<X509Certificate>)

object ClientCertificates {
    /** Reads the file a person picked and keeps it as it will be stored; refuses what cannot work. */
    fun import(file: ByteArray, password: String, now: Long = System.currentTimeMillis()): ClientCertificate =
        ClientCertificate(Base64.getEncoder().encodeToString(file), password).also { load(it, enforceValidity = true, now = now) }

    /**
     * [enforceValidity] is for what is being added: an expired certificate is refused with its own
     * message. A saved one that expired later still loads, and the server's refusal is what shows.
     */
    fun load(certificate: ClientCertificate, enforceValidity: Boolean, now: Long = System.currentTimeMillis()): ClientKey {
        val bytes = try { Base64.getDecoder().decode(certificate.pkcs12) } catch (error: IllegalArgumentException) {
            throw UserMessageException(UserMessage.CERTIFICATE_UNREADABLE, cause = error)
        }
        val password = certificate.password.toCharArray()
        val store = try {
            KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(bytes), password) }
        } catch (error: IOException) {
            // The JDK and Android's say "password" in different words; a wrong one is the usual cause.
            val wrongPassword = error.cause is UnrecoverableKeyException || error.message.orEmpty().contains("password", ignoreCase = true)
            throw UserMessageException(if (wrongPassword) UserMessage.CERTIFICATE_BAD_PASSWORD else UserMessage.CERTIFICATE_UNREADABLE, cause = error)
        } catch (error: GeneralSecurityException) {
            throw UserMessageException(UserMessage.CERTIFICATE_UNREADABLE, cause = error)
        }
        val alias = store.aliases().toList().firstOrNull(store::isKeyEntry)
            ?: throw UserMessageException(UserMessage.CERTIFICATE_NO_KEY)
        val key = try { store.getKey(alias, password) as? PrivateKey } catch (error: UnrecoverableKeyException) {
            throw UserMessageException(UserMessage.CERTIFICATE_BAD_PASSWORD, cause = error)
        } ?: throw UserMessageException(UserMessage.CERTIFICATE_NO_KEY)
        val chain = store.getCertificateChain(alias).orEmpty().filterIsInstance<X509Certificate>()
        if (chain.isEmpty()) throw UserMessageException(UserMessage.CERTIFICATE_NO_KEY)
        if (enforceValidity) try { chain.first().checkValidity(java.util.Date(now)) }
        catch (error: CertificateExpiredException) { throw UserMessageException(UserMessage.CERTIFICATE_EXPIRED, cause = error) }
        catch (error: CertificateNotYetValidException) { throw UserMessageException(UserMessage.CERTIFICATE_EXPIRED, cause = error) }
        return ClientKey(key, chain)
    }

    fun inspect(certificate: ClientCertificate, now: Long = System.currentTimeMillis()): ClientCertificateInfo {
        val leaf = load(certificate, enforceValidity = false).chain.first()
        return ClientCertificateInfo(
            subject = commonName(leaf.subjectX500Principal.name),
            issuer = commonName(leaf.issuerX500Principal.name),
            notAfterMillis = leaf.notAfter.time,
            expired = leaf.notAfter.time < now,
        )
    }

    // `javax.naming.ldap.LdapName` is not on Android; the RFC 2253 name is read for its CN by hand.
    private fun commonName(name: String): String =
        Regex("(?:^|,)\\s*CN=((?:\\\\.|[^,\\\\])+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)
            ?.replace(Regex("\\\\(.)"), "$1")?.trim()?.takeIf { it.isNotEmpty() } ?: name
}
