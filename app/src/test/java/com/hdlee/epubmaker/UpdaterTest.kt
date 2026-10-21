package com.hdlee.epubmaker

import com.hdlee.epubmaker.update.Release
import com.hdlee.epubmaker.update.UpdateException
import com.hdlee.epubmaker.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class UpdaterTest {
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun tempFile(content: String) = File.createTempFile("apk", ".bin").apply {
        writeText(content)
        deleteOnExit()
    }

    private fun sign(kp: java.security.KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(data)
            sign()
        }

    private fun pubB64(kp: java.security.KeyPair) = Base64.getEncoder().encodeToString(kp.public.encoded)

    @Test
    fun validSignature_passes() {
        val kp = keyPair()
        val f = tempFile("official-build")
        Updater.verifySignature(f, sign(kp, f.readBytes()), pubB64(kp))
    }

    @Test
    fun tamperedFile_isRejected() {
        val kp = keyPair()
        val f = tempFile("official-build")
        val sig = sign(kp, f.readBytes())
        f.writeText("tampered-build")
        try {
            Updater.verifySignature(f, sig, pubB64(kp))
            fail("변조된 파일이 통과됨")
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("서명"))
        }
    }

    @Test
    fun otherSigner_isRejected() {
        val official = keyPair()
        val attacker = keyPair()
        val f = tempFile("official-build")
        try {
            Updater.verifySignature(f, sign(attacker, f.readBytes()), pubB64(official))
            fail("다른 키로 서명한 파일이 통과됨")
        } catch (e: UpdateException) {
            // expected
        }
    }

    @Test
    fun missingPublicKey_failsClosed() {
        val kp = keyPair()
        val f = tempFile("official-build")
        try {
            Updater.verifySignature(f, sign(kp, f.readBytes()), "")
            fail("공개키가 없는데 통과됨")
        } catch (e: UpdateException) {
            // expected
        }
    }

    @Test
    fun versionCompare() {
        assertTrue(Updater.compareVersions(Updater.parseVersion("v1.2.0"), Updater.parseVersion("1.1.9")) > 0)
        assertEquals(0, Updater.compareVersions(Updater.parseVersion("v1.0"), Updater.parseVersion("1.0.0")))
        val r = Release("v1.0.1", Updater.parseVersion("v1.0.1"), "", "", emptyMap())
        assertTrue(Updater.isNewer(r, "1.0.0"))
        assertFalse(Updater.isNewer(r, "1.0.1"))
    }
}
