package com.pitchlab.domain.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 從已錄好的 MP4 裁出「出手點前後各 N 秒」的片段。
 *
 * 為什麼不重新編碼：
 * - 重新編碼在手機上要跑一整條 MediaCodec 管線，對 1080p 素材很吃 CPU，
 *   而且會二次損失畫質；
 * - 只需要改變容器層的取樣範圍，用 MediaExtractor + MediaMuxer **直接複製編碼幀** 即可，
 *   一片 6 秒的片段通常 100–300 ms 完成。
 *
 * 代價：切割點只能落在「同步幀（I 幀）」上，所以實際起點會比要求時間稍早
 * （最多一個 GOP，通常 0.5–2 秒）。對投球回放完全可接受，且避免了前段解碼破圖。
 */
@Singleton
class PitchClipExtractor @Inject constructor() {

    companion object {
        private const val TAG = "PitchClipExtractor"
        private const val MAX_SEEK_BACK_US = 3_000_000L
    }

    /**
     * @param input 原始錄影檔
     * @param output 輸出的片段檔（會被覆寫）
     * @param centerMs 出手時間（相對錄影開始，毫秒）
     * @param beforeMs 出手前保留長度
     * @param afterMs 出手後保留長度
     * @return 成功且檔案非空時 true
     */
    @WorkerThread
    fun extract(
        input: File,
        output: File,
        centerMs: Long,
        beforeMs: Long = 2000,
        afterMs: Long = 2000
    ): Boolean {
        if (!input.exists() || input.length() == 0L) {
            Log.w(TAG, "來源影片不存在或為空：${input.absolutePath}")
            return false
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(input.absolutePath)

            val requestedStartUs = (centerMs - beforeMs) * 1000L
            val requestedEndUs = (centerMs + afterMs) * 1000L

            // 1) 建軌道表
            val trackFormats = mutableListOf<Pair<Int, MediaFormat>>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackFormats.add(i to format)
                }
            }
            if (trackFormats.isEmpty()) {
                Log.w(TAG, "找不到可複製的軌道")
                return false
            }

            val videoTrackIndex = trackFormats.indexOfFirst { it.second.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }

            // 2) 影片軌對齊同步幀；音軌從 0 開始 seek 以免前面沒聲音
            var startUs = requestedStartUs.coerceAtLeast(0L)
            if (videoTrackIndex >= 0) {
                extractor.selectTrack(trackFormats[videoTrackIndex].first)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val actual = extractor.sampleTime
                if (actual >= 0) {
                    startUs = actual
                } else {
                    // 同步幀可能在更前面，退一步找
                    val fallback = (startUs - MAX_SEEK_BACK_US).coerceAtLeast(0L)
                    extractor.seekTo(fallback, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    startUs = extractor.sampleTime.coerceAtLeast(0L)
                }
            }

            val endUs = requestedEndUs.coerceAtLeast(startUs + 100_000L)

            // 3) 建立 muxer 與軌道映射
            output.parentFile?.mkdirs()
            if (output.exists()) output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = HashMap<Int, Int>()
            for ((sourceIndex, format) in trackFormats) {
                val targetIndex = muxer.addTrack(format)
                trackMap[sourceIndex] = targetIndex
            }
            muxer.start()

            val buffer = java.nio.ByteBuffer.allocateDirect(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var wroteSamples = 0
            var trackCursor = 0

            // MediaExtractor 沒有 unselectAllTracks()，必須逐軌 unselectTrack(index)
            var currentlySelected = -1

            for (sourceIndex in trackMap.keys) {
                if (currentlySelected >= 0) {
                    extractor.unselectTrack(currentlySelected)
                }
                extractor.selectTrack(sourceIndex)
                currentlySelected = sourceIndex
                // 影片軌已經 seek 過，音軌要自己 seek 到 start（含前一個同步幀）
                if (sourceIndex != trackFormats.getOrNull(videoTrackIndex)?.first) {
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }

                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0 || sampleTime > endUs) break
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break

                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = (sampleTime - startUs).coerceAtLeast(0L)
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(trackMap.getValue(sourceIndex), buffer, info)
                    wroteSamples++
                    extractor.advance()
                }
                trackCursor++
            }

            muxer.stop()
            muxer.release()
            muxer = null

            val ok = output.exists() && output.length() > 0L && wroteSamples > 0
            Log.i(TAG, "剪輯完成 tracks=$trackCursor samples=$wroteSamples size=${output.length()} ok=$ok")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "剪輯失敗：${input.name}", t)
            runCatching { muxer?.release() }
            if (output.exists()) output.delete()
            false
        } finally {
            runCatching { extractor.release() }
        }
    }
}
