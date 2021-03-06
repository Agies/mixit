package mixit.web.handler

import mixit.MixitProperties
import mixit.model.*
import mixit.repository.EventRepository
import mixit.repository.FavoriteRepository
import mixit.repository.TalkRepository
import mixit.repository.UserRepository
import mixit.util.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.ServerResponse.ok
import org.springframework.web.reactive.function.server.body
import org.springframework.web.util.UriUtils
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime


@Component
class TalkHandler(private val repository: TalkRepository,
                  private val userRepository: UserRepository,
                  private val eventRepository: EventRepository,
                  private val properties: MixitProperties,
                  private val markdownConverter: MarkdownConverter,
                  private val favoriteRepository: FavoriteRepository) {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun findByEventView(year: Int, req: ServerRequest, filterOnFavorite: Boolean, topic: String? = null): Mono<ServerResponse> =
            req.session().flatMap {
                val currentUserEmail = it.getAttribute<String>("email")
                val talks = loadTalkAndFavorites(year, req.language(), filterOnFavorite, currentUserEmail, topic).map { it.groupBy { if (it.date == null) "" else it.date } }
                val sponsors = loadSponsors(year, req)

                ok().render("talks", mapOf(
                        Pair("talks", talks),
                        Pair("year", year),
                        Pair("current", year == 2018),
                        Pair("title", when (topic) { null -> "talks.title.html|$year"
                            else -> "talks.title.html.$topic|$year"
                        }),
                        Pair("filtered", filterOnFavorite),
                        Pair("baseUri", UriUtils.encode(properties.baseUri!!, StandardCharsets.UTF_8)),
                        Pair("topic", topic),
                        Pair("has2Columns", talks.map { it.size == 2 }),
                        Pair("sponsors", sponsors)
                ))
            }

    fun findMediaByEventView(year: Int, req: ServerRequest, filterOnFavorite: Boolean, topic: String? = null): Mono<ServerResponse> =
            req.session().flatMap {
                val currentUserEmail = it.getAttribute<String>("email")
                val talks = loadTalkAndFavorites(year, req.language(), filterOnFavorite, currentUserEmail, topic).map { it.sortedBy { it.title } }
                val sponsors = loadSponsors(year, req)

                eventRepository
                        .findByYear(year)
                        .flatMap { event ->

                            ok().render("medias", mapOf(
                                    Pair("talks", talks),
                                    Pair("topic", topic),
                                    Pair("year", year),
                                    Pair("title", "medias.title.html|$year"),
                                    Pair("baseUri", UriUtils.encode(properties.baseUri!!, StandardCharsets.UTF_8)),
                                    Pair("sponsors", sponsors),
                                    Pair("filtered", filterOnFavorite),
                                    Pair("event", event),
                                    Pair("videoUrl", if (event.videoUrl?.url?.startsWith("https://vimeo.com/") == true) event.videoUrl.url.replace("https://vimeo.com/", "https://player.vimeo.com/video/") else null),
                                    Pair("hasPhotosOrVideo", event.videoUrl != null || event.photoUrls.isNotEmpty())))
                        }

            }

    private fun loadTalkAndFavorites(year: Int, language: Language, filterOnFavorite: Boolean, currentUserEmail: String? = null, topic: String? = null): Mono<List<TalkDto>> =
            if (currentUserEmail != null) {
                favoriteRepository
                        .findByEmail(currentUserEmail)
                        .collectList()
                        .flatMap { favorites ->
                            if (filterOnFavorite) {
                                repository.findByEventAndTalkIds(year.toString(), favorites.map { it.talkId }, topic)
                                        .collectList().flatMap { addUserToTalks(it, favorites, language) }
                            } else {
                                repository.findByEvent(year.toString(), topic).collectList().flatMap { addUserToTalks(it, favorites, language) }
                            }
                        }
            } else {
                repository.findByEvent(year.toString(), topic).collectList().flatMap { addUserToTalks(it, emptyList(), language) }
            }

    private fun addUserToTalks(talks: List<Talk>,
                               favorites: List<Favorite>,
                               language: Language): Mono<List<TalkDto>> =
            userRepository
                    .findMany(talks.flatMap(Talk::speakerIds))
                    .collectMap(User::login)
                    .map { speakers ->
                        talks
                                .map { talk ->
                                    talk.toDto(language,
                                            talk.speakerIds.mapNotNull { speakers[it] },
                                            favorites.filter { talk.id!!.equals(it.talkId) }.any())
                                }
                    }

    fun findOneView(year: Int, req: ServerRequest): Mono<ServerResponse> = repository.findByEventAndSlug(year.toString(), req.pathVariable("slug")).flatMap { talk ->
        val sponsors = loadSponsors(year, req)

        req.session().flatMap {
            val currentUserEmail = it.getAttribute<String>("email")

            userRepository.findMany(talk.speakerIds).collectList().flatMap { speakers ->

                val otherTalks = repository
                        .findBySpeakerId(talk.speakerIds, talk.id)
                        .collectList()
                        .flatMap { talks ->
                            talks.map { talk -> talk.toDto(req.language(), speakers.filter { talk.speakerIds.contains(it.login) }.toList()) }.toMono()
                        }

                ok().render("talk", mapOf(
                        Pair("talk", talk.toDto(req.language(), speakers!!)),
                        Pair("speakers", speakers.map { speaker -> speaker.toDto(req.language(), markdownConverter) }.sortedBy { talk.speakerIds.indexOf(it.login) }),
                        Pair("othertalks", otherTalks),
                        Pair("favorites", if (currentUserEmail == null) null else favoriteRepository.findByEmailAndTalk(currentUserEmail, talk.id!!)),
                        Pair("year", year),
                        Pair("hasOthertalks", otherTalks.map { it.size > 0 }),
                        Pair("title", "talk.html.title|${talk.title}"),
                        Pair("baseUri", UriUtils.encode(properties.baseUri!!, StandardCharsets.UTF_8)),
                        Pair("vimeoPlayer", if (talk.video?.startsWith("https://vimeo.com/") == true) talk.video.replace("https://vimeo.com/", "https://player.vimeo.com/video/") else null),
                        Pair("sponsors", sponsors)
                ))
            }
        }
    }

    fun loadSponsors(year: Int, req: ServerRequest) = eventRepository
            .findByYear(year)
            .flatMap { event ->
                userRepository
                        .findMany(event.sponsors.map { it.sponsorId })
                        .collectMap(User::login)
                        .map { sponsorsByLogin ->
                            val sponsorsByEvent = event.sponsors.groupBy { it.level }
                            mapOf(
                                    Pair("sponsors-gold", sponsorsByEvent[SponsorshipLevel.GOLD]?.map { it.toSponsorDto(sponsorsByLogin[it.sponsorId]!!) }),
                                    Pair("sponsors-others", event.sponsors
                                            .filter { it.level != SponsorshipLevel.GOLD }
                                            .map { it.toSponsorDto(sponsorsByLogin[it.sponsorId]!!) }
                                            .distinctBy { it.login })
                            )
                        }
            }

    fun findOne(req: ServerRequest) = ok().json().body(repository.findOne(req.pathVariable("login")))

    fun findByEventId(req: ServerRequest) = ok().json().body(repository.findByEvent(req.pathVariable("year")))

    fun redirectFromId(req: ServerRequest) = repository.findOne(req.pathVariable("id")).flatMap {
        permanentRedirect("${properties.baseUri}/${it.event}/${it.slug}")
    }

    fun redirectFromSlug(req: ServerRequest) = repository.findBySlug(req.pathVariable("slug")).flatMap {
        permanentRedirect("${properties.baseUri}/${it.event}/${it.slug}")
    }
}

