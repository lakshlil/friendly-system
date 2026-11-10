package com.lakshlabs.medreminder

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Drug lookup on the free NIH RxNorm API (rxnav.nlm.nih.gov, no key needed).
 * Names are downloaded once and cached on the device; strengths are fetched per drug.
 * A small built-in list keeps the form usable offline.
 */
object Drug {
    data class Suggestion(val label: String, val medName: String, val apiName: String)
    data class Variant(val strength: String, val form: String, val category: String, val value: Double) {
        /** Label for the dropdown: strength, plus the form when it isn't a plain tablet/capsule. */
        val label: String get() {
            val sub = form.removePrefix("Oral ").trim()
            return if (sub == category || sub.isEmpty()) strength else "$strength · $sub"
        }
    }

    private const val BASE = "https://rxnav.nlm.nih.gov/REST/"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var names: List<String> = emptyList()

    // Names used outside the US that RxNorm files under another name.
    private val ALIASES = mapOf(
        "paracetamol" to "acetaminophen", "salbutamol" to "albuterol", "adrenaline" to "epinephrine",
        "noradrenaline" to "norepinephrine", "frusemide" to "furosemide", "glibenclamide" to "glyburide",
        "rifampicin" to "rifampin", "lignocaine" to "lidocaine", "pethidine" to "meperidine",
        "vitamin c" to "ascorbic acid", "vitamin d3" to "cholecalciferol", "vitamin b12" to "cyanocobalamin",
        "vitamin d" to "cholecalciferol", "isoprenaline" to "isoproterenol", "amoxycillin" to "amoxicillin",
    )

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    fun apiNameFor(typed: String): String = ALIASES[typed.trim().lowercase()] ?: typed.trim().lowercase()

    fun preload(c: Context) {
        if (names.isNotEmpty()) return
        thread {
            val f = File(c.filesDir, "drugnames.txt")
            try {
                if (!f.exists() || f.length() == 0L) {
                    val body = get("displaynames.json")
                    val arr = JSONObject(body).getJSONObject("displayTermsList").getJSONArray("term")
                    val out = LinkedHashSet<String>()
                    for (i in 0 until arr.length()) {
                        val t = arr.getString(i)
                        if (t.length > 32 || !t[0].isLetter() || t.any { it in "/,(:[" }) continue
                        out.add(t.lowercase())
                    }
                    f.writeText(out.sorted().joinToString("\n"))
                }
                names = f.readLines()
            } catch (_: Exception) { /* offline: suggest() falls back to the built-in list */ }
        }
    }

