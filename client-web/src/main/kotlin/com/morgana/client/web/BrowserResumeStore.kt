package com.morgana.client.web

import com.morgana.client.net.ResumeInfo
import com.morgana.client.net.ResumeStore
import org.teavm.jso.JSBody

private const val RESUME_KEY = "morgana.resume"

@JSBody(params = ["k"], script = "try { return window.localStorage.getItem(k); } catch (e) { return null; }")
private external fun readItem(k: String): String?

@JSBody(params = ["k", "v"], script = "try { window.localStorage.setItem(k, v); } catch (e) {}")
private external fun writeItem(k: String, v: String)

@JSBody(params = ["k"], script = "try { window.localStorage.removeItem(k); } catch (e) {}")
private external fun removeItem(k: String)

/**
 * Remembers the resume token in the page's `localStorage`, so reopening or reloading the tab (or returning to a tab the
 * browser discarded) resumes the character instead of showing the login screen. Storage can be blocked (private mode),
 * so every access is guarded in script and failures just mean no resume.
 */
class BrowserResumeStore : ResumeStore {
    override fun load(): ResumeInfo? = ResumeInfo.fromJson(readItem(RESUME_KEY))

    override fun save(info: ResumeInfo) {
        writeItem(RESUME_KEY, info.toJson())
    }

    override fun clear() {
        removeItem(RESUME_KEY)
    }
}
