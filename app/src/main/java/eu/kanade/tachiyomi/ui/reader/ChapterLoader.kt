package eu.kanade.tachiyomi.ui.reader

import android.net.Uri
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.plusAssign
import rx.Observable
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription
import timber.log.Timber
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class ChapterLoader(
        private val downloadManager: DownloadManager,
        private val chapterCache: ChapterCache,
        private val manga: Manga,
        private val source: Source
) {

    private val queue = PriorityBlockingQueue<PriorityPage>()
    private val subscriptions = CompositeSubscription()

    fun init() {
        prepareOnlineReading()
    }

    fun restart() {
        cleanup()
        init()
    }

    fun cleanup() {
        subscriptions.clear()
        queue.clear()
    }

    private fun prepareOnlineReading() {
        if (source !is HttpSource) return

        subscriptions += Observable.defer { Observable.just(queue.take().page) }
                .filter { it.status == Page.QUEUE }
                .concatMap { source.fetchImageFromCacheThenNet(it) }
                .repeat()
                .subscribeOn(Schedulers.io())
                .subscribe({
                }, { error ->
                    if (error !is InterruptedException) {
                        Timber.e(error)
                    }
                })
    }

    fun loadChapter(chapter: ReaderChapter) = Observable.just(chapter)
            .flatMap {
                if (chapter.pages == null)
                    retrievePageList(chapter)
                else
                    Observable.just(chapter.pages!!)
            }
            .doOnNext { pages ->
                if (pages.isEmpty()) {
                    throw Exception("Page list is empty")
                }

                // Now that the number of pages is known, fix the requested page if the last one
                // was requested.
                if (chapter.requestedPage == -1) {
                    chapter.requestedPage = pages.lastIndex
                }

                loadPages(chapter)
            }
            .map { chapter }

    private fun retrievePageList(chapter: ReaderChapter) = Observable.just(chapter)
            .flatMap {
                // Check if the chapter is downloaded.
                chapter.isDownloaded = downloadManager.isChapterDownloaded(chapter, manga, true)

                if (chapter.isDownloaded) {
                    // Fetch the page list from disk.
                    downloadManager.buildPageList(source, manga, chapter)
                } else {
                    (source as? HttpSource)?.fetchPageListFromCacheThenNet(chapter)
                            ?: source.fetchPageList(chapter)
                }
            }
            .doOnNext { pages ->
                chapter.pages = pages
                pages.forEach { it.chapter = chapter }
            }

    private fun loadPages(chapter: ReaderChapter) {
        if (!chapter.isDownloaded) {
            loadOnlinePages(chapter)
        }
    }

    private fun loadOnlinePages(chapter: ReaderChapter) {
        chapter.pages?.let { pages ->
            val startPage = chapter.requestedPage
            val pagesToLoad = if (startPage == 0)
                pages
            else
                pages.drop(startPage)

            pagesToLoad.forEach { queue.offer(PriorityPage(it, 0)) }
        }
    }

    fun loadPriorizedPage(page: Page) {
        queue.offer(PriorityPage(page, 1))
    }

    fun retryPage(page: Page) {
        queue.offer(PriorityPage(page, 2))
    }



    private data class PriorityPage(val page: Page, val priority: Int): Comparable<PriorityPage> {

        companion object {
            private val idGenerator = AtomicInteger()
        }

        private val identifier = idGenerator.incrementAndGet()

        override fun compareTo(other: PriorityPage): Int {
            val p = other.priority.compareTo(priority)
            return if (p != 0) p else identifier.compareTo(other.identifier)
        }

    }

    /**
     * Returns an observable with the page list for a chapter. It tries to return the page list from
     * the local cache, otherwise fallbacks to network.
     *
     * @param chapter the chapter whose page list has to be fetched.
     */
    fun HttpSource.fetchPageListFromCacheThenNet(chapter: Chapter): Observable<List<Page>> {
        return chapterCache
                .getPageListFromCache(chapter)
                .onErrorResumeNext { fetchPageList(chapter) }
    }


    /**
     * Returns an observable of the page with the downloaded image.
     *
     * @param page the page whose source image has to be downloaded.
     */
    private fun HttpSource.fetchImageFromCacheThenNet(page: Page): Observable<Page> {
        return if (page.imageUrl.isNullOrEmpty())
            getImageUrl(page).flatMap { getCachedImage(it) }
        else
            getCachedImage(page)
    }

    private fun HttpSource.getImageUrl(page: Page): Observable<Page> {
        page.status = Page.LOAD_PAGE
        return fetchImageUrl(page)
            .doOnError { page.status = Page.ERROR }
            .onErrorReturn { null }
            .doOnNext { page.imageUrl = it }
            .map { page }
    }

    /**
     * Returns an observable of the page that gets the image from the chapter or fallbacks to
     * network and copies it to the cache calling [cacheImage].
     *
     * @param page the page.
     */
    private fun HttpSource.getCachedImage(page: Page): Observable<Page> {
        val imageUrl = page.imageUrl ?: return Observable.just(page)

        return Observable.just(page)
            .flatMap {
                if (!chapterCache.isImageInCache(imageUrl)) {
                    cacheImage(page)
                } else {
                    Observable.just(page)
                }
            }
            .doOnNext {
                page.uri = Uri.fromFile(chapterCache.getImageFile(imageUrl))
                page.status = Page.READY
            }
            .doOnError { page.status = Page.ERROR }
            .onErrorReturn { page }
    }

    /**
     * Returns an observable of the page that downloads the image to [ChapterCache].
     *
     * @param page the page.
     */
    private fun HttpSource.cacheImage(page: Page): Observable<Page> {
        page.status = Page.DOWNLOAD_IMAGE
        return fetchImage(page)
            .doOnNext { chapterCache.putImageToCache(page.imageUrl!!, it) }
            .map { page }
    }

}
