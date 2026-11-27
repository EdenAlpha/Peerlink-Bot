package com.peerlink.app.godmode

/** A user-requested snapshot from Android's memory service. All input amounts are KiB. */
internal object PrimeRamReport {
    val command = """
        awk '/^Mem(Total|Available):/ {print}' /proc/meminfo || exit 1
        dumpsys -t 15 meminfo -S | sed -n '/^[[:space:]]*Total PSS by process:/,/^[[:space:]]*$/p; /^[[:space:]]*Used RAM:/p; /^[[:space:]]*ZRAM:/p'
    """.trimIndent()

    private val processRow = Regex("""^\s*([\d,]+)K:\s+(.+?)\s+\(pid\s+\d+\b""")
    private val swap = Regex("""\(\s*([\d,]+)K in swap\)""")
    private val kernel = Regex("""([\d,]+)K kernel\)""")
    private val zram = Regex("""^\s*ZRAM:\s*([\d,]+)K physical""")

    private data class ProcessMemory(val name: String, val pss: Long, val swapped: Long?)

    private fun String.kib() = replace(",", "").toLong()
    private fun mib(kib: Long) = (kib + 512) / 1024

    fun format(raw: String): String {
        fun counter(key: String) = Regex("""(?m)^$key:\s*(\d+)\s+kB""").find(raw)
            ?.groupValues?.get(1)?.toLongOrNull()
        val total = requireNotNull(counter("MemTotal"))
        val available = requireNotNull(counter("MemAvailable"))
        require(total > 0 && available in 0..total)
        require(raw.lineSequence().any { it.trimStart().startsWith("Used RAM:") })
        val processes = raw.lineSequence().dropWhile { it.trim() != "Total PSS by process:" }.drop(1)
            .takeWhile { it.isNotBlank() }.mapNotNull { line ->
                val row = processRow.find(line) ?: return@mapNotNull null
                val amount = row.groupValues[1].kib()
                val swapped = swap.find(line)?.groupValues?.get(1)?.kib()?.takeIf { it <= amount }
                ProcessMemory(row.groupValues[2], amount, swapped)
            }.toList()
        require(processes.isNotEmpty())
        // Debug.MemoryInfo's total PSS includes proportional swap on Android.
        val resident = processes.all { it.swapped != null }
        val ranked = processes.map { it.name to if (resident) it.pss - requireNotNull(it.swapped) else it.pss }
            .sortedByDescending { it.second }
        return buildString {
            appendLine("Available  ${mib(available)} of ${mib(total)} MiB")
            appendLine("In use or reserved  ${mib(total - available)} MiB")
            kernel.find(raw)?.let { appendLine("Kernel  ${mib(it.groupValues[1].kib())} MiB") }
            raw.lineSequence().mapNotNull { zram.find(it) }.firstOrNull()?.let {
                appendLine("Compressed swap in RAM  ${mib(it.groupValues[1].kib())} MiB")
            }
            appendLine()
            appendLine(if (resident) "Processes by estimated resident RAM" else "Processes by reported PSS (may include swap)")
            for ((name, amount) in ranked.take(15)) appendLine("${mib(amount)} MiB  $name")
            if (ranked.size > 15) appendLine("${mib(ranked.drop(15).sumOf { it.second })} MiB  Other processes combined")
            appendLine()
            append("Available RAM includes memory Android can reclaim.")
        }.trimEnd()
    }
}
