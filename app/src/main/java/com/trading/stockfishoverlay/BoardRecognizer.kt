package com.trading.stockfishoverlay

import android.graphics.Bitmap
import android.util.Base64
import kotlin.math.sqrt

class BoardRecognizer {

    data class BoardArea(
        val left: Int,
        val top: Int,
        val size: Int
    )

    data class Result(
        val area: BoardArea,
        val boardFen: String,
        val whiteAtBottom: Boolean,
        val confidence: Double
    )

    private data class Template(
        val piece: Char,
        val data: FloatArray
    )

    /*
     * Kolory planszy z Twojego motywu Chess.com.
     * Jest spora tolerancja, więc highlight ruchu
     * nie powinien rozwalać lokalizacji planszy.
     */
    private val lightSquare =
        doubleArrayOf(237.0, 222.0, 189.0)

    private val darkSquare =
        doubleArrayOf(196.0, 142.0, 86.0)

    /*
     * Każda figura ma po 2 wzorce:
     * - na jasnym polu
     * - na ciemnym polu
     *
     * Wzorce zostały wyciągnięte z dwóch
     * przesłanych przez Ciebie screenów.
     */
    private val rawTemplates =
        listOf(

            // BLACK ROOK
            'r' to "DAwMDAwMDAwMDAwMDAwMDAwFBAQHDAkDAgMLCwMCAgcL/wH//wYB+/v5AwT59/cCDP8CAP/+/fz7+vn49/f2Agz//f38/Pv6+fj39vb29gMMCQH7+vr5+fj39vb2+gQLDAwMBvz9/Pv5+Pb1+woMDAwMDAn/AP79/Pr39v0MDAwMDAwJ/wD+/vz6+Pb9DAwMDAwMCf7//v38+vj2/QwMDAwMDAX8/Pz7+fj39voIDAwMCwL///79/Pr5+Pf2+AUMDAj+AP/+/fv6+vj39vX7CwYD/f79/fz6+vn49/b1+QP9Af///v38+/r5+Pf29vb2/f/+/v79/Pv7+/r5+fn5+Q==",
            'r' to "CwsMDAwLDAsLCwwLCwsLCwsFBQUHCwkDAwMKCgMCAgcL/wUD/wcB/Pz5AwT49/UACwAHBQIB//78+vj39vb1AAr+AP///vz6+ff29fT09AELCP75+vr5+Pj29PPz9wEJCwwLBP3//fv59/Tz+AgMCwsMDAcCBQIA/vr29PoLDAsLDAwHAQQBAP769/X6CwwLCwwLBwAEAgD++vf1+gsMCwsMCwT8/f37+ff18/cICwsLCgICBAIB/vv59/X09wQLCwcABQMCAP37+vj29PP5CgUC/wIBAP77+vn39fTz9gP/BQICAgD9/Pr5+Pb19PT1/gIBAAD//fz7+vn49/j39w==",

            // BLACK KNIGHT
            'n' to "DgYIDQ4JCw4ODw8PDw8PDw4B/v8CAP0JDQ4PDw8PDw8PBgL//P/6+Pv9AAUKDg8PDgUIBgH++/n6+vj3+P4JDwUGBv7+/fz9/fz7+vj2+AICBfv3/fz8/v38/Pv6+Pf2BwL8+/z+AAD9/Pz8+/r49gcA/Pz+AgH/+Pn8/Pv6+PYB/v39/v79+/b5+/v7+vj3/Pz8+/wABQX4+vz7+/r49vv5+fwKDg3/+vz8+/v6+Pf5+PsJDwoA/f38/Pv7+vj2CggLDggEAf38/Pv7+/n39g4ODgkJCP/8+/v7+/n39vYPDg4FDwj//Pr7+/r49/b2Dw8PBQYC/vz7+/v6+fn4+A==",
            'n' to "DwgKDg8LDQ8PDw8PDw8PDw8B/QAE//8LDw8PDw8PDw8PBv/9/P36+/4AAwgMDw8PDgUDAv78+/n6+vn5+wILDwYBAv39/Pv8/Pv7+vn4+gUBAfv4/Pv8/Pz7+/v6+fj4Av/7+/v8/v38+/v7+/r5+AL++/v9///9+fr7+/v6+fj//Pz8/f38+/j5+/v7+vn4/Pz7+/wABAb6+vv7+/r5+Pv6+v0LDw0B+vv7+/v6+fj6+fwJDwsB/Pz7+/v7+vn4CgcLDwkC/vz8+/v7+/r4+A8PDwsEA/37+vv7+vr5+PcPDw8FBwP9+/r6+/r5+Pj3Dw8PBAMB/v38/Pz7+/r6+g==",

            // BLACK BISHOP
            'b' to "CQkJCQkJBv/8AwkJCQkJCQkJCQkJCQH79/oICQkJCQkJCQkJCQkF+fb/CQkJCQkJCQkJCQkGAPv4+gQICQkJCQkJCQgD//77+Pf3/gYJCQkJCQgAAP/79/b39vb5BQkJCQkD/wD89/Tz9fb29vwJCQkJAf79+vf19Pb29vb6CAkJCQT7+/n39vf39vb1/gkJCQkIAvv39vb19fX3/gcJCQkJCQkC+vr49vb1/AgJCQkJCQkI/f37+Pb19fcFCQkJCQkJBvv39PPz8/b5AgkJCf//AAID/vb19PX6AgIA/v75+Pj4+vr6+vv39/f29vb4///8+/n6/gUHAPr4+fv+AA==",
            'b' to "CAkJCQkJB//8AwkICAgICAgICQkJCQH++PkICAgICAgJCAkJCQkE+fX+CAgICAgICQkJCQgGAf35+gQICAgJCQgJCQgDAgT++vj3/gYICQkICQcBBQX/+fb49/b4BQkICAkDBAYA+PPy9fb29fsJCAgJAQQC/fjz8/b29fX4CAgICQP+//v49/f39vX0/QgICAkIAPv59/b19fX2/AcJCAgJCQgB+/z49vX0+wcJCQgICAkI/gP/+fb19fYECQkICAgIBfj28vLy8vP1AQkICAAAAQIC/fXz8vT5AQIA//78+vn6/Pz8+fj3+Pf29ff5/f36+Pn6/AMG/vj39vj8/g==",

            // BLACK QUEEN
            'q' to "Dg4KCQ0ODQQBBg4OCggLDg4HA/8ADQYC//wMCQH+/QsOBgH9/w0L//kDDgj9+/wLDQ4I+QUPDwT6DA4N/P4MDw4PDP7/DQ4A/AcOB/wADw4DDgsC/gYMAP4CDQD8AA8M/QUKBQH+BwP//Qf//P8MAAL7AgQC+/wD/vn9APv9AgEF/vkAAfz6Avz5/v75+AAAAP37/fz6+fn29/n29vr9+vr+//7+/Pr4+Pf39vX19fMH/gEA/fz6+Pf39/b19PP6DQAFAf38+vn49/f29fTzAwkECgUB/vv5+Pj39/f29f0C+vj29vb29vb39/f39vX2CwYC/vz7+fj4+Pr8/QAECA==",
            'q' to "Dg4KCg0ODAQCCA8OCgkMDg4HA/8CDgUD/v0NCAH+/wwPBgH9AA4J/voEDgb++/0MDw0F+gcPDgH7DQ4M+wALDw4PCf4ADg4A+woOBvsDDw4GDwkC/gkLAP0FDQD8Ag8L/AcIAwAABgL+/wb/+wAMAAD8AgMB+/0E/fr+APr9AQEF/fgBAPv6Avz5//75+AD/Av36/fv6+vn29/r29vv9+vn+/v7+/Pn5+Pf39vX19fQE/wH//vz6+Pf39vb19PP7CwAFAf37+fn39/b29fT0BQkECgUB/vv5+Pj39/f29QAA+/n39vb29vb39/f39vX3CgQA/Pr5+fj5+fn6+/8DBw==",

            // BLACK KING
            'k' to "Dw8PDw8PDwkGDg8PDw8PDw8PDw8PDgf9+wUNDw8PDw8PDw8PDw8MAf4LDg8PDw8PDAsLCwwOCAD8BA0NCwsLCwIB//7+AP8A/PoAAgD//v0CAP/9/Pr6/fv5AAH+/fz8Af/+/Pv6+Pn5/AD+/f38+wD+/Pv6+fn4+f/+/v39/Pv//fv6+vn5+Pv+/v39/Pv6/fz7+vn5+Pj6/Pz8/Pv6+fz6+vr6+vr6+vr5+fj49/kGAQH//fz7+vn5+fn4+PcAC/8A/v38+/v6+fj49/f3BQcDAwD+/Pv6+fn4+Pf39/8B+/n4+Pj4+Pj4+fj4+Pf6CwcDAP79+/r6+/3+AAIGCg==",
            'k' to "Dw8PDw8PDwoFDg8PDw8PDw8PDw8PDwgA+gUNDw8PDw8PDw8PDw8LA/wJDg8PDw8PDQsLCw0OCgH8BA0NDAsLDAIBAP//Af8B/foBAgD//v4CAf/9/Pr6/fv5/wH//fz8AQD+/fv6+fn6+wD+/f38+wD+/fv6+fn4+P7+/v39/Pv//fz6+vn5+Pr+/v39/Pv6/fz7+vn5+fj6/fz9/Pv6+fz6+vn6+vr6+vr5+fj49/gH/wH//fz7+vr6+fn4+Pf9DAAA/vz8+/v6+fj49/f2AwkCAwD+/fz6+fn4+Pj39/0D/Pr5+Pj4+Pj4+Pj49/f4CwYC/vz7+vr6+/v8/QAECA==",

            // BLACK PAWN
            'p' to "CAgJCQkICQgICAkICAgICAgICQkJCAYDAwMHCAgICAgJCAkJBwECAf76+QEICAgICAkJCQEFAvz5+Pb2AwgICQgJCAj/BP76+Pf18/8ICQgICQgI///8+ff18/QDCAkICAkICAX8+Pb08/P+BwgJCAgJCAQB//v59/X09vsHCQgICQj/A/76+Pb09PX2AwkICAkIBgX9+fj18/QCBQgJCAgICQgE+/z59/Xz+wYJCQgICAgC/f37+ff29PP5BQgICAYAAQH++vj39vX08/YBCAgAAwT//Pn39vb19PPz9AIF/wYC/vv49/b29fTz8/P6Bf3+/fv6+fj4+Pj39/f3+w==",
            'p' to "CQkJCQkJCQkJCQkJCQkJCQkJCQkJCAYEAwQHCQkJCQkJCQkJBwD+/fv5+gMJCQkJCQkJCAAA/fn49/b4BgkJCQkJCQf9//v59/b19QMJCQkJCQkI/vz6+Pf19fcFCQkJCQkJCQX79/b19PYBCAkJCQkJCQP//Pn49/X2+P4ICQkJCQj+//v49/b19fb3BAkJCQkJBwb9+Pf29PYEBggJCQkJCQkE+vr49/X1/ggJCQkJCQgC+/r5+Pf29fX8BgkJCQf//f36+Pf39vX19PgDCAj///78+vj39vb29fX09wUF/QD9+/n39/b29fX09PT+Bf39/Pv6+vn5+fn5+Pj4/g==",

            // WHITE ROOK
            'R' to "/v/////+//7+/v/+/v7+/v74+vr5/vv4+ff9/ff49vn+9Q0M9vr1BAv69vf2Avvz/fYSEgsJCQ8QCQIAAgX98/3yAAQEBAMDAgD9+/r58vP++vP6AwQEBAMB/vz58vP8/v/+9/gEAwMC//v48Pv//v7///r/ExISEA0IAvP+//7+///6/xMSEhEOCQPz/v/+/v//+v8TEhIQDgkD8/7//v7//vf5AgEBAP/8+PD7//7+/fT+CgsKCggGAwD68fb+/vn7CgoKCQgHBgQA/frv/Pj1+wgICAcGBgUDAP367/f6CwoLCwsKCQkIBgQBAP//9gMCAgICAQEA//79/Pz8+w==",
            'R' to "CQkJCQkJCQkJCQkJCQkJCQn8/fz/CQL7/PoGB/r7+QAI9QgH9//3/wf3+vvz/fb3B/YMDAYFBQkKBP78/f/49wjz+v7+/v39/Pr49vXz7fgJA/f3/v///v77+Pf08foGCQkJ/vT//v79+vfz7wUJCQkJCQL8DQwMCgcC/fMJCQkJCQkD/AwMDAsIA/30CQkJCQkJA/sMDAsKCAP99AkJCQkJCPz2/v79/fv49O8CCQkJB/X4AgMDAgH//Pn07fsJCQH4BQQEAwMCAP76+PXvB/369wIBAQEAAP79+vf17fz2BwYHBwcGBQQDAv/9/Pv78vz7+/v7+vr6+fj39vb29Q==",

            // WHITE KNIGHT
            'N' to "Bff8BAX9AQYGBgYGBgYGBgTu9/Py9fD+BQYGBgYGBgYG9f//8gT56PHz9PkABQYGBPMACAUGAvYAA/748fL9BfX8BfX+BAQGBwcGBAD57/TvBu/h/gIFBwcHBwYFAPzx+gcBAQMFBwcCBQcHBgQA/AgGAwMGCAgF8QAHBwcGAv8HBgUGBgQA9OgBBwcGBQIBAQYE//b09fPuAwYHBgQCAQH8/e/9BALx/QYHBwcFAwHu7vD5Bv7z/wYGBwcHBgMB/Pn/Bfn3BAYGBgYGBgUDAAYGBv34CAYGBQYGBgYFA/8GBgbzBwkGBQUGBgYGBAL+BgYG8vr5+Pf39/f39/b18w==",
            'N' to "+fP2+fr1+Pr6+vv6+vr6+vry/fbx+/T1+fr7+vr6+vr79AME+gf/7/T19PT3+fr6+fQECgcIBf0EBgP++PP2+vMBBvkBBgcICQkIBwT/9vL0CPbuAwUICQkICQkHBAH5AAkFBQYICQkEBwkJCAcEAQkIBgYICQkH+AMJCQkIBQMJCAgICAYD+PEECAkIBwUEBAgHAvn19fL2BggJCAcFBQQAAfP3+vj0AggJCQkHBgXz9fX2+/f3BAgICQkICAYE9vX3+vX8BwgICAgICAgGBPr6+vX9CggICAgICAgHBgP6+vr0CQoICAgICAgIBwUC+vr68/r6+fn4+fj5+Pj39g==",

            // WHITE BISHOP
            'B' to "/wAAAAD//ff3+gD/////////AAAAAPgICvf///////8A/wABAAD6+Pv2////////AAAAAP/9+QUI+fv///8AAP8AAP/5/g4TEg3+9/z/AAD/AP74ChQUCgIQDgf4+wD//wD5CBYTBPPq/goNCPUA//8A+A8UEgf27AIMDQz2////APkDExIRDAgQDw0F9v///wD/+P8ICAcGBgX/9/0A//8AAP/3/goKCggB8/4AAP///wD/9AgKBgYHB/f6AAD/////++zz9vr7+fXt9QD///v6+fr59fr89f/0+Pr5+fkLCQcHCAoM+vMHCQUEBQcI+Pj3+P389/r89/n9+Pf4+Q==",
            'B' to "BwcHBwcHAvj3/QcHBwcHBwcHBwcHB/kAAvQFBwcHBwcHBwcHBwf/8vL3BwcHBwcHBwcHBwcC+f8B9/8HBwcHBwcHBwX8+wYJBwT6+AIHBwcHBwX4AwoJAPkGBP/1AAcHBwf8AAsJ+erj8wAD//UHBwcH+gUKCP/w6PsCBAP0BgcHB/77CAgHBAAGBQP89wcHBwcG/Pn9/v39/Pv4+AQHBwcHBwf7+AICAQD59AYHBwcHBwcF8/7+/Pz8/fMABwcHBwcHAu7u7vHy8O/u+wcHB/v6+v3+9vX17/ny/P36+PkB//7/AAIC9fL9AP79/f7++vn29vb2+AEE+vb19vb4+g==",

            // WHITE QUEEN
            'Q' to "DQwHBQoNCv/8AwwMBwQJDQ0CAAP7C/8CCfgJBfwB+gkM/wIF+goG+vj8CwP7A/kHCgsB7v8NDf3xBw0L8vQHDQwNCPj6Cwz6/gINA/j5DQ3+DAf/AQII+wb7C/oC+Q0K/wAGAwv6AAMM+gH9BvoJ+Q3++wQPAPMIDfr0CAj4+wQMDPgCDAn5CwkD/woH8wEK/wUEAfz//fz6+/z4+P4DAe/9AgIHBQEA//39/vv3+O8A+gABAwIB//38/fz59fPyCvYCAAAAAP/9/Pv69/bx+wb7CQgHBQMCAAAAAP/++fb57/b3+Pn5+vn6+fn49fLpB//79/X19vb39vX19vn9AA==",
            'Q' to "DQsFBQsNCP38Aw0MBgQJDQ0AAgP8DP0GCfgLAf0C+QoN/wIC+wwF+Pf/DQH8AfkKDQv/8AENDfn0CQ0J8vkJDQ0NBPv6DAv7/AQNAPr9DQ3/DAMB/wQF/gX+CfkD+w0H/gECAwr6/wUK+P8ABfkH+Q39+AMP/vMMDfj2CQb0+gcPC/YCCwj5CgcBAgkF8gULAQQE//3++/z6+vv3+P8BAO4BBAMGBQIA//7+/vr4+O7/+QEBAgMDAP7+/vv49fH0CPQCAP8BAwL///z49vbw/wP8CQcFBAIB//////79+Pj08PX29/n6+vr6+vj39fHqBwD8+Pj29fX19fb4+Pr/BA==",

            // WHITE KING
            'K' to "/v/////+//j1/v/+/v7+/v7+///+/vfs6fX9/v7+/v7//v///v/67+v5/v7+/v7+/fz7/Pz++vf1+P39+/v7/P8EBAL99/oNC/z0/AABAf4ODg0MCwb6Cwr7BQ0MCwoKDg0MCwoJ/wAE/g4ODQwKCQ4NDAsKCAX4+QkODQ0MCggMDAsKCAcF+/sNDQ0MCgcFCwoJBwUDAfv7BwgJCAYEA/3//v4AAAAA/wD//vv5+fj2AwwLCQcGBQQDAQAAAPvy+vcCAgICAwIB//z7+fjz9fcDCwgGBAMCAQD////++/Pw9fj5+vr7+/v7+/r59vPs+vf29PT09vf39vX19PX3+A==",
            'K' to "/v7////+//ny/v/+/v7+/v7+///+/vft5vL8/v7+/v7//v///v/88un5/v7+/v7+/Pv7+/v++vn3+Pz8+/v7+wAEBQP/+PcNC//0/QACAv8ODg0MCwj6CQr9BA0MCwoKDg0MCwoJAf0F/Q4ODQwKCQ4NDAsKCAb5+AgNDQ0MCggNDAsKCAcF/foNDQ0MCgcFCwoJBwUDAfz8BwcIBwYEA/v9//8AAQICAgIA/vv5+Pb3/wwLCAYFAwIBAAAAAP3x+/cBAwQEBAMCAP79+/j09PgACwcFAwMBAAD//v7+/fLy8/f4+fr8/Pz8/Pr59/Tr+/j39fX19fX19fX29fX4+Q==",

            // WHITE PAWN
            'P' to "BgYGBgYGBgcGBgYGBgYGBgYGBgYGBgH9/f4DBgYGBgYGBgYHBPj9AwP88/oGBgYGBgYHBvYCCwkGAvvu/gcGBgYGBgX0CQkHBAD68vYGBgYGBgYG9QIHBAD8+O/8BgYGBgYGBgDy9/z59ez0BAYGBgYGBv74+/v9+/bx8fMDBgYGBgbz/v79/fr38/Tu+wYGBgYGAwHz+//89+36AQUGBgYGBgb+9QYGA/718gMGBgYGBgX7+AUIBgQB/fTx/wYGBgP3/wgIBwYFAwD89/D5BgX2AgoJCAcHBgQB/vz57/sA9goKCAgHBgUDAf/8+vbw//D39vb19fX08/Px8O/u7Q==",
            'P' to "/v7///7+//7+/v/+/v7+/v7+///+/vv6+vr9/v7+/v7//v//+/gECwoC9/j+/v7+/////vYMExEOCgLz+v7+//7//vz5ExEPDAcC+Pb+//7+//799gwPDAgEAPT5/v/+/v/+/vn1AAQB/PL0/f7//v7//vj8AQQFA/759vT9/v7+//32BgUGBQL/+/rz+f7+/v/++/vzBQcE//H4+/7+/v7+//74/Q8OCgb89Pz//v7+/v34/w4QDgwJBfv0+/7+/vv4CBEQDw4NCwgE//X3/v32DBIREBAPDgwJBgQB9Pn5/xMSEBAPDg0LCQcEAv3z+Pb8+/v6+vr5+Pj29fTz8Q=="
        )

