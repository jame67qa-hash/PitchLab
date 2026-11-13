package com.pitchlab.data.repository

import android.content.Context
import com.pitchlab.data.local.PitchSessionDao
import com.pitchlab.data.local.PitcherDao
import com.pitchlab.data.local.PitcherEntity
import com.pitchlab.domain.model.Pitcher
import com.pitchlab.domain.repository.PitcherRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 投手檔案 + 「目前選取誰」。
 *
 * 選取狀態用 SharedPreferences 而不是資料表：它只是一次性的 UI 狀態，
 * 不需要查詢或關聯，放進資料庫反而多一張表要遷移。
 * 用 -1 代表未指定，避免 0 被當成合法 id。
 */
@Singleton
class RoomPitcherRepository @Inject constructor(
    private val pitcherDao: PitcherDao,
    private val sessionDao: PitchSessionDao,
    @ApplicationContext context: Context
) : PitcherRepository {

    private companion object {
        const val PREFS = "pitchlab_pitchers"
        const val KEY_ACTIVE = "active_pitcher_id"
        const val NONE = -1L
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _activePitcherId = MutableStateFlow(
        prefs.getLong(KEY_ACTIVE, NONE).takeIf { it > 0L }
    )

    override val activePitcherId: StateFlow<Long?> = _activePitcherId.asStateFlow()

    override fun observePitchers(): Flow<List<Pitcher>> =
        pitcherDao.observeAll().map { entities ->
            entities.map { Pitcher(id = it.id, name = it.name, createdAtMillis = it.createdAtMillis) }
        }

    override suspend fun add(name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "投手名稱不能空白" }
        val id = pitcherDao.insert(
            PitcherEntity(name = trimmed, createdAtMillis = System.currentTimeMillis())
        )
        // 第一個建立的投手直接變成現役，少一步「還要再點一次」
        if (_activePitcherId.value == null) setActive(id)
        return id
    }

    override fun setActive(id: Long?) {
        prefs.edit().putLong(KEY_ACTIVE, id ?: NONE).apply()
        _activePitcherId.value = id
    }

    override suspend fun rename(id: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "投手名稱不能空白" }
        pitcherDao.rename(id, trimmed)
    }

    override suspend fun delete(id: Long) {
        // 先解除關聯再刪，紀錄不會變成找不到投手的孤兒
        sessionDao.detachPitcher(id)
        pitcherDao.deleteById(id)
        if (_activePitcherId.value == id) setActive(null)
    }
}
