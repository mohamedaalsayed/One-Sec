package com.onesec.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * "Just this one": the user opened a guarded app from a link someone sent
 * them and chose to watch only that.
 *
 * The session ends with a check-in screen as soon as they swipe on to the
 * next reel or video, scroll down past the post, or after [TIMEOUT_MS] as
 * a fallback (DMs, or anything whose scrolling we can't read). Leaving the app or turning the screen off
 * ends it quietly — see AppWatcherService.
 */
object LinkSession {

    private const val TIMEOUT_MS = 90_000L

    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    /** Guarded app the session is in, or null when no session is running. */
    var pkg: String? = null
        private set
    private var source: String? = null

    // Item each list started on, by list class. Reel viewers report the
    // first visible item as fromIndex, which moves up by one per swipe:
    // Instagram's (a ViewPager) from 0, Facebook's (a RecyclerView) from 1.
    private val startIndex = mutableMapOf<String, Int>()

    // Posts are lists of several rows (header, media, caption...) that show
    // a few at once, so there's no page to count: track how far down the
    // user has scrolled instead, and step in past this much of a screen
    private const val PAST_POST_SCREENS = 0.8f
    private var scrolledDownPx = 0

    // Same, but counting comment sheets too: a reel swipe always moves well
    // over a third of a screen, a row change inside a tall post may not
    private var movedDownPx = 0

    // A comments sheet or dialog is open over the content. Scrolling it is
    // reading comments, not moving on. It opens with a window event whose
    // class isn't an Activity; the content's Activity shows up again when
    // it closes (Facebook does; Instagram doesn't, so after its comments
    // only the timer applies).
    private var sheetOpen = false

    val isActive get() = pkg != null

    fun start(ctx: Context, pkg: String, source: String) {
        end()
        this.pkg = pkg
        this.source = source
        val appCtx = ctx.applicationContext
        timeout = Runnable { checkIn(appCtx) }.also { handler.postDelayed(it, TIMEOUT_MS) }
    }

    fun end() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        pkg = null
        source = null
        startIndex.clear()
        scrolledDownPx = 0
        movedDownPx = 0
        sheetOpen = false
    }

    /** Window change in the session's app. */
    fun onWindow(cls: String) {
        sheetOpen = when {
            cls.endsWith("Activity") -> false
            // Starting window while an activity comes up, not a sheet
            cls == "android.widget.FrameLayout" -> sheetOpen
            else -> true
        }
    }

    /** Scroll event from the session's app. */
    fun onScroll(ctx: Context, event: AccessibilityEvent) {
        val cls = event.className?.toString() ?: return
        if (!cls.contains("ViewPager") && !cls.contains("RecyclerView")) return
        // Layout passes report no position (-1); nothing moved
        if (event.fromIndex < 0) return
        // Horizontal paging (carousel posts, stories) isn't moving on
        if (event.scrollDeltaY == 0 && event.scrollDeltaX != 0) return

        // Net distance down, so scrolling back up to the post counts against it
        movedDownPx += event.scrollDeltaY
        if (!sheetOpen) {
            scrolledDownPx += event.scrollDeltaY
            // Several rows on screen: a post (or the feed under it)
            if (event.toIndex - event.fromIndex > 1 &&
                scrolledDownPx > ctx.resources.displayMetrics.heightPixels * PAST_POST_SCREENS
            ) {
                checkIn(ctx)
                return
            }
        }
        if (event.toIndex - event.fromIndex > 1) return

        val start = startIndex.getOrPut(cls) {
            when {
                // Instagram: a reel opened from a link is page 0. (Not learned
                // from the first event: a quick flick can report page 1 straight
                // away, which made the check-in wait for a second swipe.)
                cls.contains("ViewPager") -> 0
                // Already landed on the next item, its swipe's earlier events
                // merged away: the user started one item back
                event.fromIndex == event.toIndex &&
                    event.scrollDeltaY > ctx.resources.displayMetrics.heightPixels / 3 ->
                    event.fromIndex - 1
                else -> event.fromIndex
            }
        }
        // One item on screen (two mid-swipe): a reel/video viewer
        val swiped = cls.contains("ViewPager") ||
            movedDownPx > ctx.resources.displayMetrics.heightPixels / 3
        if (event.fromIndex > start && swiped) checkIn(ctx)
    }

    private fun checkIn(ctx: Context) {
        val p = pkg ?: return
        val s = source ?: return
        end()

        // Drop the open-anyway bypass so that if the app covers the check-in,
        // the service brings it back like any other pause
        AppPrefs.clearAllowedPackage(ctx)
        InterventionActivity.pendingPackage = p
        ctx.startActivity(InterventionActivity.intent(ctx, p, InterventionActivity.Mode.CHECK_IN, s))
    }
}