    private val templates: List<Template> =
        rawTemplates.map {
            Template(
                piece = it.first,
                data = decodeTemplate(it.second)
            )
        }

    fun recognize(
        bitmap: Bitmap
    ): Result? {

        val area =
            findBoard(bitmap)
                ?: return null

        val whiteAtBottom =
            detectOrientation(
                bitmap,
                area
            )

        val board =
            Array(8) {
                CharArray(8) { '.' }
            }

        var confidenceSum = 0.0
        var detectedPieces = 0

        for (screenRow in 0..7) {

            for (screenCol in 0..7) {

                val recognition =
                    recognizeSquare(
                        bitmap,
                        area,
                        screenRow,
                        screenCol
                    )

                val piece =
                    recognition.first

                if (piece == '.') {
                    continue
                }

                detectedPieces++
                confidenceSum += recognition.second

                val fenRow: Int
                val fenCol: Int

                if (whiteAtBottom) {

                    fenRow =
                        screenRow

                    fenCol =
                        screenCol

                } else {

                    fenRow =
                        7 - screenRow

                    fenCol =
                        7 - screenCol
                }

                board[fenRow][fenCol] =
                    piece
            }
        }

        if (detectedPieces < 2) {
            return null
        }

        return Result(
            area = area,
            boardFen = boardToFen(board),
            whiteAtBottom = whiteAtBottom,
            confidence =
                confidenceSum /
                    detectedPieces
        )
    }

