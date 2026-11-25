package com.happyu.toolwindow;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.layout.migLayout.patched.MigLayout;
import com.intellij.util.ui.UIUtil;
import net.miginfocom.layout.CC;
import net.miginfocom.layout.LC;

import javax.swing.BoxLayout;
import javax.swing.GroupLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;

public final class HappyuTabScrollerTest extends LightPlatformTestCase {
    private ContentManager manager;
    private JPanel tabs;
    private JPanel header;
    private HappyuTabScroller scroller;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        manager = ContentFactory.getInstance().createContentManager(true, getProject());
        Disposer.register(getTestRootDisposable(), manager);
        tabs = new JPanel();
        tabs.setLayout(new BoxLayout(tabs, BoxLayout.X_AXIS));
        for (int i = 0; i < 8; i++) {
            Content content = manager.getFactory().createContent(new JPanel(), "Tab " + i, false);
            manager.addContent(content);
            JLabel label = new JLabel(content.getDisplayName());
            label.setPreferredSize(new Dimension(160, 28));
            label.setMinimumSize(label.getPreferredSize());
            label.setMaximumSize(label.getPreferredSize());
            tabs.add(label);
        }
        manager.setSelectedContent(manager.getContent(0));
        header = new JPanel();
    }

    private void install(boolean groupLayout) {
        if (groupLayout) {
            GroupLayout layout = new GroupLayout(header);
            header.setLayout(layout);
            layout.setHorizontalGroup(layout.createSequentialGroup()
                    .addComponent(tabs, GroupLayout.DEFAULT_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE));
            layout.setVerticalGroup(layout.createParallelGroup().addComponent(tabs));
        } else {
            header.setLayout(new MigLayout(new LC().insets("0px").gridGap("0px", "0px").fillY()));
            header.add(tabs, new CC().growY());
        }
        scroller = new HappyuTabScroller(manager, tabs);
        header.addNotify();
        layout(250);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            header.removeNotify();
        } finally {
            super.tearDown();
        }
    }

    private void layout(int width) {
        header.setSize(width, 32);
        header.doLayout();
        scroller.doLayout();
        scroller.getViewport().doLayout();
        tabs.doLayout();
    }

    public void testWheelScrollsNativeTabsWithoutSwitchingContentInMigLayout() {
        install(false);
        verifyScrollWithoutSelectionChange();
    }

    public void testWheelScrollsNativeTabsWithoutSwitchingContentInGroupLayout() {
        install(true);
        verifyScrollWithoutSelectionChange();
    }

    public void testLegacyLayoutConstraintsAndSiblingOrderSurviveWrappingAndDisposal() {
        // Include a subclass: IDE header layouts may extend the legacy implementation.
        MigLayout layout = new MigLayout(new LC().insets("0px").gridGap("0px", "0px").fillY()) { };
        header.setLayout(layout);
        JLabel before = new JLabel("Before");
        JLabel after = new JLabel("After");
        CC constraints = new CC().growY().pushX();
        header.add(before);
        header.add(tabs, constraints);
        header.add(after);

        scroller = new HappyuTabScroller(manager, tabs);
        assertSame(before, header.getComponent(0));
        assertSame(scroller, header.getComponent(1));
        assertSame(after, header.getComponent(2));
        assertSame(constraints, layout.getComponentConstraints().get(scroller));
        assertSame(scroller.getViewport(), tabs.getParent());

        Disposer.dispose(scroller);
        assertSame(before, header.getComponent(0));
        assertSame(tabs, header.getComponent(1));
        assertSame(after, header.getComponent(2));
        assertSame(constraints, layout.getComponentConstraints().get(tabs));
        assertEquals(8, tabs.getComponentCount());
    }

    private void verifyScrollWithoutSelectionChange() {
        assertTrue(scroller.getViewport().getViewSize().width > scroller.getViewport().getExtentSize().width);
        for (Component label : tabs.getComponents()) assertTrue(label.getWidth() >= 160);
        Content selected = manager.getSelectedContent();
        // Dispatch over a child label, just as a real wheel event bubbles to the scroll pane.
        MouseWheelEvent event = wheel(tabs.getComponent(0), 1, 0);
        tabs.getComponent(0).dispatchEvent(event);
        assertTrue(scroller.getViewport().getViewPosition().x > 0);
        assertSame(selected, manager.getSelectedContent());
        scroller.dispatchEvent(wheel(scroller, 100, 0));
        int max = scroller.getViewport().getViewSize().width - scroller.getViewport().getExtentSize().width;
        assertEquals(max, scroller.getViewport().getViewPosition().x);
        scroller.dispatchEvent(wheel(scroller, -100, 0));
        assertEquals(0, scroller.getViewport().getViewPosition().x);
        assertSame(selected, manager.getSelectedContent());
    }

    public void testPreciseWheelAndModifiersAndResize() {
        install(true);
        for (int i = 0; i < 10; i++) scroller.dispatchEvent(wheel(scroller, 0.05, 0));
        assertTrue(scroller.getViewport().getViewPosition().x > 0);
        int before = scroller.getViewport().getViewPosition().x;
        scroller.dispatchEvent(wheel(scroller, 1, InputEvent.CTRL_DOWN_MASK));
        assertEquals(before, scroller.getViewport().getViewPosition().x);
        layout(2000);
        scroller.dispatchEvent(wheel(scroller, 1, 0));
        assertEquals(0, scroller.getViewport().getViewPosition().x);
        assertSame(manager.getContent(0), manager.getSelectedContent());
    }

    public void testRevealAndDisposePreserveNativeLabelsAndLayout() {
        install(true);
        Rectangle last = tabs.getComponent(7).getBounds();
        scroller.reveal(last);
        Rectangle visible = scroller.getViewport().getViewRect();
        assertTrue(visible.contains(last));
        assertSame(manager.getContent(0), manager.getSelectedContent());
        Disposer.dispose(scroller);
        header.doLayout();
        assertSame(header, tabs.getParent());
        assertEquals(8, tabs.getComponentCount());
        assertSame(manager.getContent(0), manager.getSelectedContent());
    }

    public void testHeaderRebuildRewrapsTheSameNativeTabComponent() {
        install(true);
        JPanel rebuilt = new JPanel(new MigLayout());
        rebuilt.add(tabs, new CC().growY());
        UIUtil.dispatchAllInvocationEvents();
        assertSame(scroller, rebuilt.getComponent(0));
        assertSame(scroller.getViewport(), tabs.getParent());
        assertEquals(8, tabs.getComponentCount());
        assertSame(manager.getContent(0), manager.getSelectedContent());
    }

    private static MouseWheelEvent wheel(Component source, double rotation, int modifiers) {
        return new MouseWheelEvent(source, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers,
                2, 2, 2, 2, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, (int) rotation, rotation);
    }
}
