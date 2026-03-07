package org.carlogtt.plugins.icarus.filetype

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

object IcarusConfigTokenTypes {
    @JvmField val COMMENT = IElementType("ICARUS_COMMENT", IcarusConfigLanguage.INSTANCE)
    @JvmField val KEYWORD = IElementType("ICARUS_KEYWORD", IcarusConfigLanguage.INSTANCE)
    @JvmField val STRING = IElementType("ICARUS_STRING", IcarusConfigLanguage.INSTANCE)
    @JvmField val NUMBER = IElementType("ICARUS_NUMBER", IcarusConfigLanguage.INSTANCE)
    @JvmField val TEXT = IElementType("ICARUS_TEXT", IcarusConfigLanguage.INSTANCE)
}

class IcarusConfigLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        tokenStart = startOffset
        tokenEnd = startOffset
        tokenType = null
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= endOffset) {
            tokenType = null
            return
        }

        when {
            buffer[tokenStart] == '#' -> lexComment()
            buffer[tokenStart] == '"' || buffer[tokenStart] == '\'' -> lexString()
            buffer[tokenStart].isDigit() -> lexNumber()
            isWordChar(buffer[tokenStart]) -> lexWord()
            else -> lexOther()
        }
    }

    private fun lexComment() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < endOffset && buffer[tokenEnd] != '\n') tokenEnd++
        tokenType = IcarusConfigTokenTypes.COMMENT
    }

    private fun lexString() {
        val quote = buffer[tokenStart]
        tokenEnd = tokenStart + 1
        while (tokenEnd < endOffset && buffer[tokenEnd] != quote && buffer[tokenEnd] != '\n') tokenEnd++
        if (tokenEnd < endOffset && buffer[tokenEnd] == quote) tokenEnd++
        tokenType = IcarusConfigTokenTypes.STRING
    }

    private fun lexNumber() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < endOffset && (buffer[tokenEnd].isDigit() || buffer[tokenEnd] == '.')) tokenEnd++
        tokenType = IcarusConfigTokenTypes.NUMBER
    }

    private fun lexWord() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < endOffset && isWordChar(buffer[tokenEnd])) tokenEnd++
        val word = buffer.subSequence(tokenStart, tokenEnd).toString()
        tokenType = if (word in KEYWORDS) IcarusConfigTokenTypes.KEYWORD else IcarusConfigTokenTypes.TEXT
    }

    private fun lexOther() {
        tokenEnd = tokenStart + 1
        while (tokenEnd < endOffset) {
            val c = buffer[tokenEnd]
            if (c == '#' || c == '"' || c == '\'' || c.isDigit() || isWordChar(c)) break
            tokenEnd++
        }
        tokenType = IcarusConfigTokenTypes.TEXT
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == '_' || c == '.'

    companion object {
        private val KEYWORDS = setOf(
            "package",
            "build-system",
            "icarus-python3",
            "icarus-cdk",
            "ignore",
        )
    }
}
