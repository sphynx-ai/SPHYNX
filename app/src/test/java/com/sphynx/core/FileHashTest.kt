package com.sphynx.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

// Phase 9 M2: the SHA-256 worked out during a copy
class FileHashTest {
    @Test fun knownHashes() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FileHash.hash(ByteArrayInputStream(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileHash.hash(ByteArrayInputStream("abc".toByteArray())))
    }

    @Test fun theCopyIsExactAndHashedOnce() {
        val data = ByteArray(3_000_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        var last = 0L
        val h = FileHash.copyAndHash(ByteArrayInputStream(data), out) { last = it }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(FileHash.hash(ByteArrayInputStream(data)), h)
        assertEquals(3_000_000L, last)
    }
}
