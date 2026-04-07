package com.github.chadw.intellijhurl.run

data class HurlSection(
    val type: SectionType,
    val content: String
)

enum class SectionType { SETUP, TEST, TEARDOWN }

object HurlAnnotationParser {

    /**
     * Parse a .hurl file content into sections based on `# @setup` / `# @teardown` annotations.
     *
     * Rules:
     * - `# @setup` (trimmed) starts a SETUP section
     * - `# @teardown` (trimmed) starts a TEARDOWN section
     * - `# @test` (trimmed) starts a TEST section
     * - Content before any annotation belongs to TEST
     * - Each annotation captures all lines after it until the next annotation or EOF
     *
     * Returns sections ordered: SETUP (if any) → TEST (if any) → TEARDOWN (if any).
     */
    fun parse(fileContent: String): List<HurlSection> {
        val lines = fileContent.lines()
        val rawSections = mutableListOf<Pair<SectionType, StringBuilder>>()
        var currentType: SectionType? = null
        var currentBuilder: StringBuilder? = null

        for (line in lines) {
            val trimmed = line.trim()
            val annotation = when (trimmed) {
                "# @setup" -> SectionType.SETUP
                "# @teardown" -> SectionType.TEARDOWN
                "# @test" -> SectionType.TEST
                else -> null
            }

            if (annotation != null) {
                // Flush current section
                if (currentBuilder != null && currentType != null) {
                    rawSections.add(currentType to currentBuilder)
                }
                currentType = annotation
                currentBuilder = StringBuilder()
            } else {
                if (currentBuilder == null) {
                    // No annotation seen yet — defaults to TEST
                    currentType = SectionType.TEST
                    currentBuilder = StringBuilder()
                }
                if (currentBuilder.isNotEmpty()) {
                    currentBuilder.append("\n")
                }
                currentBuilder.append(line)
            }
        }

        // Flush last section
        if (currentBuilder != null && currentType != null) {
            rawSections.add(currentType to currentBuilder)
        }

        // Merge sections of the same type (in case of multiple `# @test` blocks, etc.)
        val merged = linkedMapOf<SectionType, StringBuilder>()
        for ((type, builder) in rawSections) {
            merged.getOrPut(type) { StringBuilder() }.let { existing ->
                if (existing.isNotEmpty()) existing.append("\n")
                existing.append(builder)
            }
        }

        // Return in canonical order: SETUP → TEST → TEARDOWN
        val result = mutableListOf<HurlSection>()
        for (type in listOf(SectionType.SETUP, SectionType.TEST, SectionType.TEARDOWN)) {
            val content = merged[type]?.toString()?.trim() ?: continue
            if (content.isNotEmpty()) {
                result.add(HurlSection(type, content))
            }
        }
        return result
    }

    /**
     * Returns true if the file content contains any `# @setup` or `# @teardown` annotations.
     * Used to decide whether to use the annotation-based execution path.
     */
    fun hasAnnotations(fileContent: String): Boolean {
        return fileContent.lines().any {
            val trimmed = it.trim()
            trimmed == "# @setup" || trimmed == "# @teardown" || trimmed == "# @test"
        }
    }
}
