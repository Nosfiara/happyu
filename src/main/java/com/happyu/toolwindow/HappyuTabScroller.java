package com.happyu.toolwindow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.impl.content.BaseLabel;
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import com.intellij.ui.content.impl.ContentManagerImpl;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.GroupLayout;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseWheelEvent;
import java.util.Map;

/**
 * Scrolls the native tool-window tabs without changing the selected content.
 */
final class HappyuTabScroller extends JScrollPane implements Disposable {
    private final JComponent tabs;
    private final ContentManager manager;
    private boolean disposed;
    private boolean wrapping;
    private boolean wrapPending;
    private boolean revealPending;
    private double fractionalScroll;
    private final HierarchyListener hierarchyListener = event -> {
        if ((event.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0) queueWrap();
    };
    private final ContentManagerListener selectionListener = new ContentManagerListener() {
        @Override
        public void selectionChanged(@NotNull ContentManagerEvent event) {
            queueRevealSelected();
        }
    };
    private final ComponentAdapter resizeListener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent event) {
            queueRevealSelected();
        }
    };

    static void install(ContentManager manager) {
        if (manager instanceof ContentManagerImpl impl && impl.getUI() instanceof ToolWindowContentUi ui) {
            new HappyuTabScroller(manager, ui.getTabComponent());
        }
    }

    HappyuTabScroller(ContentManager manager, JComponent tabs) {
        super(VERTICAL_SCROLLBAR_NEVER, HORIZONTAL_SCROLLBAR_NEVER);
        this.manager = manager;
        this.tabs = tabs;
        setBorder(JBUI.Borders.empty());
        setViewportBorder(JBUI.Borders.empty());
        setOpaque(false);
        getViewport().setOpaque(false);
        getViewport().addComponentListener(resizeListener);
        setWheelScrollingEnabled(false);
        getAccessibleContext().setAccessibleName("Browser tabs");
        addMouseWheelListener(this::scrollTabs);
        tabs.addHierarchyListener(hierarchyListener);
        manager.addContentManagerListener(selectionListener);
        Disposer.register(manager, this);
        wrap();
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    private void queueWrap() {
        if (disposed || wrapping || wrapPending || tabs.getParent() == getViewport()) return;
        wrapPending = true;
        SwingUtilities.invokeLater(() -> {
            wrapPending = false;
            if (!disposed) wrap();
        });
    }

    private void wrap() {
        Container parent = tabs.getParent();
        if (parent == null || parent == getViewport()) return;
        wrapping = true;
        try {
            replace(parent, tabs, this);
            setViewportView(tabs);
            parent.revalidate();
            parent.repaint();
            queueRevealSelected();
        } finally {
            wrapping = false;
        }
    }

    private void queueRevealSelected() {
        if (disposed || revealPending) return;
        revealPending = true;
        SwingUtilities.invokeLater(() -> {
            revealPending = false;
            if (!disposed && !manager.isDisposed()) revealSelected();
        });
    }

    private static void replace(Container parent, Component oldComponent, Component replacement) {
        LayoutManager layout = parent.getLayout();
        if (layout instanceof GroupLayout group) {
            // Newer IDE headers refer to their tab component directly in layout groups.
            group.replace(oldComponent, replacement);
        } else {
            Object constraints = componentConstraints(layout, oldComponent);
            int index = parent.getComponentZOrder(oldComponent);
            parent.remove(oldComponent);
            parent.add(replacement, constraints, index);
        }
    }

    private static Object componentConstraints(LayoutManager layout, Component component) {
        if (layout instanceof BorderLayout border) return border.getConstraints(component);
        if (layout == null) return null;
        // Only legacy IDE headers need this adapter. Do not link against the class scheduled for removal.
        // This runs when wrapping/restoring the header, never while scrolling or laying out tabs.
        for (Class<?> type = layout.getClass(); type != null; type = type.getSuperclass()) {
            if (!type.getName().equals("com.intellij.ui.layout.migLayout.patched.MigLayout")) continue;
            try {
                Object constraints = type.getMethod("getComponentConstraints").invoke(layout);
                if (constraints instanceof Map<?, ?> map) return map.get(component);
                throw new IllegalStateException("Unexpected legacy tab layout constraints: " + constraints);
            } catch (ReflectiveOperationException exception) {
                // Read before removing the component so a failed adapter cannot discard its constraints.
                throw new IllegalStateException("Cannot read legacy tab layout constraints", exception);
            }
        }
        return null;
    }

    private void scrollTabs(MouseWheelEvent event) {
        if (disposed || event.isConsumed() || event.isControlDown() || event.isAltDown() || event.isMetaDown()) return;
        int max = Math.max(0, getViewport().getViewSize().width - getViewport().getExtentSize().width);
        if (max == 0) {
            fractionalScroll = 0;
            return;
        }
        double unit = event.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL
                ? getViewport().getExtentSize().width * 0.9 : JBUI.scale(48) * Math.max(1, event.getScrollAmount());
        double distance = event.getPreciseWheelRotation() * unit + fractionalScroll;
        int pixels = (int) distance;
        fractionalScroll = distance - pixels;
        int x = Math.max(0, Math.min(max, getViewport().getViewPosition().x + pixels));
        getViewport().setViewPosition(new Point(x, 0));
        event.consume();
    }

    private void revealSelected() {
        if (tabs.getParent() != getViewport()) return;
        // Lay out at the full natural width, so native layout keeps every tab available.
        doLayout();
        getViewport().doLayout();
        tabs.doLayout();
        Content selected = manager.getSelectedContent();
        if (selected == null) return;
        for (Component component : tabs.getComponents()) {
            if (component instanceof BaseLabel label && label.getContent() == selected) {
                reveal(component.getBounds());
                break;
            }
        }
    }

    void reveal(Rectangle bounds) {
        int width = getViewport().getExtentSize().width;
        if (width <= 0) return;
        int x = getViewport().getViewPosition().x;
        if (bounds.x < x) x = bounds.x;
        else if (bounds.x + bounds.width > x + width) x = bounds.x + bounds.width - width;
        int max = Math.max(0, getViewport().getViewSize().width - width);
        getViewport().setViewPosition(new Point(Math.max(0, Math.min(max, x)), 0));
    }

    @Override
    public void dispose() {
        disposed = true;
        tabs.removeHierarchyListener(hierarchyListener);
        getViewport().removeComponentListener(resizeListener);
        manager.removeContentManagerListener(selectionListener);
        Container parent = getParent();
        if (parent != null && tabs.getParent() == getViewport()) {
            setViewportView(null);
            replace(parent, this, tabs);
            parent.revalidate();
            parent.repaint();
        }
    }
}