    fun suggest(query: String): List<Suggestion> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val out = ArrayList<Suggestion>()
        ALIASES.filter { it.key.startsWith(q) }.forEach { out.add(Suggestion("${cap(it.key)}  ·  ${cap(it.value)}", cap(it.key), it.value)) }
        val pool = if (names.isNotEmpty()) names else BUILTIN.keys.toList()
        pool.filter { it.startsWith(q) }.take(6).forEach { out.add(Suggestion(cap(it), cap(it), it)) }
        if (out.size < 6) pool.filter { !it.startsWith(q) && it.contains(q) }.take(6 - out.size)
            .forEach { out.add(Suggestion(cap(it), cap(it), it)) }
        return out.take(6)
    }

    /** Calls back on the main thread: variants, and whether they came from the offline list. */
    fun variants(apiName: String, cb: (List<Variant>?, Boolean) -> Unit) {
        thread {
            val online = try {
                parse(get("drugs.json?name=" + java.net.URLEncoder.encode(apiName, "UTF-8")), apiName)
            } catch (_: Exception) { null }
            val offline = BUILTIN[apiName]
            main.post {
                when {
                    online != null && online.isNotEmpty() -> cb(online, false)
                    offline != null -> cb(offline, true)
                    else -> cb(online, false)   // null = couldn't reach, empty = not found
                }
            }
        }
    }

    private fun get(path: String): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 15000
        try { return conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
    }

    private val STRENGTH = Regex("""(\d[\d.]*) (MG|MCG|G|UNT|MEQ|IU|ML|%)(/(?:ML|HR|ACTUAT|G|MG))?""")
    private val LEAD = Regex("""^(\d+ HR|[\d.]+ ML) """)

    private fun unitLabel(u: String) = when (u) {
        "MG" -> "mg"; "MCG" -> "mcg"; "G" -> "g"; "UNT" -> "units"; "MEQ" -> "mEq"
        "ML" -> "mL"; "ACTUAT" -> "puff"; "HR" -> "hr"; else -> u
    }

    fun categoryOf(form: String): String {
        val f = form.lowercase()
        return when {
            "inject" in f || "syringe" in f || "cartridge" in f || " pen" in f -> "Injection"
            "ophthalmic" in f || "otic" in f || "drops" in f -> "Drops"
            "inhal" in f || "aerosol" in f -> "Inhaler"
            "cream" in f || "ointment" in f || "gel" in f || "lotion" in f || "topical" in f ||
                "patch" in f || "foam" in f || "shampoo" in f -> "Topical"
            "tablet" in f || "lozenge" in f -> "Tablet"
            "capsule" in f -> "Capsule"
            "solution" in f || "suspension" in f || "syrup" in f || "elixir" in f || "liquid" in f -> "Liquid"
            else -> "Other"
        }
    }

    /** Keeps single-ingredient generics ("ibuprofen 400 MG Oral Tablet") and reads strength and form from the name. */
    private fun parse(body: String, apiName: String): List<Variant> {
        val groups = JSONObject(body).getJSONObject("drugGroup").optJSONArray("conceptGroup") ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Variant>()
        for (g in 0 until groups.length()) {
            val grp = groups.getJSONObject(g)
            if (grp.optString("tty") != "SCD") continue
            val props = grp.optJSONArray("conceptProperties") ?: continue
            for (i in 0 until props.length()) {
                var n = props.getJSONObject(i).getString("name")
                if (" / " in n) continue
                val lead = LEAD.find(n)?.value?.trim()
                if (lead != null) n = n.removePrefix(lead).trim()
                val m = STRENGTH.find(n) ?: continue
                if (!n.substring(0, m.range.first).trim().lowercase().startsWith(apiName)) continue
                val unit = unitLabel(m.groupValues[2]) + m.groupValues[3].let { if (it.isEmpty()) "" else "/" + unitLabel(it.drop(1)) }
                var form = n.substring(m.range.last + 1).trim()
                if (lead != null && lead.endsWith("HR")) form = "$lead $form"
                val strength = "${m.groupValues[1]} $unit"
                if (!seen.add("$strength|$form")) continue
                out.add(Variant(strength, form, categoryOf(form), m.groupValues[1].toDoubleOrNull() ?: 0.0))
            }
        }
        return out.sortedWith(compareBy({ Cat.all.indexOf(it.category) }, { it.value }, { it.form }))
    }

    private fun bv(v: String, u: String, form: String) =
        Variant("$v $u", form, categoryOf(form), v.toDoubleOrNull() ?: 0.0)

    private val BUILTIN: Map<String, List<Variant>> = mapOf(
        "acetaminophen" to listOf(bv("325", "mg", "Oral Tablet"), bv("500", "mg", "Oral Tablet"), bv("650", "mg", "Oral Tablet"), bv("1000", "mg", "Oral Tablet"), bv("160", "mg/5 mL", "Oral Suspension")),
        "ibuprofen" to listOf(bv("200", "mg", "Oral Tablet"), bv("400", "mg", "Oral Tablet"), bv("600", "mg", "Oral Tablet"), bv("800", "mg", "Oral Tablet"), bv("100", "mg/5 mL", "Oral Suspension")),
        "aspirin" to listOf(bv("75", "mg", "Oral Tablet"), bv("81", "mg", "Oral Tablet"), bv("325", "mg", "Oral Tablet")),
        "metformin" to listOf(bv("500", "mg", "Oral Tablet"), bv("850", "mg", "Oral Tablet"), bv("1000", "mg", "Oral Tablet")),
        "amlodipine" to listOf(bv("2.5", "mg", "Oral Tablet"), bv("5", "mg", "Oral Tablet"), bv("10", "mg", "Oral Tablet")),
        "atorvastatin" to listOf(bv("10", "mg", "Oral Tablet"), bv("20", "mg", "Oral Tablet"), bv("40", "mg", "Oral Tablet"), bv("80", "mg", "Oral Tablet")),
        "omeprazole" to listOf(bv("10", "mg", "Oral Capsule"), bv("20", "mg", "Oral Capsule"), bv("40", "mg", "Oral Capsule")),
        "cetirizine" to listOf(bv("5", "mg", "Oral Tablet"), bv("10", "mg", "Oral Tablet"), bv("1", "mg/mL", "Oral Solution")),
        "amoxicillin" to listOf(bv("250", "mg", "Oral Capsule"), bv("500", "mg", "Oral Capsule"), bv("875", "mg", "Oral Tablet"), bv("250", "mg/5 mL", "Oral Suspension")),
        "levothyroxine" to listOf(bv("25", "mcg", "Oral Tablet"), bv("50", "mcg", "Oral Tablet"), bv("75", "mcg", "Oral Tablet"), bv("100", "mcg", "Oral Tablet")),
        "losartan" to listOf(bv("25", "mg", "Oral Tablet"), bv("50", "mg", "Oral Tablet"), bv("100", "mg", "Oral Tablet")),
        "cholecalciferol" to listOf(bv("1000", "IU", "Oral Tablet"), bv("2000", "IU", "Oral Capsule"), bv("60000", "IU", "Oral Capsule")),
    )
}
