package com.lakshlabs.medreminder

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

/** Dose forms the UI offers. Each decides the unit and the dose presets. */
object Cat {
    val all = listOf("Tablet", "Capsule", "Liquid", "Injection", "Drops", "Topical", "Inhaler", "Other")

    fun amounts(cat: String): List<Double> = when (cat) {
        "Tablet" -> listOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0)
        "Capsule" -> listOf(1.0, 2.0, 3.0, 4.0)
        "Liquid" -> listOf(2.5, 5.0, 7.5, 10.0, 15.0, 20.0, 30.0)
        "Injection" -> listOf(0.5, 1.0, 2.0, 3.0, 5.0)
        "Drops" -> listOf(1.0, 2.0, 3.0, 4.0, 5.0, 10.0)
        "Inhaler" -> listOf(1.0, 2.0, 3.0, 4.0)
        else -> listOf(1.0, 2.0, 3.0)
    }

    fun unit(cat: String, amt: Double): String {
        val one = amt == 1.0
        return when (cat) {
            "Tablet" -> if (one) "tablet" else "tablets"
            "Capsule" -> if (one) "capsule" else "capsules"
            "Liquid", "Injection" -> "mL"
            "Drops" -> if (one) "drop" else "drops"
            "Topical" -> if (one) "application" else "applications"
            "Inhaler" -> if (one) "puff" else "puffs"
            else -> if (one) "unit" else "units"
        }
    }
}

fun fmtAmt(amt: Double, cat: String): String {
    val whole = floor(amt)
    val frac = amt - whole
    if (frac == 0.0) return whole.toInt().toString()
    if ((cat == "Tablet" || cat == "Capsule") && frac == 0.5)
        return if (whole == 0.0) "½" else "${whole.toInt()}½"
    return amt.toString().trimEnd('0').trimEnd('.')
}

val FOOD = listOf("Anytime", "Before meal", "After meal", "With meal")

/** Calendar.DAY_OF_WEEK values in Monday-first order, with their labels. */
val WEEK = listOf(2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat", 1 to "Sun")

data class Medicine(
    val id: String,
    val name: String,
    val strength: String = "",
    val category: String = "Tablet",
    val amount: Double = 1.0,
    val times: List<Int>,
    val days: Set<Int> = emptySet(),   // empty = every day
    val food: Int = 0,
    val startDay: String,
    val endDay: String? = null,        // null = ongoing
    val stock: Double = -1.0,          // < 0 = not tracked
    val alertDays: Int = 3,
    val notes: String = "",
) {
    val doseText: String
        get() = "${fmtAmt(amount, category)} ${Cat.unit(category, amount)}"

    val daysText: String
        get() = if (days.isEmpty() || days.size == 7) "Every day"
        else WEEK.filter { it.first in days }.joinToString(", ") { it.second }

    /** Whole days of supply left, or null when stock isn't tracked. */
    val daysLeft: Int?
        get() {
            if (stock < 0) return null
            val perDay = amount * times.size * (if (days.isEmpty()) 1.0 else days.size / 7.0)
            return if (perDay <= 0) null else (stock / perDay).toInt()
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("strength", strength).put("cat", category)
        .put("amount", amount).put("times", JSONArray(times)).put("days", JSONArray(days.toList()))
        .put("food", food).put("start", startDay).put("stock", stock)
        .put("alert", alertDays).put("notes", notes)
        .also { if (endDay != null) it.put("end", endDay) }

    companion object {
        fun fromJson(o: JSONObject): Medicine {
            fun ints(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
            return Medicine(
                id = o.getString("id"), name = o.getString("name"),
                strength = o.optString("strength"), category = o.optString("cat", "Tablet"),
                amount = o.optDouble("amount", 1.0), times = ints("times"), days = ints("days").toSet(),
                food = o.optInt("food"), startDay = o.optString("start", Store.dayStr()),
                endDay = if (o.has("end")) o.getString("end") else null,
                stock = o.optDouble("stock", -1.0), alertDays = o.optInt("alert", 3),
                notes = o.optString("notes"),
            )
        }
    }
}

/** One scheduled dose. */
data class Dose(val med: Medicine, val minute: Int)

fun fmtTime(minute: Int): String {
    val h = minute / 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "%d:%02d %s".format(h12, minute % 60, if (h < 12) "AM" else "PM")
}