    private fun findBoard(
        bitmap: Bitmap
    ): BoardArea? {

        /*
         * Na Twoim telefonie plansza Chess.com
         * ma praktycznie pełną szerokość ekranu.
         */
        val size =
            bitmap.width

        val cell =
            size / 8f

        var y = 150

        val end =
            bitmap.height -
                size -
                50

        while (y <= end) {

            var matchesNormal = 0
            var matchesReverse = 0

            for (col in 0..7) {

                val x =
                    (
                        col * cell +
                            cell * 0.50f
                    ).toInt()

                if (
                    x !in 0 until bitmap.width ||
                    y !in 0 until bitmap.height
                ) {
                    continue
                }

                val pixel =
                    bitmap.getPixel(x, y)

                val normal =
                    if (col % 2 == 0)
                        lightSquare
                    else
                        darkSquare

                val reverse =
                    if (col % 2 == 0)
                        darkSquare
                    else
                        lightSquare

                if (
                    colorDistance(
                        pixel,
                        normal
                    ) < 55.0
                ) {
                    matchesNormal++
                }

                if (
                    colorDistance(
                        pixel,
                        reverse
                    ) < 55.0
                ) {
                    matchesReverse++
                }
            }

            if (
                matchesNormal >= 6 ||
                matchesReverse >= 6
            ) {

                return BoardArea(
                    left = 0,
                    top = y,
                    size = size
                )
            }

            y += 2
        }

        return null
    }

