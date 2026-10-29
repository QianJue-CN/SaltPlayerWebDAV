package com.saltplayermod.webdav

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class HostComposeCompatibilityTest {
    @Test fun compiledComposeAndCoroutineReferencesExistInInstalledHost() {
        val archive = System.getProperty("spw.hostArchive")
        assumeTrue("指定 -PhostArchive 可校验 UI 字节码与宿主的兼容性", !archive.isNullOrBlank())
        val parser = ClassFile.of()
        val missing = sortedSetOf<String>()
        ZipFile(archive).use { zip ->
            val models = HashMap<String, ClassModel?>()
            fun model(owner: String): ClassModel? = models.getOrPut(owner) {
                val entry = zip.getEntry("$owner.class")
                val bytes = if (entry != null) zip.getInputStream(entry).use { it.readAllBytes() }
                    else if (owner.startsWith("java/")) ClassLoader.getSystemResourceAsStream("$owner.class")?.use { it.readAllBytes() }
                    else null
                bytes?.let(parser::parse)
            }
            fun hasMember(owner: String, name: String, descriptor: String, method: Boolean, visited: MutableSet<String>): Boolean {
                if (!visited.add(owner)) return false
                val type = model(owner) ?: return false
                val found = if (method) type.methods().any { it.methodName().stringValue() == name && it.methodType().stringValue() == descriptor }
                    else type.fields().any { it.fieldName().stringValue() == name && it.fieldType().stringValue() == descriptor }
                if (found) return true
                val parents = type.interfaces().map { it.asInternalName() } + type.superclass().map { listOf(it.asInternalName()) }.orElse(emptyList())
                return parents.any { hasMember(it, name, descriptor, method, visited) }
            }
            fun check(owner: String, name: String, descriptor: String, method: Boolean) {
                if ((owner.startsWith("androidx/compose/") || owner.startsWith("kotlinx/coroutines/")) &&
                    !hasMember(owner, name, descriptor, method, HashSet())) missing += "$owner.$name$descriptor"
            }
            Files.walk(Path.of("build/classes/kotlin/main")).use { paths ->
                paths.filter { it.toString().endsWith(".class") }.forEach { file ->
                    for (method in parser.parse(Files.readAllBytes(file)).methods()) {
                        method.code().ifPresent { code -> code.forEach { instruction ->
                            when (instruction) {
                                is InvokeInstruction -> check(instruction.owner().asInternalName(), instruction.name().stringValue(), instruction.type().stringValue(), true)
                                is FieldInstruction -> check(instruction.owner().asInternalName(), instruction.name().stringValue(), instruction.type().stringValue(), false)
                                else -> Unit
                            }
                        } }
                    }
                }
            }
        }
        assertTrue("宿主缺少以下 UI / 协程接口：\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
