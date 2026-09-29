package com.yfuse.feature.person

import com.arkivanov.decompose.ComponentContext
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.Person
import com.yfuse.core.model.PersonProfile
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbPersonDetail
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Which person 演员页 is about, as the page that opened it knew them. Serializable because it rides
 * in the navigation stack, and complete enough to draw the header before anything has loaded.
 */
@Serializable
data class PersonPageRequest(
    /** The server whose library credits them; null reads the default server. */
    val serverId: String?,
    val personId: String,
    val name: String,
    val role: String? = null,
    val imageTag: String? = null,
) {
    companion object {
        fun of(
            serverId: String?,
            person: Person,
        ): PersonPageRequest =
            PersonPageRequest(
                serverId = serverId,
                personId = person.id,
                name = person.name,
                role = person.role?.takeIf(String::isNotBlank),
                imageTag = person.primaryImageTag,
            )
    }
}

data class PersonPageState(
    val name: String,
    val role: String? = null,
    val imageTag: String? = null,
    val server: SavedServer? = null,
    /** The saved server this page was opened for is gone; nothing can load. */
    val serverMissing: Boolean = false,
    val profile: PersonProfile? = null,
    val profileLoading: Boolean = true,
    val works: List<MediaItem> = emptyList(),
    val worksLoading: Boolean = true,
    val worksError: String? = null,
    /** The TMDB record, only once it is known to be this person — see [resolveTmdbPerson]. */
    val tmdb: TmdbPersonDetail? = null,
    val otherWorks: List<TmdbItem> = emptyList(),
    val otherWorksLoading: Boolean = false,
) {
    /** The server's spelling once it has answered; the cast row's until then. */
    val displayName: String
        get() = profile?.name?.takeIf(String::isNotBlank) ?: name

    /** The server's own biography; TMDB's only where the server has none. */
    val overview: String?
        get() = profile?.overview ?: tmdb?.biography

    val overviewFromTmdb: Boolean
        get() = profile?.overview == null && tmdb?.biography != null

    /** TMDB's dates are the day itself; a server's may be shifted by its time zone — see serverCalendarDay. */
    val birthDate: String?
        get() = tmdb?.birthday ?: profile?.birthDate

    val deathDate: String?
        get() = tmdb?.deathday ?: profile?.deathDate

    val birthPlace: String?
        get() = profile?.birthPlace ?: tmdb?.placeOfBirth

    /** The server's portrait first: it is the one the cast row showed. */
    val serverImageTag: String?
        get() = profile?.primaryImageTag ?: imageTag

    val tmdbProfilePath: String?
        get() = tmdb?.person?.profilePath
}

/**
 * 演员页: who a person is, what of theirs this library holds — each of which plays — and, when TMDB
 * can be sure it is the same person, what else they have made.
 *
 * The server half and the TMDB half load separately and neither waits on the other's failure: a
 * server without person records still lists the titles, and TMDB being unreachable only leaves
 * its section out.
 */
class PersonComponent(
    componentContext: ComponentContext,
    private val repo: EmbyRepository,
    private val tmdb: TmdbRepository,
    private val registry: ServerRegistry,
    val request: PersonPageRequest,
    val onBack: () -> Unit,
    /** A title in the library: its detail page, the ordinary way. */
    val onOpenItem: (serverId: String, itemId: String) -> Unit,
    /** A title only TMDB knows: its TMDB page. */
    val onOpenTmdbItem: (TmdbItem) -> Unit,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle)
    private val _state =
        MutableStateFlow(PersonPageState(name = request.name, role = request.role, imageTag = request.imageTag))
    val state: StateFlow<PersonPageState> = _state.asStateFlow()
    private var loading: Job? = null

    init {
        load()
    }

    /** 重试 after the library's list failed; the rest reloads with it. */
    fun retry() {
        if (loading?.isActive == true) return
        load()
    }

    private fun load() {
        val server =
            request.serverId?.let(registry::serverById) ?: registry.defaultServer.takeIf { request.serverId == null }
        if (server == null) {
            _state.update {
                it.copy(serverMissing = true, profileLoading = false, worksLoading = false, otherWorksLoading = false)
            }
            return
        }
        _state.update {
            it.copy(
                server = server,
                serverMissing = false,
                profileLoading = true,
                worksLoading = true,
                worksError = null,
            )
        }
        loading =
            scope.launch {
                coroutineScope {
                    val profile = async { repo.person(server, request.personId) }
                    val works = async { repo.itemsByPerson(server, request.personId) }
                    val person = profile.await()
                    _state.update { it.copy(profile = person, profileLoading = false) }
                    val titles = works.await()
                    titles
                        .onSuccess { list -> _state.update { it.copy(works = list, worksLoading = false) } }
                        .onFailure { error ->
                            _state.update {
                                it.copy(worksLoading = false, worksError = error.toUserMessage("库内作品没有加载出来"))
                            }
                        }
                    loadOtherWorks(person, titles.getOrDefault(emptyList()))
                }
            }
    }

    private suspend fun loadOtherWorks(
        person: PersonProfile?,
        works: List<MediaItem>,
    ) {
        _state.update { it.copy(otherWorksLoading = true) }
        val keys = libraryTmdbKeys(works)
        val match =
            resolveTmdbPerson(
                name = person?.name?.takeIf(String::isNotBlank) ?: request.name,
                tmdbId = person?.tmdbId,
                libraryKeys = keys,
                search = { tmdb.searchPeople(it).getOrDefault(emptyList()) },
                details = { tmdb.person(it).getOrNull() },
            )
        AppLog.info(
            category = "feature.person",
            event = "tmdb_person_match",
            message = "演员页 TMDB match resolved",
            attributes =
                mapOf(
                    "outcome" to
                        when (match) {
                            is TmdbPersonMatch.ById -> "id"
                            is TmdbPersonMatch.ByName -> "name"
                            is TmdbPersonMatch.None -> match.reason
                        },
                    "libraryTitles" to keys.size.toString(),
                ),
        )
        val detail = match.detail
        _state.update {
            it.copy(
                tmdb = detail,
                otherWorks = detail?.let { record -> otherWorks(record, keys) }.orEmpty(),
                otherWorksLoading = false,
            )
        }
    }
}