    private fun recognizeSquare(
        bitmap: Bitmap,
        area: BoardArea,
        row: Int,
        col: Int
    ): Pair<Char, Double> {

        val featureResult =
            createFeature(
                bitmap,
                area,
                row,
                col
            )

        val feature =
            featureResult.first

        val contrast =
            featureResult.second

        /*
         * Po zwiększeniu marginesu pasek ewaluacji
         * po lewej nie powinien być już traktowany
         * jako figura.
         *
         * Puste pola mają u Ciebie zwykle kontrast
         * około 0-2. Figury najczęściej >20.
         */
        if (contrast < 8.0) {

            return Pair(
                '.',
                1.0
            )
        }

        var bestPiece = '.'
        var bestScore = -1.0

        for (template in templates) {

            val score =
                dot(
                    feature,
                    template.data
                )

            if (score > bestScore) {

                bestScore =
                    score

                bestPiece =
                    template.piece
            }
        }

        /*
         * Przy prawidłowych figurach z Twojego
         * motywu wyniki zwykle są ~0.8-1.0.
         */
        if (bestScore < 0.50) {

            return Pair(
                '.',
                bestScore
            )
        }

        return Pair(
            bestPiece,
            bestScore
        )
    }

    private fun createFeature(
        bitmap: Bitmap,
        area: BoardArea,
        row: Int,
        col: Int
    ): Pair<FloatArray, Double> {

        val cell =
            area.size / 8f

        /*
         * Duży margines z lewej/prawej jest celowy.
         * Evaluation bar znajduje się przy lewej
         * krawędzi planszy i wcześniej zakłócał
         * pierwszy plik pól.
         */
        val left =
            (
                area.left +
                    col * cell +
                    cell * 0.20f
                ).toInt()

        val right =
            (
                area.left +
                    (col + 1) * cell -
                    cell * 0.20f
                ).toInt()

        val top =
            (
                area.top +
                    row * cell +
                    cell * 0.08f
                ).toInt()

        val bottom =
            (
                area.top +
                    (row + 1) * cell -
                    cell * 0.08f
                ).toInt()

        if (
            left < 0 ||
            top < 0 ||
            right > bitmap.width ||
            bottom > bitmap.height ||
            right <= left ||
            bottom <= top
        ) {

            return Pair(
                FloatArray(256),
                0.0
            )
        }

        val crop =
            Bitmap.createBitmap(
                bitmap,
                left,
                top,
                right - left,
                bottom - top
            )

        val scaled =
            Bitmap.createScaledBitmap(
                crop,
                16,
                16,
                true
            )

        crop.recycle()

        val values =
            FloatArray(256)

        var sum = 0.0

        for (y in 0 until 16) {

            for (x in 0 until 16) {

                val pixel =
                    scaled.getPixel(
                        x,
                        y
                    )

                val r =
                    (pixel shr 16) and 255

                val g =
                    (pixel shr 8) and 255

                val b =
                    pixel and 255

                val gray =
                    (
                        r * 0.299 +
                            g * 0.587 +
                            b * 0.114
                        ).toFloat()

                values[
                    y * 16 + x
                ] = gray

                sum += gray
            }
        }

        scaled.recycle()

        val mean =
            sum / 256.0

        var variance = 0.0
        var norm = 0.0

        for (i in values.indices) {

            val original =
                values[i]

            val centered =
                (
                    original -
                        mean
                    ).toFloat()

            values[i] =
                centered

            variance +=
                (
                    original -
                        mean
                    ) *
                    (
                        original -
                            mean
                    )

            norm +=
                centered *
                    centered
        }

        val contrast =
            sqrt(
                variance /
                    256.0
            )

        norm =
            sqrt(norm)

        if (norm > 0.00001) {

            for (i in values.indices) {

                values[i] =
                    (
                        values[i] /
                            norm
                        ).toFloat()
            }
        }

        return Pair(
            values,
            contrast
        )
    }

