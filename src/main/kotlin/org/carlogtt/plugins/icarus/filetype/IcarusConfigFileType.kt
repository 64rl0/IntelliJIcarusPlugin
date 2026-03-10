package org.carlogtt.plugins.icarus.filetype

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import org.carlogtt.plugins.icarus.IcarusBundle
import javax.swing.Icon

class IcarusConfigFileType private constructor() : LanguageFileType(IcarusConfigLanguage.INSTANCE) {

    override fun getName(): String = IcarusBundle.message("icarus.filetype.name")
    override fun getDescription(): String = IcarusBundle.message("icarus.filetype.description")
    override fun getDefaultExtension(): String = ""
    override fun getIcon(): Icon = AllIcons.FileTypes.Config

    companion object {
        @JvmField
        val INSTANCE = IcarusConfigFileType()
    }
}
