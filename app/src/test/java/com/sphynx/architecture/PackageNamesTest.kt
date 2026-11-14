package com.sphynx.architecture

import com.sphynx.inference.LlamaJni
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

// Names the Kotlin compiler cannot check, because they are written as text: a wrong one builds fine and
// breaks only on the phone. Added with the package rename com.example.mlbbresearch -> com.sphynx
// (owner, 2026-11-14); it guards any later rename or move too.
class PackageNamesTest {

    private fun file(rel: String): File = listOf(File(rel), File("app/$rel")).first { it.isFile }

    // the code package (namespace): where BuildConfig is generated
    private val namespace = com.sphynx.BuildConfig::class.java.`package`!!.name

    @Test fun theCodePackageIsSphynx() {
        assertEquals("com.sphynx", namespace)
    }

    // llama.cpp bridge: Java_<package with _>_<Class>_<method> must match LlamaJni's native methods exactly,
    // or loading a GGUF model fails with UnsatisfiedLinkError at run time
    @Test fun theNativeFunctionNamesMatchLlamaJni() {
        val cls = LlamaJni::class.java       // the class literal does not load the native library
        val prefix = "Java_" + cls.name.replace('.', '_') + "_"
        val expected = cls.declaredMethods.filter { Modifier.isNative(it.modifiers) }.map { prefix + it.name }.toSet()
        val inCpp = Regex("""\b(Java_[A-Za-z0-9_]+)\s*\(""").findAll(file("src/main/cpp/llama_jni.cpp").readText())
            .map { it.groupValues[1] }.toSet()
        assertTrue("LlamaJni has no native methods?", expected.isNotEmpty())
        assertEquals(expected, inCpp)
    }

    // the shrinker keep rules name classes that exist (a stale name would let R8 strip the JNI bridge)
    @Test fun theProguardRulesNameRealClasses() {
        val names = Regex("""-keep\s+(?:class|interface)\s+([A-Za-z0-9_.$]+)""")
            .findAll(file("proguard-rules.pro").readText()).map { it.groupValues[1] }
            .filter { it.contains('.') }.toList()
        assertTrue(names.isNotEmpty())
        for (n in names) Class.forName(n, false, javaClass.classLoader)
    }

    // every app, activity and service in the manifest is a real class of the namespace
    @Test fun theManifestComponentsExist() {
        val names = Regex("""<(?:application|activity|service|receiver|provider)\b[^>]*?android:name="([^"]+)"""")
            .findAll(file("src/main/AndroidManifest.xml").readText()).map { it.groupValues[1] }.toList()
        assertTrue(names.size >= 3)
        for (n in names) {
            val full = if (n.startsWith(".")) namespace + n else n
            assertTrue("$full is outside the code package", full.startsWith("$namespace."))
            Class.forName(full, false, javaClass.classLoader)
        }
    }
}
