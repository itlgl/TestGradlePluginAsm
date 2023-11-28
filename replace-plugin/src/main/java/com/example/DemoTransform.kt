package com.example

import com.android.build.api.transform.Format
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.utils.FileUtils
import com.google.common.io.Files

class DemoTransform: Transform() {

    override fun getName(): String {
        return "DemoTransform"
    }

    override fun getInputTypes(): MutableSet<QualifiedContent.ContentType> {
        return TransformManager.CONTENT_CLASS
    }

    override fun getScopes(): MutableSet<in QualifiedContent.Scope> {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    override fun isIncremental(): Boolean {
        return false
    }

    override fun transform(transformInvocation: TransformInvocation) {
        println("DemoTransform --- transform")
        transformInvocation.inputs.forEach { transformInput ->
            transformInput.directoryInputs.forEach { directoryInput ->
                val inputDir = directoryInput.file
                val outputDir = transformInvocation.outputProvider.getContentLocation(
                    directoryInput.name,
                    directoryInput.contentTypes,
                    directoryInput.scopes,
                    Format.DIRECTORY
                )
                println("DemoTransform --- directoryInputs inputDir: $inputDir --- outputDir: $outputDir")
                if (outputDir.mkdirs() || outputDir.isDirectory) {
                    FileUtils.deleteRecursivelyIfExists(outputDir)
                    FileUtils.copyDirectory(inputDir, outputDir)
                }
            }

            transformInput.jarInputs.forEach { jarInput ->
                val inputJar = jarInput.file
                val outputJar = transformInvocation.outputProvider.getContentLocation(
                    jarInput.name,
                    jarInput.contentTypes,
                    jarInput.scopes,
                    Format.JAR
                )
                println("DemoTransform --- jarInputs inputDir: $inputJar --- outputDir: $outputJar")
                Files.createParentDirs(outputJar)
                outputJar.delete()
                inputJar.copyTo(outputJar)
            }
        }
    }
}