package com.sphynx.inference

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 9 M2: a .litertlm file is checked before it is copied
class LiteRtLmFileTest {
    private val ok = "LITERTLM".toByteArray() + ByteArray(8)

    @Test fun aRealHeaderPasses() {
        assertNull(LiteRtLmFile.checkHeader(ok, 2_600L * 1024 * 1024))
        assertNull(LiteRtLmFile.checkHeader(ok, -1))
    }

    @Test fun badFilesAreNamed() {
        assertTrue(LiteRtLmFile.checkHeader(ok, 4096)!!.contains("did not finish"))
        assertTrue(LiteRtLmFile.checkHeader("<!DOCTYPE html>".toByteArray(), 9000)!!.contains("web page"))
        assertTrue(LiteRtLmFile.checkHeader("version https://git-lfs.github.com/spec/v1".toByteArray(), 130)!!.contains("Git LFS"))
        assertTrue(LiteRtLmFile.checkHeader(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0), 9_000_000)!!.contains("zip"))
        assertTrue(LiteRtLmFile.checkHeader(ByteArray(0), 0)!!.contains("empty"))
        assertTrue(LiteRtLmFile.checkHeader("GGUF....".toByteArray(), 9_000_000)!!.contains("not a LiteRT-LM file"))
    }
}
