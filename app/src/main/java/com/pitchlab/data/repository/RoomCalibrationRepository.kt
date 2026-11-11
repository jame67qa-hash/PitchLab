package com.pitchlab.data.repository

import com.pitchlab.data.local.CalibrationDao
import com.pitchlab.data.local.CalibrationEntity
import com.pitchlab.domain.model.CalibrationProfile
import com.pitchlab.domain.model.NormalizedPoint
import com.pitchlab.domain.repository.CalibrationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomCalibrationRepository @Inject constructor(
    private val dao: CalibrationDao
) : CalibrationRepository {

    override fun observeActive(): Flow<CalibrationProfile?> =
        dao.observeLatest().map { it?.toDomain() }

    override suspend fun loadActive(): CalibrationProfile? = dao.findLatest()?.toDomain()

    override suspend fun save(profile: CalibrationProfile): Long =
        dao.insert(profile.toEntity())

    override suspend fun clearAll() = dao.clearAll()

    private fun CalibrationEntity.toDomain() = CalibrationProfile(
        id = id,
        corners = listOf(
            NormalizedPoint(p0x.toFloat(), p0y.toFloat()),
            NormalizedPoint(p1x.toFloat(), p1y.toFloat()),
            NormalizedPoint(p2x.toFloat(), p2y.toFloat()),
            NormalizedPoint(p3x.toFloat(), p3y.toFloat())
        ),
        cameraHeightIn = cameraHeightIn,
        strikeZoneTopIn = strikeZoneTopIn,
        strikeZoneBottomIn = strikeZoneBottomIn,
        speedScale = speedScale,
        radarReferenceMph = radarReferenceMph,
        estimatedAtCalibrationMph = estimatedAtCalibrationMph,
        createdAtMillis = createdAtMillis
    )

    private fun CalibrationProfile.toEntity(): CalibrationEntity {
        val points = corners.take(4)
        require(points.size == 4) { "標定必須剛好四個角點" }
        return CalibrationEntity(
            id = id,
            p0x = points[0].x.toDouble(), p0y = points[0].y.toDouble(),
            p1x = points[1].x.toDouble(), p1y = points[1].y.toDouble(),
            p2x = points[2].x.toDouble(), p2y = points[2].y.toDouble(),
            p3x = points[3].x.toDouble(), p3y = points[3].y.toDouble(),
            cameraHeightIn = cameraHeightIn,
            strikeZoneTopIn = strikeZoneTopIn,
            strikeZoneBottomIn = strikeZoneBottomIn,
            speedScale = speedScale,
            radarReferenceMph = radarReferenceMph,
            estimatedAtCalibrationMph = estimatedAtCalibrationMph,
            createdAtMillis = createdAtMillis
        )
    }
}
