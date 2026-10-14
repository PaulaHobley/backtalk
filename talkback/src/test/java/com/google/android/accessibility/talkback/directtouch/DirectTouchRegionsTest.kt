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

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectTouchRegionsTest {
  /** A view in a fake tree. A null child is one the app couldn't give. */
  private class Node(
    val name: String,
    val visible: Boolean = true,
    val marked: Boolean = false,
    val children: List<Node?> = emptyList(),
  )

  private object FakeTree : DirectTouchRegions.Tree<Node> {
    override fun childCount(node: Node): Int = node.children.size

    override fun child(node: Node, index: Int): Node? = node.children[index]

    override fun isVisible(node: Node): Boolean = node.visible

    override fun isMarked(node: Node): Boolean = node.marked
  }

  private fun names(roots: List<Node>, maxNodes: Int = 100, maxDepth: Int = 10): List<String> =
    DirectTouchRegions.findMarked(roots, FakeTree, maxNodes, maxDepth).map { it.name }

  @Test
  fun contentChangesAndScrollsCanMoveMarkedAreas() {
    assertTrue(DirectTouchRegions.movesMarkedAreas(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    assertTrue(DirectTouchRegions.movesMarkedAreas(AccessibilityEvent.TYPE_VIEW_SCROLLED))
    assertFalse(DirectTouchRegions.movesMarkedAreas(AccessibilityEvent.TYPE_VIEW_FOCUSED))
    assertFalse(DirectTouchRegions.movesMarkedAreas(AccessibilityEvent.TYPE_VIEW_CLICKED))
  }

  @Test
  fun findsMarkedViewsInOrder() {
    val root =
      Node(
        "root",
        children =
          listOf(
            Node("list", children = listOf(Node("button"), Node("gestures", marked = true))),
            null,
            Node("seek", marked = true),
          ),
      )
    assertEquals(listOf("gestures", "seek"), names(listOf(root)))
  }

  @Test
  fun aHiddenViewHidesItsSubtree() {
    val root =
      Node(
        "root",
        children = listOf(Node("page", visible = false, children = listOf(Node("pad", marked = true)))),
      )
    assertEquals(emptyList<String>(), names(listOf(root)))
  }

  @Test
  fun aHiddenMarkedViewDoesNotCount() {
    val root = Node("root", children = listOf(Node("pad", visible = false, marked = true)))
    assertEquals(emptyList<String>(), names(listOf(root)))
  }

  @Test
  fun aMarkedViewsChildrenAreNotRead() {
    var read = 0
    val tree =
      object : DirectTouchRegions.Tree<Node> by FakeTree {
        override fun child(node: Node, index: Int): Node? {
          read++
          return FakeTree.child(node, index)
        }
      }
    val pad = Node("pad", marked = true, children = listOf(Node("inner", marked = true)))
    val found = DirectTouchRegions.findMarked(listOf(Node("root", children = listOf(pad))), tree, 100, 10)
    assertEquals(listOf("pad"), found.map { it.name })
    assertEquals(1, read)
  }

  @Test
  fun searchStopsAfterMaxNodesAcrossAllRoots() {
    val first = Node("first", children = listOf(Node("a"), Node("b")))
    val second = Node("second", marked = true)
    // Three views in the first window use up the budget, so the second window isn't read.
    assertEquals(emptyList<String>(), names(listOf(first, second), maxNodes = 3))
    assertEquals(listOf("second"), names(listOf(first, second), maxNodes = 4))
  }

  @Test
  fun searchStopsAtMaxDepth() {
    val root = Node("root", children = listOf(Node("mid", children = listOf(Node("deep", marked = true)))))
    assertEquals(emptyList<String>(), names(listOf(root), maxDepth = 1))
    assertEquals(listOf("deep"), names(listOf(root), maxDepth = 2))
  }

  @Test
  fun aMarkedRootCounts() {
    assertEquals(listOf("root"), names(listOf(Node("root", marked = true))))
  }

  @Test
  fun windowEventsReassertTheRegion() {
    // Other services clear the shared region on these, like Narwhal does when a volume slider opens.
    assertTrue(DirectTouchRegions.reassertsRegion(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
    assertTrue(DirectTouchRegions.reassertsRegion(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
  }

  @Test
  fun otherEventsDoNot() {
    assertFalse(DirectTouchRegions.reassertsRegion(AccessibilityEvent.TYPE_VIEW_FOCUSED))
    assertFalse(DirectTouchRegions.reassertsRegion(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    assertFalse(DirectTouchRegions.reassertsRegion(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
  }

  @Test
  fun aThinStripAlongTheBottomIsANavigationBar() {
    assertTrue(DirectTouchRegions.isNavigationBar(0, 2300, 1080, 2400, 1080, 2400))
  }

  @Test
  fun aNavigationBarThatIsOnlyTheButtonClusterIsStillOne() {
    // The window Android reports on a real phone with three-button navigation.
    assertTrue(DirectTouchRegions.isNavigationBar(165, 2245, 895, 2377, 1080, 2377))
  }

  @Test
  fun aThinStripAlongASideInLandscapeIsANavigationBar() {
    assertTrue(DirectTouchRegions.isNavigationBar(2250, 0, 2400, 1080, 2400, 1080))
    assertTrue(DirectTouchRegions.isNavigationBar(0, 0, 150, 1080, 2400, 1080))
  }

  @Test
  fun theStatusBarIsNotANavigationBar() {
    assertFalse(DirectTouchRegions.isNavigationBar(0, 0, 1080, 100, 1080, 2400))
  }

  @Test
  fun theNotificationShadeIsNotANavigationBar() {
    assertFalse(DirectTouchRegions.isNavigationBar(0, 0, 1080, 2400, 1080, 2400))
    assertFalse(DirectTouchRegions.isNavigationBar(0, 1200, 1080, 2400, 1080, 2400))
  }

  @Test
  fun aSmallPopupAtTheBottomIsNotANavigationBar() {
    assertFalse(DirectTouchRegions.isNavigationBar(300, 2300, 700, 2400, 1080, 2400))
  }

  @Test
  fun anEmptyWindowIsNotANavigationBar() {
    assertFalse(DirectTouchRegions.isNavigationBar(0, 2400, 1080, 2400, 1080, 2400))
  }
}
