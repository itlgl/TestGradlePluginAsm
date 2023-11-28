package com.example

import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.Format
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Status
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.utils.FileUtils
import com.google.common.io.Files
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ReplaceTransform: Transform() {
    override fun getName(): String {
        return "ReplaceTransform"
    }

    override fun getInputTypes(): MutableSet<QualifiedContent.ContentType> {
        return TransformManager.CONTENT_CLASS
    }

    override fun getScopes(): MutableSet<in QualifiedContent.Scope> {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    override fun isIncremental(): Boolean {
        return true
    }

    override fun transform(invocation: TransformInvocation) {
        val incremental = invocation.isIncremental
        val outputProvider = invocation.outputProvider

        if (!incremental) {
            outputProvider.deleteAll()
        }

        invocation.inputs.forEach { transformInput ->
            transformDirectoryInputs(transformInput, outputProvider, incremental)
            transformJarInputs(transformInput, outputProvider, incremental)
        }
    }

    private fun transformDirectoryInputs(
        transformInput: TransformInput,
        outputProvider: TransformOutputProvider,
        incremental: Boolean
    ) {
        transformInput.directoryInputs.forEach { directoryInput ->
            val inputDir = directoryInput.file
            val outputDir = outputProvider.getContentLocation(
                directoryInput.name,
                directoryInput.contentTypes,
                directoryInput.scopes,
                Format.DIRECTORY
            )
            println("ReplaceTransform --- directoryInputs inputDir: $inputDir --- outputDir: $outputDir")
            performTransformationForDirectoryInput(directoryInput, inputDir, outputDir, incremental)
        }
    }

    private fun performTransformationForDirectoryInput(
        directoryInput: DirectoryInput,
        inputDir: File,
        outputDir: File,
        incremental: Boolean
    ) {
        if (incremental) {
            directoryInput.changedFiles.forEach { key, value ->
                val inputFile = key
                val incrementalStatus = value
                //println("ReplaceTransform: incremental inputFile: $inputFile")
                when (incrementalStatus) {
                    Status.ADDED, Status.CHANGED -> {
                        transformFile(inputFile, inputDir, outputDir)
                    }
                    Status.REMOVED -> {
                        val outputFile = toOutputFile(outputDir, inputDir, inputFile)
                        FileUtils.deleteIfExists(outputFile)
                    }
                }
            }
        } else {
            FileUtils.getAllFiles(inputDir).forEach {
                //println("ReplaceTransform: non-incremental inputFile: $it")
                transformFile(it, inputDir, outputDir)
            }
        }
    }

    private fun toOutputFile(outputDir: File, inputDir: File, inputFile: File): File {
        return File(outputDir, FileUtils.relativePossiblyNonExistingPath(inputFile, inputDir))
    }

    private fun transformFile(inputFile: File, inputDir: File, outputDir: File) {
        if (!inputFile.isDirectory && inputFile.name.endsWith(".class")) {
            val outputFile = toOutputFile(outputDir, inputDir, inputFile)
            Files.createParentDirs(outputFile)
            instrumentClassFile(inputFile, outputFile)
        }
    }

    private fun instrumentClassFile(inputFile: File, outputFile: File) {
        println("ReplaceTransform: instrumentClassFile inputFile: $inputFile, outputFile: $outputFile")
        try {
            val inputBytes = inputFile.readBytes()
            val printMethod = inputFile.name == "MainActivity.class"
            val outputBytes = instrument(inputBytes, printMethod)
            outputFile.writeBytes(outputBytes)
        } catch (e: Exception) {
            println("ReplaceTransform: instrument error: \n${e.stackTraceToString()}")
            Files.copy(inputFile, outputFile)
        }
    }

    private fun instrument(inputBytes: ByteArray, printMethod: Boolean = false): ByteArray {
        val cr = ClassReader(inputBytes)
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val methodReplaceVisitor = MethodReplaceVisitor(
            Opcodes.ASM7,
            cw,
            "com/example/testgradlepluginasm/LiLei",
            "hello",
            "()Ljava/lang/String;",
            "com/example/testgradlepluginasm/LiGui",
            "hello",
            "()Ljava/lang/String;",
            Opcodes.INVOKESTATIC
        )
        if (printMethod) {
            methodReplaceVisitor.printMethod = true
        }
        cr.accept(methodReplaceVisitor, ClassReader.SKIP_FRAMES)
        return cw.toByteArray()
    }

    private fun transformJarInputs(
        transformInput: TransformInput,
        outputProvider: TransformOutputProvider,
        incremental: Boolean
    ) {
        transformInput.jarInputs.forEach { jarInput ->
            val inputJar = jarInput.file
            val outputJar = outputProvider.getContentLocation(
                jarInput.name,
                jarInput.contentTypes,
                jarInput.scopes,
                Format.JAR
            )
            println("ReplaceTransform --- jarInputs inputDir: $inputJar --- outputDir: $outputJar")
            performTransformationForJarInput(jarInput, inputJar, outputJar, incremental)
        }
    }

    private fun performTransformationForJarInput(
        jarInput: JarInput,
        inputJar: File,
        outputJar: File,
        incremental: Boolean
    ) {
        if (incremental) {
            when (jarInput.status) {
                Status.ADDED, Status.CHANGED -> {
                    transformJar(inputJar, outputJar)
                }
                Status.REMOVED -> {
                    FileUtils.deleteIfExists(outputJar)
                }
            }
        } else {
            transformJar(inputJar, outputJar)
        }
    }

    private fun transformJar(inputJar: File, outputJar: File) {
        Files.createParentDirs(outputJar)
        val inputZip = ZipFile(inputJar)
        val zis = ZipInputStream(inputJar.inputStream())
        val zos = ZipOutputStream(outputJar.outputStream())
        while (true) {
            val inEntry = zis.nextEntry ?: break
            val entryName = inEntry.name
            if (inEntry.isDirectory) {
                continue
            }
            val eis = inputZip.getInputStream(inEntry)
            var entryBytes = eis.readBytes()
            eis.close()
            val isClassFile = entryName.endsWith(".class")
            if (!isClassFile) {
                continue
            }
            try {
                entryBytes = instrument(entryBytes)
            } catch (_: Exception) {
            }
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(entryBytes)
            zos.closeEntry()
        }
        zis.close()
        zos.close()
    }
}
//class ReplaceTransform: BaseTransform() {
//
//    override fun modifyClass(byteArray: ByteArray): ByteArray {
//        try {
//            println("ReplaceTransform: ReplaceTransform --- modifyClass")
//            val classReader = ClassReader(byteArray)
//            val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES)
//
//            val api = Opcodes.ASM9
//            val classVisitor = MethodReplaceVisitor(
//                api, classWriter,
//                "com.example.testgradlepluginasm.LiLei",
//                "hello",
//                "()V",
//
//                "com.example.testgradlepluginasm.LiGui",
//                "hello",
//                "()V",
//                Opcodes.INVOKESTATIC
//            )
//
//            classReader.accept(classVisitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
//            val replaceByteArray = classWriter.toByteArray()
//            println("ReplaceTransform: replace byteArray size: ${replaceByteArray.size}")
//            return replaceByteArray
//        } catch (e: Exception) {
//            Log.log(e.stackTraceToString())
//            throw e
//        }
//    }
//
//}