    private fun detectOrientation(
        bitmap: Bitmap,
        area: BoardArea
    ): Boolean {

        /*
         * Przy pozycjach z figurami na pierwszej
         * i ósmej linii działa bardzo dobrze.
         *
         * WHITE AT BOTTOM:
         * dolny rząd jest jaśniejszy.
         *
         * BLACK AT BOTTOM:
         * górny rząd jest jaśniejszy.
         */
        val top =
            rowBrightness(
                bitmap,
                area,
                0
            )

        val bottom =
            rowBrightness(
                bitmap,
                area,
                7
            )

        return bottom > top
    }

    private fun rowBrightness(
        bitmap: Bitmap,
        area: BoardArea,
        row: Int
    ): Double {

        val cell =
            area.size / 8f

        var total = 0.0
        var count = 0

        for (col in 0..7) {

            val x =
                (
                    area.left +
                        col * cell +
                        cell * 0.50f
                    ).toInt()

            val y =
                (
                    area.top +
                        row * cell +
                        cell * 0.50f
                    ).toInt()

            if (
                x !in 0 until bitmap.width ||
                y !in 0 until bitmap.height
            ) {
                continue
            }

            val pixel =
                bitmap.getPixel(x, y)

            val r =
                (pixel shr 16) and 255

            val g =
                (pixel shr 8) and 255

            val b =
                pixel and 255

            total +=
                (
                    r +
                        g +
                        b
                    ) / 3.0

            count++
        }

        return if (count > 0)
            total / count
        else
            0.0
    }

