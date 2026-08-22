package com.trading.stockfishoverlay

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

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

        val file =
            engineFile()

        if (!file.exists()) {
            return false
        }

        return try {

            process =
                ProcessBuilder(
                    file.absolutePath
                )
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

            waitFor(
                "uciok",
                5000
            )

            send(
                "setoption name MultiPV value 5"
            )

            send("isready")

            waitFor(
                "readyok",
                5000
            )

            true

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

            if (
                line.contains(
                    wanted
                )
            ) {
                return true
            }
        }

        return false
    }

    fun analyzeFen(
        fen: String,
        depth: Int = 14,
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

                if (
                    !waitFor(
                        "readyok",
                        3000
                    )
                ) {

                    callback(
                        EngineResult(
                            error =
                                "Stockfish nie odpowiada."
                        )
                    )

                    return@execute
                }

                send(
                    "position fen $fen"
                )

                send(
                    "go depth $depth"
                )

                val moves =
                    linkedMapOf<Int, String>()

                var evaluation =
                    "?"

                var gotScore =
                    false

                var bestMove:
                    String? = null

                var diagnostic =
                    ""

                while (true) {

                    val line =
                        reader?.readLine()
                            ?: break

                    /*
                     * Zachowujemy ostatnią odpowiedź,
                     * żeby łatwiej wykryć błędny FEN.
                     */
                    if (
                        line.isNotBlank()
                    ) {
                        diagnostic =
                            line.take(200)
                    }

                    if (
                        line.startsWith(
                            "info "
                        )
                    ) {

                        val multiPv =
                            Regex(
                                """\bmultipv\s+(\d+)"""
                            )
                                .find(line)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
                                ?: 1

                        val scoreMatch =
                            Regex(
                                """\bscore\s+(cp|mate)\s+(-?\d+)"""
                            )
                                .find(line)

                        val pvMatch =
                            Regex(
                                """\bpv\s+([a-h][1-8][a-h][1-8][qrbn]?)"""
                            )
                                .find(line)

                        if (
                            scoreMatch != null
                        ) {

                            val type =
                                scoreMatch
                                    .groupValues[1]

                            val raw =
                                scoreMatch
                                    .groupValues[2]
                                    .toIntOrNull()

                            if (raw != null) {

                                gotScore = true

                                if (
                                    multiPv == 1
                                ) {

                                    evaluation =
                                        if (
                                            type ==
                                            "cp"
                                        ) {

                                            String.format(
                                                java.util.Locale.US,
                                                "%.2f",
                                                raw / 100.0
                                            )

                                        } else {

                                            if (
                                                raw >= 0
                                            ) {
                                                "M$raw"
                                            } else {
                                                "-M${-raw}"
                                            }
                                        }
                                }
                            }
                        }

                        if (
                            pvMatch != null
                        ) {

                            val move =
                                pvMatch
                                    .groupValues[1]

                            moves[multiPv] =
                                move
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
                                "Stockfish nie znalazł ruchu. " +
                                "Najprawdopodobniej pozycja została źle rozpoznana."
                        )
                    )

                    return@execute
                }

                /*
                 * Jeżeli MultiPV nie zwróciło żadnego PV,
                 * przynajmniej pokaż bestmove.
                 */
                if (
                    moves.isEmpty()
                ) {

                    moves[1] =
                        bestMove
                }

                if (!gotScore) {

                    callback(
                        EngineResult(
                            evaluation = "?",
                            moves =
                                moves
                                    .toSortedMap()
                                    .values
                                    .toList(),

                            error =
                                "Stockfish znalazł ruch $bestMove, " +
                                "ale nie zwrócił oceny. Ostatnia odpowiedź: $diagnostic"
                        )
                    )

                    return@execute
                }

                callback(
                    EngineResult(
                        evaluation =
                            evaluation,

                        moves =
                            moves
                                .toSortedMap()
                                .values
                                .distinct()
                                .take(5)
                                .toList()
                    )
                )

            } catch (
                e: Exception
            ) {

                callback(
                    EngineResult(
                        error =
                            "Błąd Stockfisha: " +
                            (
                                e.message
                                    ?: e.javaClass.simpleName
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

data class EngineResult(
    val evaluation: String = "?",
    val moves: List<String> = emptyList(),
    val error: String? = null
)
