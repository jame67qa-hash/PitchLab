package com.pitchlab.data.repository

import com.pitchlab.data.local.PitchSessionDao
import com.pitchlab.data.local.PitchSessionEntity
import com.pitchlab.domain.model.PitchSession
import com.pitchlab.domain.model.PitchType
import com.pitchlab.domain.repository.PitchSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPitchSessionRepository @Inject constructor(
    private val dao: PitchSessionDao
) : PitchSessionRepository {

    override fun observeSessions(): Flow<List<PitchSession>> =
        dao.observeRecent().map { entities -> entities.map { it.toDomain() } }

    override fun observeCount(): Flow<Int> = dao.observeCount()

    override suspend fun save(session: PitchSession): Long = dao.insert(session.toEntity())

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun clear() = dao.clearAll()

    override suspend fun updateClip(videoPath: String, clipPath: String) =
        dao.updateClipPath(videoPath, clipPath)

    private fun PitchSessionEntity.toDomain() = PitchSession(
        id = id,
        startedAtMillis = startedAtMillis,
        durationMillis = durationMillis,
        fastestMph = fastestMph,
        averageMph = averageMph,
        horizontalBreakInches = horizontalBreakInches,
        verticalBreakInches = verticalBreakInches,
        plateXInches = plateXInches,
        plateYInches = plateYInches,
        isStrike = isStrike,
        calibrated = calibrated,
        videoPath = videoPath,
        detectedFps = detectedFps,
        trackedFrames = trackedFrames,
        createdAtMillis = createdAtMillis,
        clipPath = clipPath,
        releaseOffsetMs = releaseOffsetMs,
        poseScore = poseScore,
        separationDeg = separationDeg,
        elbowDeg = elbowDeg,
        trunkTiltDeg = trunkTiltDeg,
        releaseHeightRatio = releaseHeightRatio,
        releaseHeightFt = releaseHeightFt,
        releaseDistanceFt = releaseDistanceFt,
        cameraHeightIn = cameraHeightIn,
        cameraPitchDeg = cameraPitchDeg,
        speedScale = speedScale,
        pitchType = PitchType.fromCode(pitchType),
        pitcherId = pitcherId
    )

    private fun PitchSession.toEntity() = PitchSessionEntity(
        id = id,
        startedAtMillis = startedAtMillis,
        durationMillis = durationMillis,
        fastestMph = fastestMph,
        averageMph = averageMph,
        horizontalBreakInches = horizontalBreakInches,
        verticalBreakInches = verticalBreakInches,
        plateXInches = plateXInches,
        plateYInches = plateYInches,
        isStrike = isStrike,
        calibrated = calibrated,
        videoPath = videoPath,
        detectedFps = detectedFps,
        trackedFrames = trackedFrames,
        createdAtMillis = createdAtMillis,
        clipPath = clipPath,
        releaseOffsetMs = releaseOffsetMs,
        poseScore = poseScore,
        separationDeg = separationDeg,
        elbowDeg = elbowDeg,
        trunkTiltDeg = trunkTiltDeg,
        releaseHeightRatio = releaseHeightRatio,
        releaseHeightFt = releaseHeightFt,
        releaseDistanceFt = releaseDistanceFt,
        cameraHeightIn = cameraHeightIn,
        cameraPitchDeg = cameraPitchDeg,
        speedScale = speedScale,
        pitchType = pitchType?.code,
        pitcherId = pitcherId
    )
}
