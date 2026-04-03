package com.github.chadw.intellijhurl.run

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@State(name = "HurlProjectSettings", storages = [Storage("hurl.xml")])
@Service(Service.Level.PROJECT)
class HurlProjectSettings : PersistentStateComponent<HurlProjectSettings.State> {

    data class State(
        var defaultVariablesFile: String = "",
        var defaultHurlExecutable: String = "",
        var defaultTestMode: Boolean = false,
        var defaultVerbose: Boolean = false,
        var defaultVeryVerbose: Boolean = false
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): HurlProjectSettings =
            project.service<HurlProjectSettings>()
    }
}
