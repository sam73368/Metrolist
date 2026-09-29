/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.R
import com.metrolist.music.constants.AndroidAutoSearchLocalLimitKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.isInternetConnected
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.get
import com.metrolist.music.utils.getArtistSeparator
import com.metrolist.music.utils.joinToArtistString
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject
import com.metrolist.music.constants.AndroidAutoSectionsOrderKey
import com.metrolist.music.constants.AndroidAutoYouTubePlaylistsKey
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import com.metrolist.music.ui.screens.settings.deserializeSections
import com.metrolist.music.ui.screens.settings.serializeSections
import kotlinx.coroutines.withContext
import timber.log.Timber

class MediaLibrarySessionCallback
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(Dispatchers.Main) + Job()
    lateinit var service: MusicService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}
    var addToTargetPlaylist: () -> Unit = {}

    fun release() {
        scope.cancel()
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleLibrary)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .add(MediaSessionConstants.CommandAddToTargetPlaylist)
                .build(),
            connectionResult.availablePlayerCommands,
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> toggleLibrary()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> session.player.shuffleModeEnabled =
                !session.player.shuffleModeEnabled

            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> session.player.toggleRepeatMode()
            MediaSessionConstants.ACTION_ADD_TO_TARGET_PLAYLIST -> addToTargetPlaylist()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaItemsWithStartPosition> =
        Futures.immediateFuture(
            MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET),
        )

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(MusicService.ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                params,
            ),
        )

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        Futures.immediateFuture(
            if (isBrowsableMediaId(parentId)) {
                LibraryResult.ofVoid(params)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            },
        )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            try {
                loadLocalChildren(parentId, page, pageSize)?.let { children ->
                    return@future LibraryResult.ofItemList(children, params)
                }

                val children = when (parentId) {
                    MusicService.ROOT -> {
                        val sectionsRaw = context.dataStore.get(
                            AndroidAutoSectionsOrderKey,
                            serializeSections(AndroidAutoSection.values().map { it to true })
                        )
                        val sections = deserializeSections(sectionsRaw)
                        val showYoutubePlaylists = context.dataStore.get(AndroidAutoYouTubePlaylistsKey, false)
                        val rootItems = sections
                            .filter { (_, enabled) -> enabled }
                            .ifEmpty { listOf(AndroidAutoSection.LIKED to true) }
                            .map { (section, _) ->
                                when (section) {
                                    AndroidAutoSection.LIKED -> browsableMediaItem(
                                        "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                                        context.getString(R.string.liked_songs),
                                        null,
                                        drawableUri(R.drawable.favorite),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                   AndroidAutoSection.SONGS -> browsableMediaItem(
                                        MusicService.SONG,
                                        context.getString(R.string.songs),
                                        null,
                                        drawableUri(R.drawable.music_note),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                    AndroidAutoSection.ARTISTS -> browsableMediaItem(
                                        MusicService.ARTIST,
                                        context.getString(R.string.artists),
                                        null,
                                        drawableUri(R.drawable.artist),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                                    )
                                    AndroidAutoSection.ALBUMS -> browsableMediaItem(
                                        MusicService.ALBUM,
                                        context.getString(R.string.albums),
                                        null,
                                        drawableUri(R.drawable.album),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                                    )
                                    AndroidAutoSection.PLAYLISTS -> browsableMediaItem(
                                        MusicService.PLAYLIST,
                                        context.getString(R.string.playlists),
                                        null,
                                        drawableUri(R.drawable.queue_music),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                    )
                                }
                            }
                        if (showYoutubePlaylists) {
                            rootItems + browsableMediaItem(
                                MusicService.YOUTUBE_PLAYLIST,
                                context.getString(R.string.mixes),
                                null,
                                drawableUri(R.drawable.explore_outlined),
                                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                            )
                        } else {
                            rootItems
                        }
                    }
                    MusicService.YOUTUBE_PLAYLIST -> {
                        if (!context.dataStore.get(AndroidAutoYouTubePlaylistsKey, false)) {
                            emptyList()
                        } else {
                            try {
                                val allSections = mutableListOf<com.metrolist.innertube.pages.HomePage.Section>()
                                var continuation: String? = null
                                val maxPages = 4

                                for (page in 0 until maxPages) {
                                    val result = YouTube.home(continuation)
                                        .onFailure { reportException(it) }
                                        .getOrNull() ?: break
                                    allSections.addAll(result.sections)
                                    continuation = result.continuation
                                    if (continuation == null) break
                                }

                                // Drop playlists already saved to the local library,
                                // which are exposed under MusicService.PLAYLIST.
                                val savedBrowseIds = database.bookmarkedPlaylistBrowseIds().toSet()

                                val playlists = allSections
                                    .flatMap { it.items }
                                    .filterIsInstance<PlaylistItem>()
                                    .filterNot { it.id in savedBrowseIds }
                                    .distinctBy { it.id }

                                playlists.map { playlist ->
                                    browsableMediaItem(
                                        "${MusicService.YOUTUBE_PLAYLIST}/${playlist.id}",
                                        playlist.title,
                                        playlist.author?.name,
                                        playlist.thumbnail?.toUri(),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                }
                            } catch (e: Exception) {
                                reportException(e)
                                emptyList()
                            }
                        }
                    }

                    else ->
                        when {
                            parentId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/") -> {
                                val playlistId = parentId.removePrefix("${MusicService.YOUTUBE_PLAYLIST}/")
                                try {
                                    val songs = YouTube.playlist(playlistId).getOrNull()?.songs
                                        ?.take(100)
                                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                        ?: emptyList()

                                    // Add shuffle item at the top
                                    listOf(
                                        MediaItem.Builder()
                                            .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(context.getString(R.string.shuffle))
                                                    .setArtworkUri(drawableUri(R.drawable.shuffle))
                                                    .setIsPlayable(true)
                                                    .setIsBrowsable(false)
                                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                    .build()
                                            ).build()
                                    ) + songs.map { songItem ->
                                        MediaItem.Builder()
                                            .setMediaId("$parentId/${songItem.id}")
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(songItem.title)
                                                    .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                                        ArtistNameAliases.resolve(it.id, it.name)
                                                    })
                                                    .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                                        ArtistNameAliases.resolve(it.id, it.name)
                                                    })
                                                    .setArtworkUri(songItem.thumbnail.toUri())
                                                    .setIsPlayable(true)
                                                    .setIsBrowsable(false)
                                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                    .build()
                                            )
                                            .build()
                                    }
                                } catch (e: Exception) {
                                    reportException(e)
                                    emptyList()
                                }
                            }

                            else -> emptyList()
                        }
                }
                LibraryResult.ofItemList(children.paginate(page, pageSize), params)
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }

    private suspend fun loadLocalChildren(
        parentId: String,
        page: Int,
        pageSize: Int,
    ): List<MediaItem>? {
        val request = androidAutoPageRequest(page, pageSize)
        return when {
            parentId == MusicService.ARTIST ->
                database.artistsByCreateDateAsc(request.limit, request.offset).map { artist ->
                    browsableMediaItem(
                        "${MusicService.ARTIST}/${artist.id}",
                        ArtistNameAliases.resolve(artist.id, artist.artist.name),
                        context.resources.getQuantityString(
                            R.plurals.n_song,
                            artist.songCount,
                            artist.songCount,
                        ),
                        artist.artist.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ARTIST,
                    )
                }

            parentId == MusicService.ALBUM ->
                database.albumsByCreateDateAsc(request.limit, request.offset).map { album ->
                    browsableMediaItem(
                        "${MusicService.ALBUM}/${album.id}",
                        album.album.title,
                        album.artists.joinToString {
                            ArtistNameAliases.resolve(it.id, it.name)
                        },
                        album.album.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ALBUM,
                    )
                }

            parentId == MusicService.PLAYLIST ->
                loadPlaylistContainers(request)

            parentId == MusicService.SONG ->
                database.songsByCreateDateAsc(request.limit, request.offset)
                    .map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.ARTIST}/") ->
                database.artistSongsByCreateDateAsc(
                    parentId.removePrefix("${MusicService.ARTIST}/"),
                    request.limit,
                    request.offset,
                ).map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.ALBUM}/") ->
                database.albumSongs(
                    parentId.removePrefix("${MusicService.ALBUM}/"),
                    request.limit,
                    request.offset,
                ).map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.PLAYLIST}/") ->
                loadPlaylistChildren(parentId, request)

            else -> null
        }
    }

    private suspend fun loadPlaylistContainers(request: AndroidAutoPageRequest): List<MediaItem> {
        val builtInItems =
            if (request.offset < 2 && request.limit > 0) {
                val likedSongCount = database.likedSongsCount().first()
                val downloadedSongCount = downloadUtil.downloads.value.size
                listOf(
                    browsableMediaItem(
                        "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                        context.getString(R.string.liked_songs),
                        context.resources.getQuantityString(R.plurals.n_song, likedSongCount, likedSongCount),
                        drawableUri(R.drawable.favorite),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    ),
                    browsableMediaItem(
                        "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                        context.getString(R.string.downloaded_songs),
                        context.resources.getQuantityString(
                            R.plurals.n_song,
                            downloadedSongCount,
                            downloadedSongCount,
                        ),
                        drawableUri(R.drawable.download),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    ),
                ).drop(request.offset).take(request.limit)
            } else {
                emptyList()
            }
        val playlistRequest = request.afterLeadingItems(2)
        val playlists =
            if (playlistRequest.limit == 0) {
                emptyList()
            } else {
                database.playlistsByCreateDateAsc(playlistRequest.limit, playlistRequest.offset)
            }
        return builtInItems + playlists.map { playlist ->
            browsableMediaItem(
                "${MusicService.PLAYLIST}/${playlist.id}",
                playlist.playlist.name,
                context.resources.getQuantityString(R.plurals.n_song, playlist.songCount, playlist.songCount),
                playlist.thumbnails.firstOrNull()?.toUri(),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )
        }
    }

    private suspend fun loadPlaylistChildren(
        parentId: String,
        request: AndroidAutoPageRequest,
    ): List<MediaItem> {
        val playlistId = parentId.removePrefix("${MusicService.PLAYLIST}/")
        val includeShuffle = request.offset == 0 && request.limit > 0
        val songRequest = request.afterLeadingItems(1)
        val songs = when {
            songRequest.limit == 0 -> emptyList()
            playlistId == PlaylistEntity.LIKED_PLAYLIST_ID ->
                database.likedSongsByCreateDateDesc(songRequest.limit, songRequest.offset)

            playlistId == PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                val downloads = downloadUtil.downloads.value
                val completedSongIds = downloads.entries
                    .asSequence()
                    .filter { it.value.state == Download.STATE_COMPLETED }
                    .sortedBy { it.value.updateTimeMs }
                    .map { it.key }
                    .toList()
                val existingSongIds = completedSongIds
                    .chunked(MAX_ANDROID_AUTO_PAGE_SIZE)
                    .flatMapTo(mutableSetOf()) { database.existingSongIds(it) }
                val songIds = completedSongIds.asSequence()
                    .filter(existingSongIds::contains)
                    .drop(songRequest.offset)
                    .take(songRequest.limit)
                    .toList()
                val songsById = database.getSongsByIds(songIds).associateBy { it.id }
                songIds.mapNotNull(songsById::get)
            }

            else ->
                database.playlistSongs(playlistId, songRequest.limit, songRequest.offset)
                    .map { it.song }
        }

        return buildList {
            if (includeShuffle) {
                add(
                    MediaItem.Builder()
                        .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(context.getString(R.string.shuffle))
                                .setArtworkUri(drawableUri(R.drawable.shuffle))
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        ).build()
                )
            }
            addAll(songs.map { it.toMediaItem(parentId) })
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            try {
                database.song(mediaId).first()?.toMediaItem()?.let {
                    LibraryResult.ofItem(it, null)
                } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            }
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 1, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future(Dispatchers.IO) {
            if (query.isEmpty()) {
                return@future LibraryResult.ofItemList(emptyList(), params)
            }

            try {
                val searchResults = mutableListOf<MediaItem>()
                val limit = context.dataStore.get(AndroidAutoSearchLocalLimitKey, 75)

                val allLocalSongs = database.searchSongsExtended(query, limit).first()
                allLocalSongs.forEach { song ->
                    searchResults.add(song.toMediaItem(
                        path = "${MusicService.SEARCH}/$query",
                        isPlayable = true,
                        isBrowsable = false,
                    ))
                }

                try {
                    val onlineResults = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                        .getOrNull()
                        ?.items
                        ?.filterIsInstance<SongItem>()
                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                        ?.filter { onlineSong ->
                            !allLocalSongs.any { localSong ->
                                localSong.id == onlineSong.id ||
                                (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                 localSong.artists.any { artist ->
                                     onlineSong.artists.any {
                                         it.name.equals(artist.name, ignoreCase = true)
                                     }
                                 })
                            }
                        } ?: emptyList()

                    onlineResults.forEach { songItem ->
                        try {
                            database.query { insert(songItem.toMediaMetadata()) }
                        } catch (e: Exception) {
                        }
                        
                        searchResults.add(
                            MediaItem.Builder()
                                .setMediaId("${MusicService.SEARCH}/$query/${songItem.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(songItem.title)
                                        .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                            ArtistNameAliases.resolve(it.id, it.name)
                                        })
                                        .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                            ArtistNameAliases.resolve(it.id, it.name)
                                        })
                                        .setArtworkUri(songItem.thumbnail.toUri())
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                )
                                .build()
                        )
                    }
                } catch (e: Exception) {
                    reportException(e)
                }
                
                // Android Auto may ask for the results page by page.
                val pageRequest = androidAutoPageRequest(page, pageSize)
                LibraryResult.ofItemList(
                    searchResults.drop(pageRequest.offset).take(pageRequest.limit),
                    params,
                )

            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        scope.future(Dispatchers.IO) {
            val defaultResult = MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
            val firstItem = mediaItems.firstOrNull() ?: return@future defaultResult

            // Voice request ("Hey Google, play ... on Metrolist", Android Auto voice search):
            // it carries a search query and/or no media id.
            if (!firstItem.requestMetadata.searchQuery.isNullOrBlank() || firstItem.mediaId.isBlank()) {
                return@future handleVoiceRequest(mediaSession, firstItem.requestMetadata) ?: defaultResult
            }

            val path = parseMediaIdPath(firstItem.mediaId)

            when (path.firstOrNull()) {
                MusicService.SONG -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val allSongs = database.songsByCreateDateAsc().first()
                    MediaItemsWithStartPosition(
                        allSongs.map { it.toMediaItem() },
                        allSongs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                        startPositionMs
                    )
                }

                MusicService.ARTIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val artistId = path.getOrNull(1) ?: return@future defaultResult
                    val songs = database.artistSongsByCreateDateAsc(artistId).first()
                    MediaItemsWithStartPosition(
                        songs.map { it.toMediaItem() },
                        songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                        startPositionMs
                    )
                }

                MusicService.ALBUM -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val albumId = path.getOrNull(1) ?: return@future defaultResult
                    val albumWithSongs = database.albumWithSongs(albumId).first() ?: return@future defaultResult
                    MediaItemsWithStartPosition(
                        albumWithSongs.songs.map { it.toMediaItem() },
                        albumWithSongs.songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                        startPositionMs
                    )
                }

                MusicService.PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult
                    val songs = when (playlistId) {
                        PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(SongSortType.CREATE_DATE, descending = true)
                        PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                            val downloads = downloadUtil.downloads.value
                            database
                                .allSongs()
                                .flowOn(Dispatchers.IO)
                                .map { songs ->
                                    songs.filter {
                                        downloads[it.id]?.state == Download.STATE_COMPLETED
                                    }
                                }.map { songs ->
                                    songs
                                        .map { it to downloads[it.id] }
                                        .sortedBy { it.second?.updateTimeMs ?: 0L }
                                        .map { it.first }
                                }
                        }
                        else -> database.playlistSongs(playlistId).map { list ->
                            list.map { it.song }
                        }
                    }.first()

                    // Check if this is a shuffle action
                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            songs.shuffled().map { it.toMediaItem() },
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            songs.map { it.toMediaItem() },
                            songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                            startPositionMs
                        )
                    }
                }

                MusicService.YOUTUBE_PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult

                    val songs = try {
                        YouTube.playlist(playlistId).getOrNull()?.songs?.map {
                            it.toMediaItem()
                        } ?: emptyList()
                    } catch (e: Exception) {
                        reportException(e)
                        return@future defaultResult
                    }

                    // Check if this is a shuffle action
                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            songs.shuffled(),
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            songs,
                            songs.indexOfFirst { it.mediaId.endsWith(songId) }.takeIf { it != -1 } ?: 0,
                            C.TIME_UNSET
                        )
                    }
                }

                MusicService.SEARCH -> {
                    // A song tapped in the Android Auto search results: "search/<query>/<songId>"
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val searchQuery = path.getOrNull(1) ?: return@future defaultResult
                    val online = context.isInternetConnected()
                    if (songId.isBlank()) {
                        return@future playSong(searchQuery, online) ?: defaultResult
                    }
                    val selectedSong = database.song(songId).first()
                        ?: searchSongs(searchQuery, online).songs.firstOrNull { it.id == songId }
                        ?: return@future defaultResult
                    startFromSong(selectedSong, online) ?: defaultResult
                }

                else -> defaultResult
            }
        }

    // ---------------------------------------------------------------------------------------
    // Voice requests
    // ---------------------------------------------------------------------------------------

    private class SongSearchResults(
        /** Local matches first, then YouTube results. */
        val songs: List<Song>,
        /** Number of local songs at the start of [songs]. */
        val localCount: Int,
    )

    private suspend fun handleVoiceRequest(
        session: MediaSession,
        requestMetadata: MediaItem.RequestMetadata,
    ): MediaItemsWithStartPosition? {
        val extras = requestMetadata.extras
        val request = VoiceQueryParser.parse(
            query = requestMetadata.searchQuery,
            focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS),
            artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
            playlist = extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST),
            title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
        )
        val online = context.isInternetConnected()
        Timber.tag(VOICE_TAG).d("Voice request %s (online=%s)", request, online)

        val result = try {
            when (request.type) {
                VoiceQueryParser.Type.ANY -> playAnything(online)
                VoiceQueryParser.Type.LIKED -> playLiked(online)
                VoiceQueryParser.Type.DOWNLOADED -> playDownloaded()
                VoiceQueryParser.Type.PLAYLIST -> playPlaylist(request.query, online)
                VoiceQueryParser.Type.ALBUM -> playAlbum(request.query, online)
                VoiceQueryParser.Type.ARTIST -> playArtist(request.query, online)
                VoiceQueryParser.Type.SONG -> playSong(request.query, online)
            }
        } catch (e: Exception) {
            reportException(e)
            null
        }

        if (result == null) sendVoiceError(session, request, online)
        return result
    }

    /** Tells the controller (Android Auto, Metrolist itself) why nothing is playing. */
    private suspend fun sendVoiceError(
        session: MediaSession,
        request: VoiceQueryParser.Request,
        online: Boolean,
    ) {
        val message = when {
            request.query.isBlank() -> context.getString(R.string.voice_nothing_to_play)
            !online -> context.getString(R.string.voice_offline_no_result, request.query)
            else -> context.getString(R.string.voice_no_result, request.query)
        }
        Timber.tag(VOICE_TAG).d("Voice request failed: %s", message)
        withContext(Dispatchers.Main) {
            session.sendError(SessionError(SessionError.ERROR_UNKNOWN, message))
        }
    }

    private fun isDownloaded(songId: String): Boolean =
        downloadUtil.downloads.value[songId]?.state == Download.STATE_COMPLETED

    /** Offline, only downloaded songs can be played. */
    private fun List<Song>.playableOffline(online: Boolean): List<Song> =
        if (online) this else filter { isDownloaded(it.id) }

    private suspend fun playQueue(
        items: List<MediaItem>,
        title: String?,
    ): MediaItemsWithStartPosition? {
        if (items.isEmpty()) return null
        withContext(Dispatchers.Main) {
            service.adoptQueue(ListQueue(title = title, items = items), title = title)
        }
        return MediaItemsWithStartPosition(items, 0, C.TIME_UNSET)
    }

    private suspend fun playSongs(
        songs: List<Song>,
        title: String?,
        shuffle: Boolean,
    ): MediaItemsWithStartPosition? =
        playQueue((if (shuffle) songs.shuffled() else songs).map { it.toMediaItem() }, title)

    private fun List<SongItem>.filterForPlayback(): List<SongItem> =
        filterExplicit(context.dataStore.get(HideExplicitKey, false))
            .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))

    /** "Play music": liked songs, or the whole library if nothing is liked, shuffled. */
    private suspend fun playAnything(online: Boolean): MediaItemsWithStartPosition? {
        val liked = database.likedSongs(SongSortType.CREATE_DATE, descending = true).first().playableOffline(online)
        if (liked.isNotEmpty()) return playSongs(liked, context.getString(R.string.liked), shuffle = true)
        val library = database.songsByCreateDateAsc().first().playableOffline(online)
        return playSongs(library, null, shuffle = true)
    }

    private suspend fun playLiked(online: Boolean): MediaItemsWithStartPosition? {
        val liked = database.likedSongs(SongSortType.CREATE_DATE, descending = true).first().playableOffline(online)
        return playSongs(liked, context.getString(R.string.liked), shuffle = true)
    }

    private suspend fun playDownloaded(): MediaItemsWithStartPosition? {
        val downloaded = database.allSongs().first().filter { isDownloaded(it.id) }
        return playSongs(downloaded, context.getString(R.string.downloaded_songs), shuffle = true)
    }

    /** Local playlist with a close enough name ("sport" -> "Sport 💪"), else a YouTube Music playlist. */
    private suspend fun playPlaylist(name: String, online: Boolean): MediaItemsWithStartPosition? {
        val localPlaylist = VoiceSearchMatcher.bestByName(name, database.playlistsByNameAsc().first()) {
            it.playlist.name
        }
        if (localPlaylist != null) {
            val songs = database.playlistSongs(localPlaylist.playlist.id).first().map { it.song }.playableOffline(online)
            playSongs(songs, localPlaylist.playlist.name, shuffle = false)?.let { return it }
        }
        if (!online) return null

        val onlinePlaylists = listOf(YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST)
            .flatMap { filter ->
                YouTube.search(name, filter).getOrNull()?.items?.filterIsInstance<PlaylistItem>().orEmpty()
            }
        val playlist = VoiceSearchMatcher.bestByName(name, onlinePlaylists) { it.title }
            ?: onlinePlaylists.firstOrNull()
            ?: return null
        val songs = YouTube.playlist(playlist.id).getOrNull()?.songs?.filterForPlayback().orEmpty()
        return playQueue(songs.map { it.toMediaItem() }, playlist.title)
    }

    /** Album from the library, else from YouTube Music, played in order. */
    private suspend fun playAlbum(name: String, online: Boolean): MediaItemsWithStartPosition? {
        val localAlbum = VoiceSearchMatcher.bestByName(name, database.albumsByNameAsc().first()) {
            it.album.title
        }
        if (localAlbum != null) {
            val songs = database.albumWithSongs(localAlbum.album.id).first()?.songs.orEmpty().playableOffline(online)
            playSongs(songs, localAlbum.album.title, shuffle = false)?.let { return it }
        }
        if (!online) return null

        val albums = YouTube.search(name, YouTube.SearchFilter.FILTER_ALBUM).getOrNull()
            ?.items?.filterIsInstance<AlbumItem>().orEmpty()
        val album = VoiceSearchMatcher.bestByName(name, albums) { it.title }
            ?: albums.firstOrNull()
            ?: return null
        val songs = YouTube.album(album.browseId).getOrNull()?.songs?.filterForPlayback().orEmpty()
        return playQueue(songs.map { it.toMediaItem() }, album.title)
    }

    /** Shuffled mix of the artist from YouTube Music, else the artist's songs in the library. */
    private suspend fun playArtist(name: String, online: Boolean): MediaItemsWithStartPosition? {
        if (online) {
            val artists = YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                ?.items?.filterIsInstance<ArtistItem>().orEmpty()
            val artist = VoiceSearchMatcher.bestByName(name, artists) { it.title } ?: artists.firstOrNull()
            if (artist != null) {
                val page = YouTube.artist(artist.id).getOrNull()?.artist
                val endpoint = page?.shuffleEndpoint ?: artist.shuffleEndpoint
                    ?: page?.radioEndpoint ?: artist.radioEndpoint
                if (endpoint != null) {
                    val queue = YouTubeQueue(endpoint)
                    val status = runCatching {
                        queue.getInitialStatus()
                            .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                    }.getOrNull()
                    if (status != null && status.items.isNotEmpty()) {
                        val title = page?.title ?: artist.title
                        withContext(Dispatchers.Main) {
                            service.adoptQueue(queue, title, status.items.size)
                        }
                        return MediaItemsWithStartPosition(status.items, 0, C.TIME_UNSET)
                    }
                }
            }
        }

        val localArtist = VoiceSearchMatcher.bestByName(name, database.artistsByNameAsc().first()) {
            it.artist.name
        } ?: return null
        val songs = database.artistSongsByCreateDateAsc(localArtist.artist.id).first().playableOffline(online)
        return playSongs(songs, localArtist.artist.name, shuffle = true)
    }

    /** A specific song, then the auto radio (or, offline, other downloaded songs). */
    private suspend fun playSong(query: String, online: Boolean): MediaItemsWithStartPosition? {
        // "Play Workout" where "Workout" is exactly the name of a local playlist.
        val exactPlaylist = database.playlistsByNameAsc().first().firstOrNull {
            VoiceSearchMatcher.nameScore(query, it.playlist.name) == 1.0
        }
        if (exactPlaylist != null) {
            val songs = database.playlistSongs(exactPlaylist.playlist.id).first().map { it.song }.playableOffline(online)
            playSongs(songs, exactPlaylist.playlist.name, shuffle = false)?.let { return it }
        }

        val results = searchSongs(query, online)
        // No strong title match (e.g. the title is worded differently): fall back to YouTube's
        // top result, then to the first local hit, instead of playing nothing.
        val selectedSong = VoiceSearchMatcher.findBest(query, results.songs)
            ?: results.songs.getOrNull(results.localCount)
            ?: results.songs.firstOrNull()
            ?: return null
        return startFromSong(selectedSong, online)
    }

    /** Local songs matching [query] (only downloaded ones when offline), then YouTube results. */
    private suspend fun searchSongs(query: String, online: Boolean): SongSearchResults {
        val limit = context.dataStore.get(AndroidAutoSearchLocalLimitKey, 75)
        val localSongs = database.searchSongsExtended(query, limit).first().playableOffline(online)
        if (!online) return SongSearchResults(localSongs, localSongs.size)

        val results = localSongs.toMutableList()
        try {
            val onlineResults = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                .getOrNull()
                ?.items
                ?.filterIsInstance<SongItem>()
                ?.filterForPlayback()
                ?.filter { onlineSong ->
                    localSongs.none { localSong ->
                        localSong.id == onlineSong.id ||
                            (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                localSong.artists.any { artist ->
                                    onlineSong.artists.any { it.name.equals(artist.name, ignoreCase = true) }
                                })
                    }
                }.orEmpty()

            onlineResults.forEach { songItem ->
                try {
                    database.query { insert(songItem.toMediaMetadata()) }
                    database.song(songItem.id).first()?.let { results.add(it) }
                } catch (e: Exception) {
                    reportException(e)
                }
            }
        } catch (e: Exception) {
            reportException(e)
        }
        return SongSearchResults(results, localSongs.size)
    }

    /**
     * Starts [song], followed by:
     * - the YouTube radio of the song if auto radio is enabled (online),
     * - other downloaded songs, shuffled (offline),
     * - nothing otherwise.
     */
    private suspend fun startFromSong(song: Song, online: Boolean): MediaItemsWithStartPosition? {
        if (!online) {
            val others = database.allSongs().first().filter { it.id != song.id && isDownloaded(it.id) }.shuffled()
            return playSongs(listOf(song) + others, song.song.title, shuffle = false)
        }

        if (context.dataStore.get(AutoRadioQueueKey, true)) {
            val radioQueue = YouTubeQueue.radio(song.toMediaMetadata())
            val radioStatus = runCatching {
                radioQueue
                    .getInitialStatus()
                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                    .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
            }.getOrNull()

            if (radioStatus != null && radioStatus.items.isNotEmpty()) {
                // Make sure the requested song is the one that starts, even if the radio
                // (or the explicit/video filters) left it out of the initial items.
                val selectedIndex = radioStatus.items.indexOfFirst { it.mediaId == song.id }
                val radioItems =
                    if (selectedIndex == -1) listOf(song.toMediaItem()) + radioStatus.items
                    else radioStatus.items
                withContext(Dispatchers.Main) {
                    service.adoptQueue(radioQueue, radioStatus.title, radioItems.size) //Used to make the radio queue load more songs when near the end
                }
                return MediaItemsWithStartPosition(radioItems, selectedIndex.coerceAtLeast(0), C.TIME_UNSET)
            }
        }

        return playSongs(listOf(song), song.song.title, shuffle = false)
    }

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .build(),
        ).build()

    private fun Song.toMediaItem(path: String, isPlayable: Boolean = true, isBrowsable: Boolean = false): MediaItem {
        return MediaItem
            .Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                 MediaMetadata
                     .Builder()
                     .setTitle(song.title)
                     .setSubtitle(artists.joinToArtistString(getArtistSeparator(context)) {
                         ArtistNameAliases.resolve(it.id, it.name)
                     })
                     .setArtist(artists.joinToArtistString(getArtistSeparator(context)) {
                         ArtistNameAliases.resolve(it.id, it.name)
                     })
                     .setArtworkUri(song.thumbnailUrl?.toUri())
                    .setIsPlayable(isPlayable)
                    .setIsBrowsable(isBrowsable)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }
}

