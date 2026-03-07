package org.carlogtt.plugins.icarus.filetype

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class IcarusConfigSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = IcarusConfigLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        return when (tokenType) {
            IcarusConfigTokenTypes.COMMENT -> COMMENT_KEYS
            IcarusConfigTokenTypes.KEYWORD -> KEYWORD_KEYS
            IcarusConfigTokenTypes.STRING -> STRING_KEYS
            IcarusConfigTokenTypes.NUMBER -> NUMBER_KEYS
            else -> EMPTY_KEYS
        }
    }

    companion object {
        private val COMMENT_KEYS = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "ICARUS_CONFIG_COMMENT",
                DefaultLanguageHighlighterColors.LINE_COMMENT,
            )
        )
        private val KEYWORD_KEYS = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "ICARUS_CONFIG_KEYWORD",
                DefaultLanguageHighlighterColors.KEYWORD,
            )
        )
        private val STRING_KEYS = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "ICARUS_CONFIG_STRING",
                DefaultLanguageHighlighterColors.STRING,
            )
        )
        private val NUMBER_KEYS = arrayOf(
            TextAttributesKey.createTextAttributesKey(
                "ICARUS_CONFIG_NUMBER",
                DefaultLanguageHighlighterColors.NUMBER,
            )
        )
        private val EMPTY_KEYS = emptyArray<TextAttributesKey>()
    }
}

class IcarusConfigSyntaxHighlighterFactory : SyntaxHighlighterFactory() {

    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter {
        return IcarusConfigSyntaxHighlighter()
    }
}
