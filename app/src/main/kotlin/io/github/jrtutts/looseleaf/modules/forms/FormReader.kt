package io.github.jrtutts.looseleaf.modules.forms

import android.graphics.Matrix
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import io.github.jrtutts.looseleaf.core.model.FormKey
import io.github.jrtutts.looseleaf.core.model.PPoint
import io.github.jrtutts.looseleaf.core.model.PRect
import io.github.jrtutts.looseleaf.core.pdf.PageGeometry
import io.github.jrtutts.looseleaf.core.pdf.PdfFiles
import java.io.File
import java.util.IdentityHashMap

enum class FieldKind { TEXT, CHECK, RADIO, CHOICE }

/** One place on a page where a field appears. [onValue] is the "checked" state name of a checkbox or radio button. */
class FormWidget(val pageIndex: Int, val rect: PRect, val onValue: String?)

class FormField(
    val key: FormKey,
    val label: String,
    val kind: FieldKind,
    val widgets: List<FormWidget>,
    /** The value stored in the PDF. */
    val original: String,
    val multiline: Boolean = false,
    /** Export value to display value, for choice fields. */
    val options: List<Pair<String, String>> = emptyList(),
)

/** Finds the fillable AcroForm fields of a PDF and where they are on its pages. */
object FormReader {
    fun read(source: Int, file: File): List<FormField> = PdfFiles.load(file).use { doc ->
        val form = doc.documentCatalog.acroForm ?: return emptyList()
        val pageOf = IdentityHashMap<COSDictionary, Int>()
        val toPage = ArrayList<Matrix>()
        doc.pages.forEachIndexed { i, page ->
            runCatching { page.annotations }.getOrNull()?.forEach { pageOf[it.cosObject] = i }
            val crop = page.cropBox
            val m = PageGeometry.visualToUserMatrix(
                PageGeometry.normalizeRotation(page.rotation), crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height,
            )
            toPage += Matrix().also { m.invert(it) }
        }

        val out = ArrayList<FormField>()
        for (field in form.fieldTree) {
            if (field !is PDTerminalField || field.isReadOnly) continue
            val kind = when (field) {
                is PDTextField -> FieldKind.TEXT
                is PDCheckBox -> FieldKind.CHECK
                is PDRadioButton -> FieldKind.RADIO
                is PDChoice -> FieldKind.CHOICE
                else -> continue   // push buttons and signature fields
            }
            val widgets = field.widgets.mapNotNull { w ->
                val pageIndex = pageOf[w.cosObject] ?: return@mapNotNull null
                val r = w.rectangle ?: return@mapNotNull null
                if (w.isHidden || w.isNoView) return@mapNotNull null
                val pts = floatArrayOf(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)
                toPage[pageIndex].mapPoints(pts)
                val on = runCatching {
                    val normal = w.appearance?.normalAppearance
                    if (normal != null && normal.isSubDictionary) normal.subDictionary.keys.map { it.name }.firstOrNull { it != "Off" } else null
                }.getOrNull()
                FormWidget(pageIndex, PRect.ofPoints(PPoint(pts[0], pts[1]), PPoint(pts[2], pts[3])), on)
            }
            if (widgets.isEmpty()) continue
            val name = field.fullyQualifiedName ?: continue
            out += FormField(
                key = FormKey(source, name),
                label = field.alternateFieldName?.takeIf { it.isNotBlank() } ?: field.partialName ?: name,
                kind = kind,
                widgets = widgets,
                original = runCatching { field.valueAsString }.getOrNull().orEmpty(),
                multiline = field is PDTextField && field.isMultiline,
                options = if (field is PDChoice) {
                    val export = field.optionsExportValues
                    val display = field.optionsDisplayValues
                    export.mapIndexed { i, e -> e to (display.getOrNull(i) ?: e) }
                } else emptyList(),
            )
        }
        out
    }
}