class TalkDto(
        val id: String?,
        val slug: String,
        val format: TalkFormat,
        val event: String,
        val title: String,
        val summary: String,
        val speakers: List<User>,
        val language: String,
        val addedAt: LocalDateTime,
        val description: String?,
        val topic: String?,
        val video: String?,
        val vimeoPlayer: String?,
        val room: String?,
        val start: String?,
        val end: String?,
        val date: String?,
        val favorite: Boolean = false,
        val photoUrls: List<Link> = emptyList(),
        val isEn: Boolean = (language == "english"),
        val isTalk: Boolean = (format == TalkFormat.TALK),
        val multiSpeaker: Boolean = (speakers.size > 1),
        val speakersFirstNames: String = (speakers.joinToString { it.firstname })
)

fun Talk.toDto(lang: Language, speakers: List<User>, favorite: Boolean = false) = TalkDto(
        id, slug, format, event, title,
        summary(),
        speakers, language.name.toLowerCase(), addedAt,
        description(),
        topic,
        video,
        if (video?.startsWith("https://vimeo.com/") == true) video.replace("https://vimeo.com/", "https://player.vimeo.com/video/") else null,
        "rooms.${room?.name?.toLowerCase()}",
        start?.formatTalkTime(lang),
        end?.formatTalkTime(lang),
        start?.formatTalkDate(lang),
        favorite,
        photoUrls
)

fun Talk.summary() = if (format == TalkFormat.RANDOM && language == Language.ENGLISH && event == "2018")
    "This is a \"Random\" talk. For this track we choose the programm for you. You are in a room, and a speaker come to speak about a subject for which you ignore the content. Don't be afraid it's only for 25 minutes. As it's a surprise we don't display the session summary before...   "
else if (format == TalkFormat.RANDOM && language == Language.FRENCH && event == "2018")
    "Ce talk est de type \"random\". Pour cette track, nous choisissons le programme pour vous. Vous êtes dans une pièce et un speaker vient parler d'un sujet dont vous ignorez le contenu. N'ayez pas peur, c'est seulement pour 25 minutes. Comme c'est une surprise, nous n'affichons pas le résumé de la session avant ..."
else summary

fun Talk.description() = if (format == TalkFormat.RANDOM && event == "2018") "" else description
