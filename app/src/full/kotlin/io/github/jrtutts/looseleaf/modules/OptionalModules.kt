package io.github.jrtutts.looseleaf.modules

import io.github.jrtutts.looseleaf.core.module.EditorModule
import io.github.jrtutts.looseleaf.modules.ocr.OcrModule

/** Modules that exist only in the full flavour because of their size. */
val optionalModules: List<EditorModule> = listOf(OcrModule)
