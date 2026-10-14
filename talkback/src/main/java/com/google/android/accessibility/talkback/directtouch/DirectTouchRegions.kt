/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.directtouch

import android.graphics.Rect
import android.graphics.Region
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

object DirectTouchRegions {
  /**
   * Whether touches in a window of [windowType] stay with the screen reader. System windows and
   * accessibility overlays always do, and the keyboard does unless [directTyping] is on.
   */
  fun shouldExcludeWindow(windowType: Int, directTyping: Boolean): Boolean {
    if (
      windowType == AccessibilityWindowInfo.TYPE_SYSTEM ||
        windowType == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
    ) {
      return true
    }
    return !directTyping && windowType == AccessibilityWindowInfo.TYPE_INPUT_METHOD
  }

  /**
   * Whether an event of [eventType] should make direct touch look at the screen again once things
   * settle, and send its region again if it is on. The system keeps one passthrough region per
   * display for all services, and some clear it on every window change (Narwhal does, 50 ms after
   * the event), so the last writer wins.
   */
  fun reassertsRegion(eventType: Int): Boolean =
    eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
      eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

  /**
   * Whether a window with these bounds on a display of [displayWidth] by [displayHeight] pixels is
   * a navigation bar: a thin strip along the bottom edge, or along a side edge in landscape, that
   * spans at least half the display. The window can be just the cluster of buttons, so it need not
   * span all of it. The status bar and the notification shade are not navigation bars.
   */
  fun isNavigationBar(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    displayWidth: Int,
    displayHeight: Int,
  ): Boolean {
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) {
      return false
    }
    val alongBottom =
      bottom >= displayHeight && height * 5 <= displayHeight && width * 2 >= displayWidth
    val alongSide =
      (left <= 0 || right >= displayWidth) &&
        width * 5 <= displayWidth &&
        height * 2 >= displayHeight
    return alongBottom || alongSide
  }

  /** The whole display minus the [excluded] rectangles. */
  fun passthroughRegion(displayBounds: Rect, excluded: List<Rect>): Region {
    val region = Region(displayBounds)
    excluded.forEach { region.op(it, Region.Op.DIFFERENCE) }
    return region
  }

  /**
   * Whether an event of [eventType] from an app that marks its own direct touch areas can mean the
   * marked views moved, appeared or went away, without any window changing.
   */
  fun movesMarkedAreas(eventType: Int): Boolean =
    eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
      eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

  /** How [findMarked] reads a tree of views, so that it can be tested without a device. */
  interface Tree<N> {
    fun childCount(node: N): Int

    fun child(node: N, index: Int): N?

    fun isVisible(node: N): Boolean

    /** Whether the app marked the view as a direct touch area. */
    fun isMarked(node: N): Boolean
  }

  /**
   * The visible views under [roots] that are marked as direct touch areas. A view that isn't
   * visible hides its whole subtree, and a marked view's own subtree isn't searched, since all of it
   * is already in the area. The search reads at most [maxNodes] views across all the roots, and goes
   * at most [maxDepth] levels below each root, because every child it reads can be a call into the
   * app's process on the main thread, and an app with a huge tree must not stall Backtalk. Marked
   * views beyond the limits are left out, and their touches stay with Backtalk.
   */
  fun <N : Any> findMarked(roots: List<N>, tree: Tree<N>, maxNodes: Int, maxDepth: Int): List<N> {
    val marked = mutableListOf<N>()
    var visited = 0
    for (root in roots) {
      val stack = ArrayDeque<Pair<N, Int>>()
      stack.addLast(root to 0)
      while (stack.isNotEmpty()) {
        if (visited >= maxNodes) {
          return marked
        }
        val (node, depth) = stack.removeLast()
        visited++
        if (!tree.isVisible(node)) {
          continue
        }
        if (tree.isMarked(node)) {
          marked += node
          continue
        }
        if (depth < maxDepth) {
          // Pushed last to first, so that views are read in order.
          for (index in tree.childCount(node) - 1 downTo 0) {
            tree.child(node, index)?.let { stack.addLast(it to depth + 1) }
          }
        }
      }
    }
    return marked
  }

  /** A window on screen, as far as working out which parts of marked views can be touched. */
  data class LayeredWindow(
    val id: Int,
    /** Windows with a higher layer are drawn on top. */
    val layer: Int,
    val bounds: Rect,
    /**
     * Touches on it always stay with Backtalk, wherever it is in the stack, as [shouldExcludeWindow]
     * decides.
     */
    val alwaysExcluded: Boolean,
  )

  /** A view the app marked as a direct touch area, in the window with [windowId]. */
  data class MarkedArea(val windowId: Int, val bounds: Rect)

  /**
   * The parts of the [marked] views that can take touches directly: each view clipped to its own
   * window, minus every window drawn on top of that window and every window that is always excluded.
   * A dialog from the same app is cut out this way, so it works with Backtalk while the rest of a
   * marked view stays direct. A view whose window isn't in [windows] is left out.
   */
  fun markedRegion(marked: List<MarkedArea>, windows: List<LayeredWindow>): Region {
    val region = Region()
    marked.forEach { area ->
      val window = windows.firstOrNull { it.id == area.windowId } ?: return@forEach
      val part = Region(area.bounds)
      part.op(window.bounds, Region.Op.INTERSECT)
      windows.forEach { other ->
        if (other.id != window.id && (other.alwaysExcluded || other.layer > window.layer)) {
          part.op(other.bounds, Region.Op.DIFFERENCE)
        }
      }
      region.op(part, Region.Op.UNION)
    }
    return region
  }
}
