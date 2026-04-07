package com.github.chadw.intellijhurl.highlight

import com.github.chadw.intellijhurl.lexer.HurlTokenTypes
import com.github.chadw.intellijhurl.run.HurlAnnotations
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement

class HurlAnnotator : Annotator {

    companion object {
        private val SECTION_ANNOTATION_PATTERN = Regex(
            "^#\\s*(${HurlAnnotations.SETUP}|${HurlAnnotations.TEARDOWN}|${HurlAnnotations.TEST})\\s*$"
        )
    }

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val elementType = element.node?.elementType ?: return

        when (elementType) {
            HurlTokenTypes.TEMPLATE_VAR -> {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .textAttributes(HurlSyntaxHighlighter.TEMPLATE_VAR)
                    .create()
            }
            HurlTokenTypes.URL_VALUE -> {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .textAttributes(HurlSyntaxHighlighter.URL)
                    .create()
            }
            HurlTokenTypes.COMMENT -> {
                val text = element.text.trim()
                if (SECTION_ANNOTATION_PATTERN.matches(text)) {
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .textAttributes(HurlSyntaxHighlighter.SECTION_ANNOTATION)
                        .create()
                }
            }
        }
    }
}
