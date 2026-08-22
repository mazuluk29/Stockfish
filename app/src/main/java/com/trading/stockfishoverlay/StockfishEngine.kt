package com.trading.stockfishoverlay

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.Executors

data class EngineLine(
    val rank: Int,
    val move: String,
    val evaluation: String,
    val centipawns: Int?,
    val mate: Int?
)

data class EngineResult(
    val evaluation: String = "?",
    val moves: List<String> = emptyList(),
    val lines: List<EngineLine> = emptyList(),
    val error: String? = null
)

class StockfishEngine(
    private val context: Context
) {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    private val executor =
        Executors.newSingleThreadExecutor()

    private fun engineFile(): File {
        return File(
            context.applicationInfo.nativeLibraryDir,
            "libstockfish.so"
        )
    }

    fun isAvailable(): Boolean {
        return engineFile().exists()
    }

    @Synchronized
    private fun startEngine(): Boolean {

        if (process?.isAlive == true) {
            return true
        }

        val file = engineFile()

        if (!file.exists()) {
            return false
        }

        return try {

            process =
                ProcessBuilder(file.absolutePath)
                    .redirectErrorStream(true)
                    .start()

            writer =
                BufferedWriter(
                    OutputStreamWriter(
                        process!!.outputStream
                    )
                )

            reader =
                BufferedReader(
                    InputStreamReader(
                        process!!.inputStream
                    )
                )

            send("uci")

            if (!waitFor("uciok", 5000)) {
                return false
            }

            send("setoption name MultiPV value 5")
            send("isready")

            waitFor(
                "readyok",
                5000
            )

        } catch (_: Exception) {

            process = null
            writer = null
            reader = null

            false
        }
    }

    private fun send(
        command: String
    ) {

        writer?.apply {
            write(command)
            newLine()
            flush()
        }
    }

    private fun waitFor(
        wanted: String,
        timeout: Long
    ): Boolean {

        val input =
            reader ?: return false

        val start =
            System.currentTimeMillis()

        while (
            System.currentTimeMillis() -
            start <
            timeout
        ) {

            if (!input.ready()) {
                Thread.sleep(5)
                continue
            }

            val line =
                input.readLine()
                    ?: return false

            if (line.contains(wanted)) {
                return true
            }
        }

        return false
    }

    fun analyzeFen(
        fen: String,
        depth: Int = 16,
        callback: (EngineResult) -> Unit
    ) {

        executor.execute {

            if (!startEngine()) {

                callback(
                    EngineResult(
                        error =
                            "Nie udało się uruchomić Stockfisha."
                    )
                )

                return@execute
            }

            try {

                send("stop")
                send("isready")

                if (!waitFor("readyok", 3000)) {

                    callback(
                        EngineResult(
                            error =
                                "Stockfish nie odpowiada."
                        )
                    )

                    return@execute
                }

                send(
                    "setoption name MultiPV value 5"
                )

                send(
                    "position fen $fen"
                )

                send(
                    "go depth $depth"
                )

                data class MutableLine(
                    var move: String = "",
                    var cp: Int? = null,
                    var mate: Int? = null
                )

                val resultLines =
                    mutableMapOf<Int, MutableLine>()

                var bestMove: String? =
                    null

                while (true) {

                    val line =
                        reader?.readLine()
                            ?: break

                    if (
                        line.startsWith(
                            "info "
                        )
                    ) {

                        val pvNumber =
                            Regex(
                                """\bmultipv\s+(\d+)"""
                            )
                                .find(line)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
                                ?: 1

                        val move =
                            Regex(
                                """\bpv\s+([a-h][1-8][a-h][1-8][qrbn]?)"""
                            )
                                .find(line)
                                ?.groupValues
                                ?.getOrNull(1)

                        val score =
                            Regex(
                                """\bscore\s+(cp|mate)\s+(-?\d+)"""
                            )
                                .find(line)

                        val item =
                            resultLines.getOrPut(
                                pvNumber
                            ) {
                                MutableLine()
                            }

                        if (move != null) {
                            item.move = move
                        }

                        if (score != null) {

                            val type =
                                score.groupValues[1]

                            val value =
                                score.groupValues[2]
                                    .toIntOrNull()

                            if (value != null) {

                                if (type == "cp") {
                                    item.cp = value
                                    item.mate = null
                                } else {
                                    item.mate = value
                                    item.cp = null
                                }
                            }
                        }
                    }

                    if (
                        line.startsWith(
                            "bestmove "
                        )
                    ) {

                        bestMove =
                            line
                                .substringAfter(
                                    "bestmove "
                                )
                                .substringBefore(" ")
                                .trim()

                        break
                    }
                }

                if (
                    bestMove == null ||
                    bestMove == "(none)" ||
                    bestMove == "0000"
                ) {

                    callback(
                        EngineResult(
                            error =
                                "Stockfish nie znalazł ruchu."
                        )
                    )

                    return@execute
                }

                val lines =
                    resultLines
                        .toSortedMap()
                        .mapNotNull {
                                entry ->

                            val rank =
                                entry.key

                            val item =
                                entry.value

                            if (
                                item.move.isBlank()
                            ) {
                                null

                            } else {

                                val display =
                                    when {

                                        item.mate != null -> {

                                            val mate =
                                                item.mate!!

                                            if (mate >= 0) {
                                                "M$mate"
                                            } else {
                                                "-M${-mate}"
                                            }
                                        }

                                        item.cp != null -> {

                                            String.format(
                                                Locale.US,
                                                "%.2f",
                                                item.cp!! / 100.0
                                            )
                                        }

                                        else ->
                                            "?"
                                    }

                                EngineLine(
                                    rank = rank,
                                    move = item.move,
                                    evaluation = display,
                                    centipawns = item.cp,
                                    mate = item.mate
                                )
                            }
                        }
                        .take(5)

                val finalLines =
                    if (lines.isEmpty()) {

                        listOf(
                            EngineLine(
                                rank = 1,
                                move = bestMove,
                                evaluation = "?",
                                centipawns = null,
                                mate = null
                            )
                        )

                    } else {

                        lines
                    }

                callback(
                    EngineResult(
                        evaluation =
                            finalLines.first()
                                .evaluation,

                        moves =
                            finalLines.map {
                                it.move
                            },

                        lines =
                            finalLines
                    )
                )

            } catch (
                error: Exception
            ) {

                callback(
                    EngineResult(
                        error =
                            "Stockfish: " +
                                (
                                    error.message
                                        ?: error.javaClass.simpleName
                                )
                    )
                )
            }
        }
    }

    fun shutdown() {

        runCatching {
            send("quit")
        }

        runCatching {
            process?.destroy()
        }

        process = null
        writer = null
        reader = null

        executor.shutdownNow()
    }
}
