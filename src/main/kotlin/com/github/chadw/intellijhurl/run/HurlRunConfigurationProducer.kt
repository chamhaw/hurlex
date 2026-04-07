package com.github.chadw.intellijhurl.run

import com.github.chadw.intellijhurl.language.HurlFileType
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement

private fun HurlRunConfiguration.applyProjectDefaults(project: com.intellij.openapi.project.Project) {
    val state = HurlProjectSettings.getInstance(project).state
    if (state.defaultVariablesFile.isNotBlank()) variablesFile = state.defaultVariablesFile
    testMode = state.defaultTestMode
    verbose = state.defaultVerbose
    veryVerbose = state.defaultVeryVerbose
}

class HurlRunConfigurationProducer : LazyRunConfigurationProducer<HurlRunConfiguration>() {

    private val cachedConfigurationFactory by lazy {
        ConfigurationTypeUtil.findConfigurationType(HurlConfigurationType::class.java)
            .configurationFactories.first()
    }

    override fun getConfigurationFactory(): ConfigurationFactory = cachedConfigurationFactory

    override fun isConfigurationFromContext(
        configuration: HurlRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val location = context.psiLocation ?: return false

        // 目录模式
        val dir = location as? com.intellij.psi.PsiDirectory
        if (dir != null) {
            return configuration.hurlFilePath == dir.virtualFile.path
        }

        // 文件模式
        val file = location.containingFile ?: return false
        if (file.fileType != HurlFileType) return false
        return configuration.hurlFilePath == file.virtualFile?.path
    }

    override fun setupConfigurationFromContext(
        configuration: HurlRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val location = context.psiLocation ?: return false

        // 目录模式
        val dir = location as? com.intellij.psi.PsiDirectory
        if (dir != null) {
            configuration.hurlFilePath = dir.virtualFile.path
            configuration.name = dir.name
        } else {
            // 文件模式
            val file = location.containingFile ?: return false
            if (file.fileType != HurlFileType) return false

            configuration.hurlFilePath = file.virtualFile?.path
            configuration.name = file.name
        }

        configuration.workingDirectory = context.project.basePath
        configuration.applyProjectDefaults(context.project)
        return true
    }
}
