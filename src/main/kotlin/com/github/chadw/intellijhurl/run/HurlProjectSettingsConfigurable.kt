package com.github.chadw.intellijhurl.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JCheckBox

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

    private val hurlExecutableField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "Select Hurl Executable", "Choose the hurl executable path",
            project, FileChooserDescriptorFactory.createSingleFileDescriptor()
        )
    }

    private val testModeCheckbox = JCheckBox("Run in test mode (--test)")
    private val verboseCheckbox = JCheckBox("Verbose (--verbose)")
    private val veryVerboseCheckbox = JCheckBox("Very verbose (--very-verbose)")

    override fun createPanel(): DialogPanel = panel {
        group("Default Run Configuration Settings") {
            row("Default variables file:") {
                cell(variablesFileField).align(AlignX.FILL)
                    .comment("Default --variables-file path for new run configurations. Can be overridden per configuration.")
            }
            row("Default hurl executable:") {
                cell(hurlExecutableField).align(AlignX.FILL)
                    .comment("Path to hurl binary. Overrides system PATH lookup. Applied to all new run configurations.")
            }
            row {
                cell(testModeCheckbox)
                    .comment("Enables --test: treats each request as a test case and reports pass/fail. Recommended for CI.")
            }
            row {
                cell(verboseCheckbox)
                    .comment("Enables --verbose: prints request and response headers. Useful for debugging failed requests.")
            }
            row {
                cell(veryVerboseCheckbox)
                    .comment("Enables --very-verbose: prints headers and full response body. Use to inspect 4xx/5xx errors.")
            }
        }
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return variablesFileField.text != state.defaultVariablesFile ||
                hurlExecutableField.text != state.defaultHurlExecutable ||
                testModeCheckbox.isSelected != state.defaultTestMode ||
                verboseCheckbox.isSelected != state.defaultVerbose ||
                veryVerboseCheckbox.isSelected != state.defaultVeryVerbose
    }

    override fun apply() {
        val state = settings.state
        state.defaultVariablesFile = variablesFileField.text
        state.defaultHurlExecutable = hurlExecutableField.text
        state.defaultTestMode = testModeCheckbox.isSelected
        state.defaultVerbose = verboseCheckbox.isSelected
        state.defaultVeryVerbose = veryVerboseCheckbox.isSelected
    }

    override fun reset() {
        val state = settings.state
        variablesFileField.text = state.defaultVariablesFile
        hurlExecutableField.text = state.defaultHurlExecutable
        testModeCheckbox.isSelected = state.defaultTestMode
        verboseCheckbox.isSelected = state.defaultVerbose
        veryVerboseCheckbox.isSelected = state.defaultVeryVerbose
    }
}
