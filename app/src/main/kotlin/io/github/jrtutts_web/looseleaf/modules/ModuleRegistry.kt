package io.github.jrtutts_web.looseleaf.modules

import io.github.jrtutts_web.looseleaf.core.module.EditorModule
import io.github.jrtutts_web.looseleaf.modules.annotate.AnnotateModule
import io.github.jrtutts_web.looseleaf.modules.compress.CompressModule
import io.github.jrtutts_web.looseleaf.modules.find.FindModule
import io.github.jrtutts_web.looseleaf.modules.forms.FormsModule
import io.github.jrtutts_web.looseleaf.modules.library.LibraryModule
import io.github.jrtutts_web.looseleaf.modules.pages.PagesModule
import io.github.jrtutts_web.looseleaf.modules.protect.ProtectModule
import io.github.jrtutts_web.looseleaf.modules.redact.RedactModule
import io.github.jrtutts_web.looseleaf.modules.signatures.SignaturesModule
import io.github.jrtutts_web.looseleaf.modules.stamps.StampsModule
import io.github.jrtutts_web.looseleaf.modules.text.TextModule

/**
 * The one place where modules are registered. The order here is the order of
 * the toolbar, the menus and the Settings list. To add a module, add it to
 * this list; to remove one for good, delete its line and its package.
 *
 * Modules that only exist in some build flavours are listed in that flavour's
 * OptionalModules.kt and appended here.
 */
object ModuleRegistry {
    val all: List<EditorModule> = listOf(
        AnnotateModule,
        TextModule,
        StampsModule,
        SignaturesModule,
        FormsModule,
        RedactModule,
        FindModule,
        PagesModule,
        CompressModule,
        ProtectModule,
        LibraryModule,
    ) + optionalModules   // OCR in the "full" build flavour; see src/full and src/lite
}