    private fun decodeTemplate(
        encoded: String
    ): FloatArray {

        val bytes =
            Base64.decode(
                encoded,
                Base64.DEFAULT
            )

        val result =
            FloatArray(
                bytes.size
            )

        var norm = 0.0

        for (i in bytes.indices) {

            /*
             * Byte w Kotlinie jest signed:
             * dokładnie tak były zapisane wzorce.
             */
            result[i] =
                bytes[i].toFloat()

            norm +=
                result[i] *
                    result[i]
        }

        norm =
            sqrt(norm)

        if (norm > 0.00001) {

            for (i in result.indices) {

                result[i] =
                    (
                        result[i] /
                            norm
                        ).toFloat()
            }
        }

        return result
    }

    private fun dot(
        a: FloatArray,
        b: FloatArray
    ): Double {

        val size =
            minOf(
                a.size,
                b.size
            )

        var result = 0.0

        for (i in 0 until size) {

            result +=
                a[i] *
                    b[i]
        }

        return result
    }

    private fun colorDistance(
        pixel: Int,
        reference: DoubleArray
    ): Double {

        val r =
            ((pixel shr 16) and 255)
                .toDouble()

        val g =
            ((pixel shr 8) and 255)
                .toDouble()

        val b =
            (pixel and 255)
                .toDouble()

        val dr =
            r - reference[0]

        val dg =
            g - reference[1]

        val db =
            b - reference[2]

        return sqrt(
            dr * dr +
                dg * dg +
                db * db
        )
    }

    private fun boardToFen(
        board: Array<CharArray>
    ): String {

        return buildString {

            for (row in 0..7) {

                var empty = 0

                for (col in 0..7) {

                    val piece =
                        board[row][col]

                    if (piece == '.') {

                        empty++

                    } else {

                        if (empty > 0) {

                            append(empty)

                            empty = 0
                        }

                        append(piece)
                    }
                }

                if (empty > 0) {
                    append(empty)
                }

                if (row < 7) {
                    append("/")
                }
            }
        }
    }
}
