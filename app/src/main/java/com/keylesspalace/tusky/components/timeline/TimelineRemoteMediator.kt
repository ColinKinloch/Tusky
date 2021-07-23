package com.keylesspalace.tusky.components.timeline

import android.util.Log
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.google.gson.Gson
import com.keylesspalace.tusky.db.AppDatabase
import com.keylesspalace.tusky.db.TimelineStatusWithAccount
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.util.HttpHeaderLink
import com.keylesspalace.tusky.util.dec
import com.keylesspalace.tusky.util.isLessThan
import kotlinx.coroutines.rx3.await

@ExperimentalPagingApi
class TimelineRemoteMediator(
    private val accountId: Long,
    private val api: MastodonApi,
    private val db: AppDatabase,
    private val gson: Gson
) : RemoteMediator<Int, TimelineStatusWithAccount>() {

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, TimelineStatusWithAccount>
    ): MediatorResult {

        try {
            val statusResponse = when (loadType) {
                LoadType.REFRESH -> {
                    api.homeTimeline(limit = state.config.pageSize).await()
                }
                LoadType.PREPEND -> {
                    return MediatorResult.Success(endOfPaginationReached = true)
                }
                LoadType.APPEND -> {
                    val maxId = state.pages.findLast { it.data.isNotEmpty() }?.data?.lastOrNull()?.status?.serverId
                    api.homeTimeline(maxId = maxId, limit = state.config.pageSize).await()
                }
            }

            val statuses = statusResponse.body()!!

            val timelineDao = db.timelineDao()

            if (loadType == LoadType.REFRESH) {
                db.conversationDao().deleteForAccount(accountId)
            }
            db.withTransaction {
                if (statuses.isNotEmpty()) {
                    timelineDao.deleteRange(accountId, statuses.last().id, statuses.first().id)
                }

                for (status in statuses) {
                    timelineDao.insertInTransaction(
                        status.toEntity(accountId, gson),
                        status.account.toEntity(accountId, gson),
                        status.reblog?.account?.toEntity(accountId, gson)
                    )
                }

                if (loadType == LoadType.REFRESH ) {
                    val linkHeader = statusResponse.headers()["Link"]
                    val links = HttpHeaderLink.parse(linkHeader)
                    val nextId = HttpHeaderLink.findByRelationType(links, "next")?.uri?.getQueryParameter("max_id")

                    val topId = state.firstItemOrNull()?.status?.serverId

                    if (topId?.isLessThan(nextId!!) == true) {
                        timelineDao.insertStatusIfNotThere(
                            Placeholder(nextId!!).toEntity(accountId)
                        )
                    }

                }

            }
            return MediatorResult.Success(endOfPaginationReached = statuses.isEmpty())
        } catch (e: Exception) {
            return MediatorResult.Error(e)
        }
    }

    override suspend fun initialize() = InitializeAction.LAUNCH_INITIAL_REFRESH
}
