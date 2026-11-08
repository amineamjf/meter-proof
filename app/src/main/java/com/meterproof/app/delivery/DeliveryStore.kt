package com.meterproof.app.delivery

import java.io.File

/**
 * تخزين المعايرة على الجهاز فقط: نموذج الألوان، العتبات، وعينات الخصائص.
 * العينات أرقام فقط (14 رقمًا لكل إطار)، لا صور ولا بكسلات، فلا يمكن أن تحوي وجوهًا أو داخل بيوت.
 * ملفات نصية بسيطة داخل مجلد التطبيق الخاص (لا مزامنة ولا رفع).
 */
class DeliveryStore(private val dir: File) {

    private val colorFile get() = File(dir, "color_model.txt")
    private val paramsFile get() = File(dir, "params.txt")
    private val samplesFile get() = File(dir, "samples.txt")

    init {
        dir.mkdirs()
    }

    fun loadColorModel(): ColorModel? =
        colorFile.takeIf { it.exists() }?.readText()?.let { ColorModel.parse(it) }

    fun saveColorModel(m: ColorModel) = colorFile.writeText(m.serialize())

    fun loadParams(): DeliveryParams? =
        paramsFile.takeIf { it.exists() }?.readText()?.let { DeliveryParams.parse(it) }

    fun saveParams(p: DeliveryParams) = paramsFile.writeText(p.serialize())

    /** سطر لكل عينة: label|colorVersion|frame;frame;... */
    fun addSample(s: Sample) {
        samplesFile.appendText(format(s) + "\n")
    }

    fun loadSamples(): List<Sample> {
        if (!samplesFile.exists()) return emptyList()
        return samplesFile.readLines().mapNotNull { parse(it) }
    }

    fun deleteLastSample(): Boolean {
        val lines = if (samplesFile.exists()) samplesFile.readLines().filter { it.isNotBlank() } else return false
        if (lines.isEmpty()) return false
        samplesFile.writeText(lines.dropLast(1).joinToString("\n") + if (lines.size > 1) "\n" else "")
        return true
    }

    fun clearSamples() {
        samplesFile.delete()
    }

    /** يحذف كل ما خزّنته المعايرة. */
    fun clearAll() {
        colorFile.delete()
        paramsFile.delete()
        samplesFile.delete()
    }

    companion object {
        fun format(s: Sample): String =
            s.label.code + "|" + s.colorVersion + "|" + s.frames.joinToString(";") { it.toLine() }

        fun parse(line: String): Sample? {
            if (line.isBlank()) return null
            val parts = line.split('|')
            if (parts.size != 3) return null
            val label = Label.fromCode(parts[0]) ?: return null
            val ver = parts[1].toIntOrNull() ?: return null
            val frames = parts[2].split(';').filter { it.isNotEmpty() }.map { Feat.parse(it) ?: return null }
            return Sample(label, ver, frames)
        }
    }
}
