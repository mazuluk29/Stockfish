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

    fun isAvailable(): Boolean =
        engineFile().exists()

    private fun engineFile(): File =
        File(
            context.applicationInfo.nativeLibraryDir,
            "libstockfish.so"
        )

    @Synchronized
    private fun ensureStarted(): Boolean {

        if (process?.isAlive == true)
            return true

        val file = engineFile()

        if (!file.exists())
            return false

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
        send("setoption name MultiPV value 10")

        return true
    }

    private fun send(command: String) {

        writer?.apply {

            write(command)
            newLine()
            flush()
        }
    }

    fun analyzeFen(
        fen: String,
        depth: Int = 14,
        callback: (EngineResult) -> Unit
    ) {

        executor.execute {

            if (!ensureStarted()) {

                callback(
                    EngineResult(
                        error =
                        "Brak libstockfish.so dla arm64-v8a"
                    )
                )

                return@execute
            }

            send("stop")
            send("position fen $fen")
            send("go depth $depth")

            var evaluation = "?"

            val moves =
                linkedMapOf<Int, String>()

            while (true) {

                val line =
                    reader?.readLine()
                        ?: break

                if (line.startsWith("info ")) {

                    val pv =
                        Regex(
                            "multipv (\\d+).*?score (cp|mate) (-?\\d+).*? pv ([a-h][1-8][a-h][1-8][qrbn]?)"
                        ).find(line)

                    if (pv != null) {

                        val idx =
                            pv.groupValues[1].toInt()

                        val scoreType =
                            pv.groupValues[2]

                        val score =
                            pv.groupValues[3]

                        val move =
                            pv.groupValues[4]

                        moves[idx] = move

                        if (idx == 1) {

                            evaluation =
                                if (scoreType == "cp") {

                                    "%.2f".format(
                                        score.toInt() / 100.0
                                    )

                                } else {

                                    "M$score"
                                }
                        }
                    }
                }

                if (
                    line.startsWith("bestmove ")
                ) {
                    break
                }
            }

            callback(
                EngineResult(
                    evaluation = evaluation,
                    moves =
                    moves
                        .toSortedMap()
                        .values
                        .toList()
                )
            )
        }
    }

    fun shutdown() {

        executor.execute {

            try {
                send("quit")
            } catch (_: Exception) {
            }

            process?.destroy()
            process = null
        }
    }
}

data class EngineResult(
    val evaluation: String = "?",
    val moves: List<String> = emptyList(),
    val error: String? = null
)
