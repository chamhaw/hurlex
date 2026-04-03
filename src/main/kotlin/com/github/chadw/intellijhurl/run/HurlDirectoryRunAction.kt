package com.github.chadw.intellijhurl.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.actionSystem.CommonDataKeys

class HurlDirectoryRunAction : AnAction("Run Hurl Files") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (!dir.isDirectory) return

        val runManager = RunManager.getInstance(project)
        val factory = ConfigurationTypeUtil.findConfigurationType(HurlConfigurationType::class.java)
            .configurationFactories.first()
        val settings = runManager.createConfiguration(dir.name, factory)
        val config = settings.configuration as HurlRunConfiguration

        config.hurlFilePath = dir.path
        config.workingDirectory = project.basePath

        val defaultVarFile = HurlProjectSettings.getInstance(project).state.defaultVariablesFile
        if (defaultVarFile.isNotBlank()) {
            config.variablesFile = defaultVarFile
        }

        val executor = DefaultRunExecutor.getRunExecutorInstance()
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }
}
