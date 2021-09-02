package com.keylesspalace.tusky.components.timeline.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.keylesspalace.tusky.appstore.BookmarkEvent
import com.keylesspalace.tusky.appstore.EventHub
import com.keylesspalace.tusky.appstore.FavoriteEvent
import com.keylesspalace.tusky.appstore.PinEvent
import com.keylesspalace.tusky.appstore.ReblogEvent
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.entity.Poll
import com.keylesspalace.tusky.entity.Status
import com.keylesspalace.tusky.network.FilterModel
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.network.TimelineCases
import com.keylesspalace.tusky.util.LinkHelper
import com.keylesspalace.tusky.util.inc
import com.keylesspalace.tusky.util.toViewData
import com.keylesspalace.tusky.viewdata.StatusViewData
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import retrofit2.Response
import javax.inject.Inject

class NetworkTimelineViewModel @Inject constructor(
    timelineCases: TimelineCases,
    private val api: MastodonApi,
    eventHub: EventHub,
    accountManager: AccountManager,
    sharedPreferences: SharedPreferences,
    filterModel: FilterModel
) : TimelineViewModel(timelineCases, api, eventHub, accountManager, sharedPreferences, filterModel) {

    var currentSource: NetworkTimelinePagingSource? = null

    val statusData: MutableList<StatusViewData> = mutableListOf()

    var nextKey: String? = null

    @ExperimentalPagingApi
    override val statuses = Pager(
        config = PagingConfig(pageSize = LOAD_AT_ONCE),
        pagingSourceFactory = {
            NetworkTimelinePagingSource(
                viewModel = this
            ).also { source ->
                currentSource = source
            }
        },
        remoteMediator = NetworkTimelineRemoteMediator(this)
    ).flow
        .cachedIn(viewModelScope)

    override fun updatePoll(newPoll: Poll, status: StatusViewData.Concrete,) {
        status.copy(
            status = status.status.copy(poll = newPoll)
        ).update()
    }

    override fun changeExpanded(expanded: Boolean, status: StatusViewData.Concrete) {
        status.copy(
            isExpanded = expanded
        ).update()
    }

    override fun changeContentHidden(isShowing: Boolean, status: StatusViewData.Concrete) {
        status.copy(
            isShowingContent = isShowing
        ).update()
    }

    override fun changeContentCollapsed(isCollapsed: Boolean, status: StatusViewData.Concrete) {
        status.copy(
            isCollapsed = isCollapsed
        ).update()
    }

    override fun removeAllByAccountId(accountId: String) {
        statusData.removeAll { vd ->
            val status = vd.asStatusOrNull()?.status ?: return@removeAll false
            status.account.id == accountId || status.actionableStatus.account.id == accountId
        }
        currentSource?.invalidate()
    }

    override fun removeAllByInstance(instance: String) {
        statusData.removeAll { vd ->
            val status = vd.asStatusOrNull()?.status ?: return@removeAll false
            LinkHelper.getDomain(status.account.url) == instance
        }
    }

    override fun loadMore(placeholderId: String) {
        viewModelScope.launch {
            val response = fetchStatusesForKind(
                fromId = placeholderId.inc(),
                uptoId = null,
                limit = 20
            )

            val statuses = response.body()!!.map { status ->
                status.toViewData(false, false) // todo
            }

            val index = statusData.indexOfFirst { it is StatusViewData.Placeholder && it.id == placeholderId }
            statusData.removeAt(index)
            statusData.addAll(index, statuses)

            currentSource?.invalidate()
        }
    }

    override fun handleReblogEvent(reblogEvent: ReblogEvent) {
        updateStatusById(reblogEvent.statusId) {
            it.copy(status = it.status.copy(reblogged = reblogEvent.reblog))
        }
    }

    override fun handleFavEvent(favEvent: FavoriteEvent) {
        updateActionableStatusById(favEvent.statusId) {
            it.copy(favourited = favEvent.favourite)
        }
    }

    override fun handleBookmarkEvent(bookmarkEvent: BookmarkEvent) {
        updateActionableStatusById(bookmarkEvent.statusId) {
            it.copy(bookmarked = bookmarkEvent.bookmark)
        }
    }

    override fun handlePinEvent(pinEvent: PinEvent) {
        updateActionableStatusById(pinEvent.statusId) {
            it.copy(pinned = pinEvent.pinned)
        }
    }

    suspend fun fetchStatusesForKind(
        fromId: String?,
        uptoId: String?,
        limit: Int
    ): Response<List<Status>> {
        return when (kind) {
            Kind.HOME -> api.homeTimeline(fromId, uptoId, limit)
            Kind.PUBLIC_FEDERATED -> api.publicTimeline(null, fromId, uptoId, limit)
            Kind.PUBLIC_LOCAL -> api.publicTimeline(true, fromId, uptoId, limit)
            Kind.TAG -> {
                val firstHashtag = tags[0]
                val additionalHashtags = tags.subList(1, tags.size)
                api.hashtagTimeline(firstHashtag, additionalHashtags, null, fromId, uptoId, limit)
            }
            Kind.USER -> api.accountStatuses(
                id!!,
                fromId,
                uptoId,
                limit,
                excludeReplies = true,
                onlyMedia = null,
                pinned = null
            )
            Kind.USER_PINNED -> api.accountStatuses(
                id!!,
                fromId,
                uptoId,
                limit,
                excludeReplies = null,
                onlyMedia = null,
                pinned = true
            )
            Kind.USER_WITH_REPLIES -> api.accountStatuses(
                id!!,
                fromId,
                uptoId,
                limit,
                excludeReplies = null,
                onlyMedia = null,
                pinned = null
            )
            Kind.FAVOURITES -> api.favourites(fromId, uptoId, limit)
            Kind.BOOKMARKS -> api.bookmarks(fromId, uptoId, limit)
            Kind.LIST -> api.listTimeline(id!!, fromId, uptoId, limit)
        }.await()
    }

    private fun StatusViewData.Concrete.update() {
        val position = statusData.indexOfFirst { viewData -> viewData.asStatusOrNull()?.id == this.id }
        statusData[position] = this
        currentSource?.invalidate()
    }

    private inline fun updateStatusById(
        id: String,
        updater: (StatusViewData.Concrete) -> StatusViewData.Concrete
    ) {
        val pos = statusData.indexOfFirst { it.asStatusOrNull()?.id == id }
        if (pos == -1) return
        updateViewDataAt(pos, updater)
    }

    private inline fun updateActionableStatusById(
        id: String,
        updater: (Status) -> Status
    ) {
        val pos = statusData.indexOfFirst { it.asStatusOrNull()?.id == id }
        if (pos == -1) return
        updateViewDataAt(pos) { vd ->
            if (vd.status.reblog != null) {
                vd.copy(status = vd.status.copy(reblog = updater(vd.status.reblog)))
            } else {
                vd.copy(status = updater(vd.status))
            }
        }
    }

    private inline fun updateViewDataAt(
        position: Int,
        updater: (StatusViewData.Concrete) -> StatusViewData.Concrete
    ) {
        val status = statusData.getOrNull(position)?.asStatusOrNull() ?: return
        statusData[position] = updater(status)
        currentSource?.invalidate()
    }
}
