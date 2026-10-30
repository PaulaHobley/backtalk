package com.google.android.accessibility.talkback.eventprocessor;

import android.view.accessibility.AccessibilityEvent;
import com.google.android.accessibility.utils.Role;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes changes in page position, rather than attempts to scroll. */
public final class PageTurnMonitor {
  private static final Pattern PAGE =
      Pattern.compile("(?i)^\\s*page\\s+(\\d+)\\s+(?:of|/)\\s*\\d+[\\s.]*$");
  private final Map<String, Integer> positions = new LinkedHashMap<>();
  private int windowId = -1;
  private String packageName = "";

  public boolean onEvent(AccessibilityEvent event) {
    String pkg = String.valueOf(event.getPackageName());
    if (event.getWindowId() != windowId || !pkg.equals(packageName)) {
      positions.clear();
      windowId = event.getWindowId();
      packageName = pkg;
    }
    // A pager's indexed scroll event is a page change in any app. Lists and continuous
    // scroll views are deliberately excluded, including Kindle's library list.
    if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
        && isPager(event)
        && event.getFromIndex() >= 0
        && event.getFromIndex() == event.getToIndex()) {
      return update("pager:" + event.getClassName(), event.getFromIndex());
    }
    // Kindle versions which expose page announcements instead of a ViewPager.
    // Do not interpret changed book text or focus movement as a page turn.
    if (!"com.amazon.kindle".equals(pkg)
        || (event.getEventType() != AccessibilityEvent.TYPE_ANNOUNCEMENT
            && event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
      return false;
    }
    for (CharSequence text : event.getText()) {
      if (pageLabelChanged(text)) {
        return true;
      }
    }
    return pageLabelChanged(event.getContentDescription());
  }

  private static boolean isPager(AccessibilityEvent event) {
    String cls = String.valueOf(event.getClassName());
    return "androidx.viewpager.widget.ViewPager".equals(cls)
        || "android.support.v4.view.ViewPager".equals(cls)
        || "androidx.viewpager2.widget.ViewPager2".equals(cls)
        || "com.android.internal.widget.ViewPager".equals(cls)
        || Role.getSourceRole(event) == Role.ROLE_PAGER;
  }

  private boolean pageLabelChanged(CharSequence label) {
    if (label == null) {
      return false;
    }
    Matcher match = PAGE.matcher(label);
    if (!match.matches()) {
      return false;
    }
    try {
      return update("kindle-page", Integer.parseInt(match.group(1)));
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private boolean update(String source, int page) {
    Integer previous = positions.put(source, page);
    // Establish a baseline silently when opening a book or changing windows.
    return previous != null && page > previous;
  }
}
