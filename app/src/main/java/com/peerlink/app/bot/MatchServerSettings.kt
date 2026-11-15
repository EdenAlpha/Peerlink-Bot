package com.peerlink.app.bot

/** Only an explicit successful server response can select the revision policy. */
internal object MatchServerSettings {
    fun revisionCheck(response: Map<*, *>): Boolean {
        check(response["result"] == "NOERR") { "Could not read the server's match settings: ${response["result"]}" }
        return when (response["is_revision_check"]) {
            "YES" -> true
            "NO" -> false
            else -> error("Server match settings have no supported is_revision_check value")
        }
    }
}