/**
 * Splits a media id into its path segments. Search ids are "search/<query>/<songId>" and the
 * query itself may contain "/" (e.g. "AC/DC"), so for them everything between the first and
 * the last segment is kept together as the query.
 */
internal fun parseMediaIdPath(mediaId: String): List<String> {
    val parts = mediaId.split("/")
    if (parts.firstOrNull() != MusicService.SEARCH || parts.size <= 3) return parts
    return listOf(parts.first(), parts.subList(1, parts.size - 1).joinToString("/"), parts.last())
}

internal fun isBrowsableMediaId(mediaId: String): Boolean =
    mediaId == MusicService.ROOT ||
        mediaId == MusicService.SONG ||
        mediaId == MusicService.ARTIST ||
        mediaId == MusicService.ALBUM ||
        mediaId == MusicService.PLAYLIST ||
        mediaId == MusicService.YOUTUBE_PLAYLIST ||
        mediaId.startsWith("${MusicService.ARTIST}/") ||
        mediaId.startsWith("${MusicService.ALBUM}/") ||
        mediaId.startsWith("${MusicService.PLAYLIST}/") ||
        mediaId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/")

internal fun <T> List<T>.paginate(
    page: Int,
    pageSize: Int,
): List<T> {
    val fromIndex = (page.toLong() * pageSize).coerceAtMost(size.toLong()).toInt()
    val toIndex = (fromIndex.toLong() + pageSize).coerceAtMost(size.toLong()).toInt()
    return subList(fromIndex, toIndex)
}

internal const val MAX_ANDROID_AUTO_PAGE_SIZE = 500

private const val VOICE_TAG = "VoiceSearch"

internal data class AndroidAutoPageRequest(
    val offset: Int,
    val limit: Int,
) {
    fun afterLeadingItems(count: Int): AndroidAutoPageRequest {
        val leadingItemsOnPage = (count - offset).coerceIn(0, limit)
        return AndroidAutoPageRequest(
            offset = (offset - count).coerceAtLeast(0),
            limit = limit - leadingItemsOnPage,
        )
    }
}

internal fun androidAutoPageRequest(
    page: Int,
    pageSize: Int,
): AndroidAutoPageRequest {
    val safePage = page.coerceAtLeast(0)
    val safePageSize = pageSize.coerceAtLeast(0)
    val effectivePageSize = safePageSize.coerceAtMost(MAX_ANDROID_AUTO_PAGE_SIZE)
    return AndroidAutoPageRequest(
        offset = (safePage.toLong() * effectivePageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        limit = effectivePageSize,
    )
}
