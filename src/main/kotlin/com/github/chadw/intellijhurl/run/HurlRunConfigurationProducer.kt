package com.github.chadw.intellijhurl.run

import com.github.chadw.intellijhurl.language.HurlFileType
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement

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
        val file = context.psiLocation?.containingFile ?: return false
        if (file.fileType != HurlFileType) return false
        return configuration.hurlFilePath == file.virtualFile?.path
    }

    override fun setupConfigurationFromContext(
        configuration: HurlRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val file = context.psiLocation?.containingFile ?: return false
        if (file.fileType != HurlFileType) return false

        configuration.hurlFilePath = file.virtualFile?.path
        configuration.name = file.name
        configuration.workingDirectory = context.project.basePath

        val defaultVarFile = HurlProjectSettings.getInstance(context.project).state.defaultVariablesFile
        if (defaultVarFile.isNotBlank()) {
            configuration.variablesFile = defaultVarFile
        }

        return true
    }
}
