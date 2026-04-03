package com.github.chadw.intellijhurl.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel

class HurlProjectSettingsConfigurable(private val project: Project) : BoundConfigurable("Hurl") {

    private val settings by lazy { HurlProjectSettings.getInstance(project) }

    private val variablesFileField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "Select Default Variables File",
            "Choose a default variables file for new run configurations",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
        )
    }

    override fun createPanel(): DialogPanel = panel {
        group("Default Run Configuration Settings") {
            row("Default variables file:") {
                cell(variablesFileField).align(AlignX.FILL)
            }
        }
    }

    override fun isModified(): Boolean {
        return variablesFileField.text != settings.state.defaultVariablesFile
    }

    override fun apply() {
        settings.state.defaultVariablesFile = variablesFileField.text
    }

    override fun reset() {
        variablesFileField.text = settings.state.defaultVariablesFile
    }
}
