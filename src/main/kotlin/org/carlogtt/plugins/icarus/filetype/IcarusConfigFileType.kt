package org.carlogtt.plugins.icarus.filetype

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

class IcarusConfigFileType private constructor() : LanguageFileType(IcarusConfigLanguage.INSTANCE) {

    override fun getName(): String = FILE_TYPE_NAME
    override fun getDescription(): String = FILE_TYPE_DESCRIPTION
    override fun getDefaultExtension(): String = ""
    override fun getIcon(): Icon = AllIcons.FileTypes.Config

    companion object {
        const val FILE_TYPE_NAME = "Icarus Config"
        const val FILE_TYPE_DESCRIPTION = "Icarus Config"

        @JvmField
        val INSTANCE = IcarusConfigFileType()
    }
}
