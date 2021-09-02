package com.keylesspalace.tusky.components.timeline.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.map
import androidx.room.withTransaction
import com.google.gson.Gson
import com.keylesspalace.tusky.appstore.BookmarkEvent
import com.keylesspalace.tusky.appstore.EventHub
import com.keylesspalace.tusky.appstore.FavoriteEvent
import com.keylesspalace.tusky.appstore.PinEvent
import com.keylesspalace.tusky.appstore.ReblogEvent
import com.keylesspalace.tusky.components.timeline.Placeholder
import com.keylesspalace.tusky.components.timeline.toEntity
import com.keylesspalace.tusky.components.timeline.toStatus
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.db.AppDatabase
import com.keylesspalace.tusky.entity.Poll
import com.keylesspalace.tusky.network.FilterModel
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.network.TimelineCases
import com.keylesspalace.tusky.util.Either
import com.keylesspalace.tusky.util.dec
import com.keylesspalace.tusky.util.inc
import com.keylesspalace.tusky.util.toViewData
import com.keylesspalace.tusky.viewdata.StatusViewData
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import javax.inject.Inject

class CachedTimelineViewModel @Inject constructor(
    timelineCases: TimelineCases,
    private val api: MastodonApi,
    eventHub: EventHub,
    private val accountManager: AccountManager,
    sharedPreferences: SharedPreferences,
    filterModel: FilterModel,
    private val db: AppDatabase,
    private val gson: Gson
) : TimelineViewModel(timelineCases, api, eventHub, accountManager, sharedPreferences, filterModel) {

    @ExperimentalPagingApi
    override val statuses = Pager(
        config = PagingConfig(pageSize = LOAD_AT_ONCE),
        remoteMediator = CachedTimelineRemoteMediator(accountManager.activeAccount!!.id, api, db, gson),
        pagingSourceFactory = { db.timelineDao().getStatusesForAccount(accountManager.activeAccount!!.id) }
    ).flow
        .map {
            it.map { item ->
                when (val status = item.toStatus(gson)) {
                    is Either.Right -> status.value.toViewData(
                        alwaysShowSensitiveMedia,
                        alwaysOpenSpoilers
                    )
                    is Either.Left -> StatusViewData.Placeholder(status.value.id, false)
                }
            }
        }

    override fun updatePoll(newPoll: Poll, status: StatusViewData.Concrete) {
        // handled by CacheUpdater
    }

    override fun changeExpanded(expanded: Boolean, status: StatusViewData.Concrete) {
        viewModelScope.launch {
            db.timelineDao().setExpanded(accountManager.activeAccount!!.id, status.id, expanded)
        }
    }

    override fun changeContentHidden(isShowing: Boolean, status: StatusViewData.Concrete) {
        viewModelScope.launch {
            db.timelineDao().setContentHidden(accountManager.activeAccount!!.id, status.id, isShowing)
        }
    }

    override fun changeContentCollapsed(isCollapsed: Boolean, status: StatusViewData.Concrete) {
        viewModelScope.launch {
            db.timelineDao().setContentCollapsed(accountManager.activeAccount!!.id, status.id, isCollapsed)
        }
    }

    override fun removeAllByAccountId(accountId: String) {
    }

    override fun removeAllByInstance(instance: String) {
    }

    override fun loadMore(placeholderId: String) {
        viewModelScope.launch {
            val response = api.homeTimeline(maxId = placeholderId.inc(), limit = 20).await()

            val statuses = response.body()!!

            val timelineDao = db.timelineDao()

            val accountId = accountManager.activeAccount!!.id

            db.withTransaction {

                timelineDao.delete(accountId, placeholderId)

                val overlappedStatuses = if (statuses.isNotEmpty()) {
                    timelineDao.deleteRange(accountId, statuses.last().id, statuses.first().id)
                } else {
                    0
                }

                for (status in statuses) {
                    timelineDao.insertInTransaction(
                        status.toEntity(accountId, gson),
                        status.account.toEntity(accountId, gson),
                        status.reblog?.account?.toEntity(accountId, gson)
                    )
                }

                if (overlappedStatuses == 0) {
                    /*val linkHeader = statusResponse.headers()["Link"]
                    val links = HttpHeaderLink.parse(linkHeader)
                    val nextId = HttpHeaderLink.findByRelationType(links, "next")?.uri?.getQueryParameter("max_id")

                    val topId = state.firstItemOrNull()?.status?.serverId

                    Log.d("TimelineMediator", " topId: $topId")
                    Log.d("TimelineMediator", "nextId: $nextId")*/

                    timelineDao.insertStatus(
                        Placeholder(statuses.last().id.dec()).toEntity(accountId)
                    )
                }
            }
        }
    }

    override fun handleReblogEvent(reblogEvent: ReblogEvent) {
    }

    override fun handleFavEvent(favEvent: FavoriteEvent) {
    }

    override fun handleBookmarkEvent(bookmarkEvent: BookmarkEvent) {
    }

    override fun handlePinEvent(pinEvent: PinEvent) {
    }
}
