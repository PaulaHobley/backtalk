package com.google.android.accessibility.talkback.eventprocessor

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageTurnMonitorTest {
  private fun page(number: Int, pkg: String = "com.amazon.kindle") =
    AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
      packageName = pkg
      text.add("Page $number of 200")
    }

  @Test fun pagerChangesSoundButOrdinaryListScrollingDoesNot() {
    val monitor = PageTurnMonitor()
    fun scroll(index: Int, cls: String) =
      AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED).apply {
        packageName = "reader.example"
        className = cls
        fromIndex = index
        toIndex = index
        itemCount = 200
      }
    assertFalse(monitor.onEvent(scroll(10, "androidx.viewpager.widget.ViewPager")))
    assertTrue(monitor.onEvent(scroll(11, "androidx.viewpager.widget.ViewPager")))
    assertFalse(monitor.onEvent(scroll(11, "androidx.viewpager.widget.ViewPager")))
    assertFalse(monitor.onEvent(scroll(12, "android.widget.ListView")))
  }

  @Test fun forwardSoundsOnceAndBackwardStaysSilent() {
    val monitor = PageTurnMonitor()
    assertFalse(monitor.onEvent(page(10)))
    assertTrue(monitor.onEvent(page(11)))
    assertFalse(monitor.onEvent(page(11)))
    assertFalse(monitor.onEvent(page(10)))
    assertTrue(monitor.onEvent(page(11)))
  }

  @Test fun appSwitchResetsBaselineAndOtherAnnouncementsStaySilent() {
    val monitor = PageTurnMonitor()
    assertFalse(monitor.onEvent(page(10)))
    assertFalse(monitor.onEvent(page(20, "some.other.app")))
    assertFalse(monitor.onEvent(page(21)))
    val paragraph = page(22).apply { text.clear(); text.add("The next page of the story") }
    assertFalse(monitor.onEvent(paragraph))
    assertTrue(monitor.onEvent(page(22)))
  }
}
