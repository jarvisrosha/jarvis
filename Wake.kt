package app.jarvis

/** Finds the wake word "Jarvis" in what the speech recognizer heard, in Latin or Devanagari spelling. */
object Wake {
    private val latin = Regex("(?i)\\b[jz][ae]?r+v[iye][sczx]\\w{0,2}")

    // ज/झ/ज़ + optional nukta + optional aa + र + optional virama + व + i/ii + स/श/ष/ज/झ/ज़
    private val deva = Regex(
        "[जझज़]़?[ाॉ]?र्?व[िी][सशषजझज़]़?"
    )

    /** Returns what was said after the wake word (possibly empty), or null when the wake word is absent. */
    fun split(raw: String): String? {
        val text = raw.replace("‌", "").replace("‍", "")
        val m = latin.find(text) ?: deva.find(text) ?: return null
        return text.substring(m.range.last + 1)
            .trim()
            .trimStart(',', '.', '!', '?', ':', ';', '-', '।', ' ')
    }
}
