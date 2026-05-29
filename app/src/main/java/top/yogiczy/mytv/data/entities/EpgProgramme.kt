package top.yogiczy.mytv.data.entities

import kotlinx.serialization.Serializable

/**
 * 频道节目
 */
@Serializable
data class EpgProgramme(
    /**
     * 开始时间（时间戳）
     */
    val startAt: Long = 0,

    /**
     * 结束时间（时间戳）
     */
    val endAt: Long = 0,

    /**
     * 节目名称
     */
    val title: String = "",
) {
    companion object {
        /**
         * 是否正在直播
         */
        fun EpgProgramme.isLive(now: Long = System.currentTimeMillis()) = now in startAt..<endAt

        /**
         * 节目进度
         */
        fun EpgProgramme.progress(): Float {
            val duration = endAt - startAt
            if (duration <= 0) return 0f

            return ((System.currentTimeMillis() - startAt).toFloat() / duration).coerceIn(0f, 1f)
        }
    }
}
