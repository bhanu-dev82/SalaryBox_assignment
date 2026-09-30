package com.bhanu.attendance.data.repository

import com.bhanu.attendance.data.local.dao.FaceTemplateDao
import com.bhanu.attendance.data.local.dao.StaffDao
import com.bhanu.attendance.data.mapper.toDomain
import com.bhanu.attendance.data.mapper.toEntity
import com.bhanu.attendance.domain.model.FaceTemplate
import com.bhanu.attendance.domain.outcome.runCatchingOutcome
import com.bhanu.attendance.domain.repository.FaceTemplateRepository
import com.bhanu.attendance.domain.time.TimeProvider
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaceTemplateRepositoryImpl @Inject constructor(
    private val faceTemplateDao: FaceTemplateDao,
    private val staffDao: StaffDao,
    private val timeProvider: TimeProvider,
) : FaceTemplateRepository {

    override suspend fun get(staffId: String): FaceTemplate? {
        val template = faceTemplateDao.get(staffId)?.toDomain() ?: return null
        if (template.descriptor.isEmpty()) {
            // An empty blob cannot be matched. Drop it so the caller treats the person as
            // not enrolled instead of crashing while building a descriptor.
            delete(staffId)
            return null
        }
        return template
    }

    /**
     * Stores the template and stamps the staff row.
     *
     * Both, in one call, so a staff member can never be left showing "enrolled" in the UI
     * while the template is missing, or holding a template the UI believes is absent.
     */
    override suspend fun upsert(template: FaceTemplate) {
        runCatchingOutcome {
            faceTemplateDao.upsert(template.toEntity())
            staffDao.setFaceEnrolledAt(template.staffId, timeProvider.now())
        }
    }

    override suspend fun delete(staffId: String) {
        runCatchingOutcome {
            faceTemplateDao.delete(staffId)
            staffDao.setFaceEnrolledAt(staffId, null)
        }
    }

    override suspend fun countEnrolled(): Int = faceTemplateDao.countEnrolled()

    override fun observeEnrolled(staffId: String): Flow<Boolean> =
        faceTemplateDao.observeEnrolled(staffId)
}
