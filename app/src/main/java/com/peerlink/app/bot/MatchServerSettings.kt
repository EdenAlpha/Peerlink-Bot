package com.peerlink.app.bot

/** The server options consumed by the original client before setting up a match. */
internal data class MatchServerSettings(
    val revisionCheck: Boolean,
    val protocolOptions: String,
    val onlineOptions: String,
    val slowdownThresholds: List<Int>,
) {
    companion object {
        fun fromResponse(response: Map<*, *>): MatchServerSettings {
            check(response["result"] == "NOERR") { "Could not read the server's match settings: ${response["result"]}" }
            val revisionCheck = when (response["is_revision_check"]) {
                "YES" -> true
                "NO" -> false
                else -> error("Server match settings have no supported is_revision_check value")
            }
            val thresholds = response["slowdown_judgment_threshold_list"]
            check(thresholds == null || thresholds is List<*> && thresholds.size <= 3) {
                "Server match settings have an invalid slowdown threshold list"
            }
            val slowdownThresholds = thresholds.orEmpty().map {
                check(it is Int || it is Long && it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    "Server match settings have an invalid slowdown threshold"
                }
                (it as Number).toInt()
            }
            return MatchServerSettings(revisionCheck, response.options("proto_opt"), response.options("onsys_opt"),
                slowdownThresholds)
        }

        private fun Map<*, *>.options(name: String): String {
            val value = this[name] ?: return ""
            check(value is String) { "Server match settings have an invalid $name value" }
            return value
        }
    }
}
