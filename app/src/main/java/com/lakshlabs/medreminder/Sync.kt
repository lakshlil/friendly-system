package com.lakshlabs.medreminder

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Cloud backup and sync, end-to-end encrypted.
 *
 * Each phone gets a random sync code (128 bits, shown in Settings). From it:
 *   id  = SHA-256("medreminder-sync-id:"  + code)  → which blob on the server
 *   key = SHA-256("medreminder-sync-key:" + code)  → AES-256-GCM key, never leaves the phone
 * The server (sync-server/, Vercel) stores only {v, iv, data}. Phones with the same code share data.
 *
 * Merge: medicines by id, newest change wins (`medUpdated`), deletions win over older edits (`deleted`);
 * dose log entries by key, newest change wins (`logTs`). Pull, merge, then push the merged copy.
 */
object Sync {
    @Volatile var urlOverride: String? = null
    /** Tests switch this on so nothing touches the network. */
    @Volatile var offline = false
    @Volatile var onChanged: (() -> Unit)? = null

    val url: String get() = urlOverride ?: BuildConfig.SYNC_URL
    private val main = Handler(Looper.getMainLooper())
    private var lastContext: Context? = null
    private val runner = Runnable { lastContext?.let { c -> thread { syncBlocking(c) } } }

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // no 0/O, 1/I

    fun code(c: Context): String = Store.syncCode(c) ?: newCode().also { Store.setSyncCode(c, it) }

    private fun newCode(): String {
        val r = SecureRandom()
        val chars = (1..26).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")   // 26 × 5 bits = 130 bits
        return chars.chunked(4).joinToString("-")
    }

