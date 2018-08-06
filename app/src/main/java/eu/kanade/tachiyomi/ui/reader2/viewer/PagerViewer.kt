package eu.kanade.tachiyomi.ui.reader2.viewer

import android.support.v4.view.ViewPager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerReader
import eu.kanade.tachiyomi.ui.reader2.ReaderActivity
import eu.kanade.tachiyomi.ui.reader2.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader2.model.ViewerChapters
import timber.log.Timber

@Suppress("LeakingThis")
abstract class PagerViewer(activity: ReaderActivity) : BaseViewer(activity) {

    val pager = createPager()

    val config = PagerConfig()

    private val adapter = PagerViewerAdapter(this)

    private var awaitingIdleViewerChapters: ViewerChapters? = null

    private var currentPage: Any? = null

    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                awaitingIdleViewerChapters?.let {
                    setChaptersInternal(it)
                    awaitingIdleViewerChapters = null
                }
            }
        }

    init {
        pager.visibility = View.GONE // Don't layout the pager yet
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.offscreenPageLimit = 1
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                val page = adapter.items.getOrNull(position)
                if (page != null && currentPage != page) {
                    currentPage = page
                    when (page) {
                        is ReaderPage -> onPageSelected(page)
                        is ChapterTransition -> onTransitionSelected(page)
                    }
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                isIdle = state == ViewPager.SCROLL_STATE_IDLE
            }
        })
        pager.tapListener = { event ->
            val positionX = event.x

            if (positionX < pager.width * PagerReader.LEFT_REGION) {
                if (config.tappingEnabled) moveLeft()
            } else if (positionX > pager.width * PagerReader.RIGHT_REGION) {
                if (config.tappingEnabled) moveRight()
            } else {
                activity.toggleMenu()
            }
        }
        pager.longTapListener = { _ ->
            val item = adapter.items.getOrNull(pager.currentItem)
            if (item is ReaderPage) {
                activity.onLongTap(item)
            }
        }
        config.imagePropertyChangedListener = {
            refreshAdapter()
        }
    }

    abstract fun createPager(): Pager

    override fun getView(): View {
        return pager
    }

    override fun destroy() {
        super.destroy()
        config.unsubscribe()
    }

    private fun onPageSelected(page: ReaderPage) {
        val pages = page.chapter2.pages!! // Won't be null because it's the loaded chapter
        Timber.w("onPageSelected: ${page.number}/${pages.size}")
        activity.onPageSelected(page)

        if (page === pages.last()) {
            Timber.w("Request preload next chapter because we're at the last page")
            activity.requestPreloadNextChapter()
        }
    }

    private fun onTransitionSelected(transition: ChapterTransition) {
        Timber.w("onTransitionSelected: $transition")
        when (transition) {
            is ChapterTransition.Prev -> {
                Timber.w("Request preload previous chapter because we're on the transition")
                activity.requestPreloadPreviousChapter()
            }
            is ChapterTransition.Next -> {
                Timber.w("Request preload next chapter because we're on the transition")
                activity.requestPreloadNextChapter()
            }
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        if (isIdle) {
            setChaptersInternal(chapters)
        } else {
            awaitingIdleViewerChapters = chapters
        }
    }

    private fun setChaptersInternal(chapters: ViewerChapters) {
        Timber.w("setChaptersInternal")
        adapter.setChapters(chapters)

        // Layout the pager once a chapter is being set
        if (pager.visibility == View.GONE) {
            Timber.w("Pager first layout")
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[chapters.currChapter.requestedPage])
            pager.visibility = View.VISIBLE
        }
    }

    override fun moveToPage(page: ReaderPage) {
        Timber.w("moveToPage")
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            pager.setCurrentItem(position, true)
        } else {
            Timber.w("Page $page not found in adapter")
        }
    }

    open fun moveToNext() {
        moveRight()
    }

    open fun moveToPrevious() {
        moveLeft()
    }

    override fun moveRight() {
        if (pager.currentItem != adapter.count - 1) {
            pager.setCurrentItem(pager.currentItem + 1, config.usePageTransitions)
        }
    }

    override fun moveLeft() {
        if (pager.currentItem != 0) {
            pager.setCurrentItem(pager.currentItem - 1, config.usePageTransitions)
        }
    }

    override fun moveUp() {
        moveToPrevious()
    }

    override fun moveDown() {
        moveToNext()
    }

    override fun moveToNextChapter() {
        Timber.w("moveToNextChapter")
        val position = adapter.items.indexOfLast { it is ChapterTransition.Next }
        if (position != -1) {
            pager.setCurrentItem(position, true)
        }
    }

    override fun moveToPrevChapter() {
        Timber.w("moveToPrevChapter")
        val position = adapter.items.indexOfFirst { it is ChapterTransition.Prev }
        if (position != -1) {
            pager.setCurrentItem(position, true)
        }
    }

    private fun refreshAdapter() {
        val currentItem = pager.currentItem
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    /**
     * Extension method to be called by buttons or other views that want to intercept the default
     * click behavior to change pages or show the menu.
     */
    fun interceptPagerTapListenerOnClick(view: View) {
        view.setOnTouchListener { _, event ->
            pager.setTapListenerEnabled(false)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                pager.setTapListenerEnabled(true)
            }
            false
        }
    }

}
