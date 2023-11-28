package com.example

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class ReplacePlugin: Plugin<Project> {

    override fun apply(project: Project) {
        println("ReplacePlugin ---- apply")
        val appExtension = project.extensions.getByType(AppExtension::class.java)
        appExtension.registerTransform(DemoTransform())
        appExtension.registerTransform(ReplaceTransform())
    }
}