    /** Cleans up a typed code; null if it can't be one. */
    fun normalize(input: String): String? {
        val s = input.uppercase().filter { it in ALPHABET }
        return if (s.length == 26) s.chunked(4).joinToString("-") else null
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    fun idFor(code: String) = sha("medreminder-sync-id:$code").joinToString("") { "%02x".format(it) }
    private fun keyFor(code: String) = SecretKeySpec(sha("medreminder-sync-key:$code"), "AES")

    fun encrypt(code: String, plain: String): JSONObject {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keyFor(code), GCMParameterSpec(128, iv)) }
        return JSONObject().put("v", 1).put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP))
    }

    fun decrypt(code: String, o: JSONObject): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, keyFor(code), GCMParameterSpec(128, Base64.decode(o.getString("iv"), Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(o.getString("data"), Base64.NO_WRAP)))
    }

    /** Sync a few seconds after the last change (changes come in bursts). */
    fun soon(c: Context, delayMs: Long = 4000) {
        if (offline || url.isEmpty() || !Store.syncEnabled(c)) return
        lastContext = c.applicationContext
        main.removeCallbacks(runner); main.postDelayed(runner, delayMs)
    }

    fun now(c: Context, done: (String) -> Unit = {}) {
        val app = c.applicationContext
        thread { val s = syncBlocking(app, force = true); main.post { done(s) } }
    }

    private val lock = Any()

    fun syncBlocking(c: Context, force: Boolean = false): String = synchronized(lock) {
        if (offline || url.isEmpty()) return "Sync not available in this build"
        if (!Store.syncEnabled(c) && !force) return Store.syncStatus(c)
        val code = code(c)
        val endpoint = "$url/api/sync?id=${idFor(code)}"
        val status = try {
            val (http, body) = request("GET", endpoint, null)
            var changed = false
            when (http) {
                200 -> {
                    val plain = try { decrypt(code, JSONObject(body)) } catch (e: Exception) {
                        throw IllegalStateException("cloud copy can't be opened with this code")
                    }
                    changed = merge(c, JSONObject(plain))
                }
                404 -> {}
                else -> throw IllegalStateException("server said $http")
            }
            val (put, _) = request("PUT", endpoint, encrypt(code, snapshot(c).toString()).toString())
            if (put != 200) throw IllegalStateException("server said $put")
            Store.setLastSync(c, System.currentTimeMillis())
            if (changed) { Scheduler.rescheduleAll(c); onChanged?.let { cb -> main.post(cb) } }
            "Synced"
        } catch (e: Exception) {
            "Couldn't sync: ${e.message ?: e.javaClass.simpleName}"
        }
        Store.setSyncStatus(c, status)
        Store.log(c, "Cloud sync: $status")
        status
    }

    private fun request(method: String, u: String, body: String?): Pair<Int, String> {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 10_000; conn.readTimeout = 20_000; conn.useCaches = false
        try {
            if (body != null) {
                conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            return code to text
        } finally { conn.disconnect() }
    }

    /** Everything worth syncing. The developer test medicine stays on this phone. */
    fun snapshot(c: Context): JSONObject {
        val meds = JSONArray()
        val list = Store.rawList(c)
        for (i in 0 until list.length()) {
            val m = list.getJSONObject(i)
            if (!m.getString("id").startsWith(Scheduler.DEV_PREFIX)) meds.put(m)
        }
        return JSONObject().put("v", 1).put("meds", meds)
            .put("medUpdated", Store.jsonMap(c, "medUpdated")).put("deleted", Store.jsonMap(c, "deleted"))
            .put("log", Store.rawLog(c)).put("logTs", Store.jsonMap(c, "logTs"))
    }

    /** Folds the cloud copy into this phone's data. Returns true if anything here changed. */
    fun merge(c: Context, remote: JSONObject): Boolean {
        var changed = false
        val localUpd = Store.jsonMap(c, "medUpdated"); val remoteUpd = remote.optJSONObject("medUpdated") ?: JSONObject()
        val deleted = Store.jsonMap(c, "deleted"); val remoteDel = remote.optJSONObject("deleted") ?: JSONObject()
        remoteDel.keys().forEach { k -> if (remoteDel.getLong(k) > deleted.optLong(k, 0)) deleted.put(k, remoteDel.getLong(k)) }

        val byId = LinkedHashMap<String, JSONObject>()
        val local = Store.rawList(c)
        for (i in 0 until local.length()) local.getJSONObject(i).let { byId[it.getString("id")] = it }
        val rm = remote.optJSONArray("meds") ?: JSONArray()
        for (i in 0 until rm.length()) {
            val m = rm.getJSONObject(i); val id = m.getString("id")
            val r = remoteUpd.optLong(id, 0); val l = localUpd.optLong(id, if (byId.containsKey(id)) 0 else -1)
            if (!byId.containsKey(id) || r > l) {
                if (byId[id]?.toString() != m.toString()) changed = true
                byId[id] = m; localUpd.put(id, maxOf(r, localUpd.optLong(id, 0)))
            }
        }
        // A deletion wins over any edit made before it.
        for (id in byId.keys.toList()) {
            val d = deleted.optLong(id, 0)
            if (d > 0 && d >= localUpd.optLong(id, 0)) {
                byId.remove(id)?.let { m -> Scheduler.cancel(c, Medicine.fromJson(m)); changed = true }
            }
        }
        if (changed) Store.saveRaw(c, JSONArray(byId.values.toList()))
        Store.setJsonMap(c, "medUpdated", localUpd); Store.setJsonMap(c, "deleted", deleted)

        val log = Store.rawLog(c); val ts = Store.jsonMap(c, "logTs")
        val rLog = remote.optJSONObject("log") ?: JSONObject(); val rTs = remote.optJSONObject("logTs") ?: JSONObject()
        val keys = (rTs.keys().asSequence() + rLog.keys().asSequence()).toSet()
        for (k in keys) {
            val rEntry = rTs.optJSONArray(k)
            val rTime = rEntry?.optLong(1, 0) ?: 0
            val lTime = ts.optJSONArray(k)?.optLong(1, 0) ?: if (log.has(k)) 0 else -1
            if (rTime > lTime || (lTime < 0 && rLog.has(k))) {
                val st = rEntry?.optString(0) ?: rLog.optString(k)
                if (st.isEmpty()) { if (log.has(k)) { log.remove(k); changed = true } }
                else if (log.optString(k) != st) { log.put(k, st); changed = true }
                if (rEntry != null) ts.put(k, rEntry)
            }
        }
        Store.saveRawLog(c, log); Store.setJsonMap(c, "logTs", ts)
        return changed
    }
}
