package com.github.chadw.intellijhurl.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.io.FileUtil
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import com.github.chadw.intellijhurl.run.HurlProjectSettings

class HurlCommandLineState(
    private val configuration: HurlRunConfiguration,
    environment: ExecutionEnvironment
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val filePath = configuration.hurlFilePath
        if (!filePath.isNullOrBlank() && File(filePath).isFile) {
            val fileContent = FileUtil.loadFile(File(filePath), Charsets.UTF_8)
            if (HurlAnnotationParser.hasAnnotations(fileContent)) {
                return startAnnotatedProcess(fileContent)
            }
        }
        return startOriginalProcess()
    }

    // ========================== Original (non-annotated) execution ==========================

    private fun startOriginalProcess(): ProcessHandler {
        val customPath = configuration.hurlExecutable?.takeIf { it.isNotBlank() }
            ?: HurlProjectSettings.getInstance(environment.project).state.defaultHurlExecutable.takeIf { it.isNotBlank() }
        val location = HurlExecutableUtil.findHurl(customPath)

        val commandLine = buildBaseCommand(location)

        val filePath = configuration.hurlFilePath
        if (!filePath.isNullOrBlank()) {
            commandLine.addParameter(maybeWslPath(filePath, location))
        }

        addCommonFlags(commandLine, location)
        configureWorkingDir(commandLine)
        configureEnvVars(commandLine)
        commandLine.addParameter("--color")

        val processHandler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(processHandler)
        return processHandler
    }

    // ========================== Annotated (section-based) execution ==========================

    private fun startAnnotatedProcess(fileContent: String): ProcessHandler {
        val sections = HurlAnnotationParser.parse(fileContent)
        val customPath = configuration.hurlExecutable?.takeIf { it.isNotBlank() }
            ?: HurlProjectSettings.getInstance(environment.project).state.defaultHurlExecutable.takeIf { it.isNotBlank() }
        val location = HurlExecutableUtil.findHurl(customPath)

        // Create a dummy process handler to write output to the console.
        // We run the first section (setup or test) as the "main" process,
        // but we need a handler to stream all output. Use a trivial no-op process.
        // Actually, we'll run each section synchronously and stream their output
        // to a single process handler.

        // Create temp directory for section files and reports
        val tempDir = Files.createTempDirectory("hurl-sections-")
        val tempDirFile = tempDir.toFile()

        try {
            // Write section files
            val sectionFiles = mutableMapOf<SectionType, File>()
            for (section in sections) {
                val fileName = when (section.type) {
                    SectionType.SETUP -> "setup.hurl"
                    SectionType.TEST -> "test.hurl"
                    SectionType.TEARDOWN -> "teardown.hurl"
                }
                val file = File(tempDirFile, fileName)
                FileUtil.writeToFile(file, section.content)
                sectionFiles[section.type] = file
            }

            val setupFile = sectionFiles[SectionType.SETUP]
            val testFile = sectionFiles[SectionType.TEST]
            val teardownFile = sectionFiles[SectionType.TEARDOWN]

            // We need a ProcessHandler for IntelliJ's run framework.
            // Run the first real section as the "main" process, then chain others.
            // Strategy: create a wrapper shell command that runs all sections sequentially.
            // This allows the IntelliJ process handler to capture everything naturally.

            val scriptFile = File(tempDirFile, "run-sections.sh")
            val script = buildSectionScript(
                location, setupFile, testFile, teardownFile, tempDirFile
            )
            FileUtil.writeToFile(scriptFile, script)
            scriptFile.setExecutable(true)

            val commandLine = GeneralCommandLine("/bin/sh", scriptFile.absolutePath)
            configureWorkingDir(commandLine)
            configureEnvVars(commandLine)

            val processHandler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine)
            ProcessTerminatedListener.attach(processHandler)

            // Clean up temp dir when process terminates
            processHandler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                    try {
                        FileUtil.delete(tempDirFile)
                    } catch (_: Exception) {}
                }
            })

            return processHandler
        } catch (e: Exception) {
            // On failure, clean up and fall back to original execution
            try { FileUtil.delete(tempDirFile) } catch (_: Exception) {}
            return startOriginalProcess()
        }
    }

    private fun buildSectionScript(
        location: HurlExecutableUtil.HurlLocation?,
        setupFile: File?,
        testFile: File?,
        teardownFile: File?,
        tempDir: File
    ): String {
        val sb = StringBuilder()
        sb.appendLine("#!/bin/sh")
        sb.appendLine("set -e")
        sb.appendLine()

        val hurlCmd = buildHurlCmdString(location)

        // Common flags as a string
        val commonFlags = buildCommonFlagsString(location)

        val setupReportDir = File(tempDir, "setup-report")
        val testReportDir = File(tempDir, "test-report")

        // --- SETUP ---
        if (setupFile != null) {
            sb.appendLine("# === Setup ===")
            sb.appendLine("echo ''")
            sb.appendLine("echo '[Setup] Running...'")
            sb.appendLine("echo ''")
            sb.appendLine("set +e")
            sb.appendLine("$hurlCmd ${shellEscape(setupFile.absolutePath)} $commonFlags --report-json ${shellEscape(setupReportDir.absolutePath)} --color")
            sb.appendLine("SETUP_EXIT=\$?")
            sb.appendLine("set -e")
            sb.appendLine("if [ \$SETUP_EXIT -ne 0 ]; then")
            sb.appendLine("  echo ''")
            sb.appendLine("  echo \"[Setup] FAILED (exit code \$SETUP_EXIT), aborting execution.\"")
            sb.appendLine("  exit \$SETUP_EXIT")
            sb.appendLine("fi")
            sb.appendLine()
        }

        // We need a capture parser function in the script
        sb.appendLine("# Parse captures from report.json as --variable args")
        sb.appendLine("parse_captures() {")
        sb.appendLine("  if [ ! -f \"\$1\" ]; then return 0; fi")
        // Use python if available, otherwise use a simple awk/grep approach
        sb.appendLine("  if command -v python3 >/dev/null 2>&1; then")
        sb.appendLine("    python3 -c \"")
        sb.appendLine("import json, sys")
        sb.appendLine("try:")
        sb.appendLine("    data = json.load(open(sys.argv[1]))")
        sb.appendLine("    for file_entry in data:")
        sb.appendLine("        for entry in file_entry.get('entries', []):")
        sb.appendLine("            captures = entry.get('captures', {})")
        sb.appendLine("            if isinstance(captures, dict):")
        sb.appendLine("                for name, value in captures.items():")
        sb.appendLine("                    if isinstance(value, bool):")
        sb.appendLine("                        value = str(value).lower()")
        sb.appendLine("                    print('--variable')")
        sb.appendLine("                    print(str(name) + '=' + str(value))")
        sb.appendLine("except: pass")
        sb.appendLine("\" \"\$1\"")
        sb.appendLine("  fi")
        sb.appendLine("}")
        sb.appendLine()

        // --- TEST ---
        if (testFile != null) {
            sb.appendLine("# === Test ===")
            sb.appendLine("echo ''")
            sb.appendLine("echo '[Test] Running...'")
            sb.appendLine("echo ''")

            // Collect captures from setup
            if (setupFile != null) {
                sb.appendLine("SETUP_CAPTURES=\$(parse_captures ${shellEscape(setupReportDir.absolutePath)}/report.json)")
            } else {
                sb.appendLine("SETUP_CAPTURES=")
            }

            sb.appendLine("set +e")
            sb.appendLine("$hurlCmd ${shellEscape(testFile.absolutePath)} $commonFlags --continue-on-error --report-json ${shellEscape(testReportDir.absolutePath)} \$SETUP_CAPTURES --color")
            sb.appendLine("TEST_EXIT=\$?")
            sb.appendLine("set -e")
            sb.appendLine()
        } else {
            sb.appendLine("TEST_EXIT=0")
        }

        // --- TEARDOWN ---
        if (teardownFile != null) {
            sb.appendLine("# === Teardown ===")
            sb.appendLine("echo ''")
            sb.appendLine("echo '[Teardown] Running...'")
            sb.appendLine("echo ''")

            // Merge captures from setup + test (test overrides setup)
            sb.appendLine("ALL_CAPTURES=")
            if (setupFile != null) {
                sb.appendLine("SETUP_CAPS=\$(parse_captures ${shellEscape(setupReportDir.absolutePath)}/report.json)")
                sb.appendLine("ALL_CAPTURES=\"\$SETUP_CAPS\"")
            }
            if (testFile != null) {
                sb.appendLine("TEST_CAPS=\$(parse_captures ${shellEscape(testReportDir.absolutePath)}/report.json)")
                sb.appendLine("if [ -n \"\$TEST_CAPS\" ]; then")
                sb.appendLine("  ALL_CAPTURES=\"\$ALL_CAPTURES \$TEST_CAPS\"")
                sb.appendLine("fi")
            }

            sb.appendLine("set +e")
            sb.appendLine("$hurlCmd ${shellEscape(teardownFile.absolutePath)} $commonFlags \$ALL_CAPTURES --color")
            sb.appendLine("TEARDOWN_EXIT=\$?")
            sb.appendLine("set -e")
            sb.appendLine("if [ \$TEARDOWN_EXIT -ne 0 ]; then")
            sb.appendLine("  echo ''")
            sb.appendLine("  echo \"[Teardown] FAILED (exit code \$TEARDOWN_EXIT)\"")
            sb.appendLine("fi")
            sb.appendLine()
        }

        // Final exit code = test exit code
        sb.appendLine("exit \${TEST_EXIT:-0}")
        return sb.toString()
    }

    private fun buildHurlCmdString(location: HurlExecutableUtil.HurlLocation?): String {
        return if (location != null) {
            location.buildCommand().joinToString(" ") { shellEscape(it) }
        } else {
            "hurl"
        }
    }

    private fun buildCommonFlagsString(location: HurlExecutableUtil.HurlLocation?): String {
        val flags = mutableListOf<String>()

        // Test mode
        if (configuration.testMode) {
            flags.add("--test")
        }

        // Verbose flags
        if (configuration.veryVerbose) {
            flags.add("--very-verbose")
        } else if (configuration.verbose) {
            flags.add("--verbose")
        }

        // Additional options
        val options = configuration.hurlOptions
        if (!options.isNullOrBlank()) {
            options.split("\\s+".toRegex()).filter { it.isNotBlank() }.forEach {
                flags.add(it)
            }
        }

        // Variables file
        val variablesFile = configuration.variablesFile
        if (!variablesFile.isNullOrBlank()) {
            flags.add("--variables-file")
            flags.add(maybeWslPath(variablesFile, location))
        }

        return flags.joinToString(" ") { shellEscape(it) }
    }

    // ========================== Shared helpers ==========================

    private fun buildBaseCommand(location: HurlExecutableUtil.HurlLocation?): GeneralCommandLine {
        return if (location != null) {
            GeneralCommandLine(location.buildCommand())
        } else {
            GeneralCommandLine("hurl")
        }
    }

    private fun addCommonFlags(commandLine: GeneralCommandLine, location: HurlExecutableUtil.HurlLocation?) {
        if (configuration.testMode) {
            commandLine.addParameter("--test")
        }

        if (configuration.veryVerbose) {
            commandLine.addParameter("--very-verbose")
        } else if (configuration.verbose) {
            commandLine.addParameter("--verbose")
        }

        val options = configuration.hurlOptions
        if (!options.isNullOrBlank()) {
            options.split("\\s+".toRegex()).filter { it.isNotBlank() }.forEach {
                commandLine.addParameter(it)
            }
        }

        val variablesFile = configuration.variablesFile
        if (!variablesFile.isNullOrBlank()) {
            commandLine.addParameter("--variables-file")
            commandLine.addParameter(maybeWslPath(variablesFile, location))
        }
    }

    private fun configureWorkingDir(commandLine: GeneralCommandLine) {
        val workDir = configuration.workingDirectory
        if (!workDir.isNullOrBlank()) {
            commandLine.workDirectory = File(workDir)
        } else {
            val projectDir = environment.project.basePath
            if (projectDir != null) {
                commandLine.workDirectory = File(projectDir)
            }
        }
    }

    private fun configureEnvVars(commandLine: GeneralCommandLine) {
        val envVars = configuration.environmentVariables
        if (!envVars.isNullOrBlank()) {
            envVars.split(";").filter { it.contains("=") }.forEach { pair ->
                val key = pair.substringBefore("=").trim()
                val value = pair.substringAfter("=").trim()
                if (key.isNotBlank()) {
                    commandLine.environment[key] = value
                }
            }
        }
    }

    private fun maybeWslPath(path: String, location: HurlExecutableUtil.HurlLocation?): String {
        return if (location?.prefixArgs?.contains("wsl.exe") == true) {
            toWslPath(path)
        } else {
            path
        }
    }

    private fun shellEscape(s: String): String {
        if (s.isEmpty()) return "''"
        // If string contains no special chars, return as-is
        if (s.matches(Regex("[A-Za-z0-9_./:=@-]+"))) return s
        // Otherwise wrap in single quotes, escaping existing single quotes
        return "'" + s.replace("'", "'\\''") + "'"
    }

    /**
     * Convert a Windows path (e.g., C:\Users\foo\file.hurl) to a WSL path (/mnt/c/Users/foo/file.hurl).
     */
    private fun toWslPath(windowsPath: String): String {
        val normalized = windowsPath.replace("\\", "/")

        // Handle WSL UNC paths: //wsl.localhost/<Distro>/path or //wsl$//<Distro>/path
        val wslUncRegex = Regex("^//wsl(?:\\.localhost|\\$)/[^/]+(/.*)")
        val wslUncMatch = wslUncRegex.matchEntire(normalized)
        if (wslUncMatch != null) {
            return wslUncMatch.groupValues[1]
        }

        // If it already looks like a Unix path, return as-is
        if (normalized.startsWith("/")) return normalized

        // Convert drive letter: C:\foo -> /mnt/c/foo
        val driveRegex = Regex("^([A-Za-z]):/(.*)$")
        val driveMatch = driveRegex.matchEntire(normalized)
        return if (driveMatch != null) {
            val drive = driveMatch.groupValues[1].lowercase()
            val rest = driveMatch.groupValues[2]
            "/mnt/$drive/$rest"
        } else {
            normalized
        }
    }
}
