package com.lifeos.app.data.repository

import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.data.toDto
import com.lifeos.app.domain.model.GithubActivity
import com.lifeos.app.domain.repository.GithubRepository
import javax.inject.Inject

class GithubRepositoryImpl @Inject constructor(
    private val api: LifeOsApi,
) : GithubRepository {

    override suspend fun submit(activity: GithubActivity) {
        api.submitGithub(activity.toDto())
    }

    override suspend fun refresh(days: Int) {
        // GitHub rollups are derived server-side; nothing to pull for Phase 1.
    }
}
