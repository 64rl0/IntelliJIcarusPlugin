package org.carlogtt.plugins.icarus.filetype

import com.intellij.lang.Language

class IcarusConfigLanguage private constructor() : Language(LANGUAGE_ID) {

    companion object {
        const val LANGUAGE_ID = "IcarusConfig"

        @JvmField
        val INSTANCE = IcarusConfigLanguage()
    }
}
