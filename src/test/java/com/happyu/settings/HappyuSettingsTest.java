package com.happyu.settings;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.xmlb.XmlSerializer;

import javax.swing.JComponent;
import javax.swing.JSpinner;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicInteger;

public final class HappyuSettingsTest extends LightPlatformTestCase {
    private HappyuSettings settings;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        settings = getProject().getService(HappyuSettings.class);
        settings.loadState(new HappyuSettings.Options());
    }

    public void testDefaultOptionsAndXmlRoundTripAreDetachedAndValidated() {
        assertFalse(settings.getState().autoSleepEnabled);
        assertEquals(15, settings.getState().idleMinutes);
        settings.update(true, 22);
        HappyuSettings.Options saved = settings.getState();
        HappyuSettings.Options restored = XmlSerializer.deserialize(XmlSerializer.serialize(saved), HappyuSettings.Options.class);
        settings.loadState(restored);
        saved.idleMinutes = 1;
        restored.autoSleepEnabled = false;
        assertTrue(settings.getState().autoSleepEnabled);
        assertEquals(22, settings.getState().idleMinutes);
        restored.idleMinutes = -10;
        settings.loadState(restored);
        assertEquals(1, settings.getState().idleMinutes);
        settings.update(false, Integer.MAX_VALUE);
        assertEquals(1440, settings.getState().idleMinutes);
    }

    public void testOnlyChangedOptionsNotifyLiveControllers() {
        AtomicInteger notifications = new AtomicInteger();
        getProject().getMessageBus().connect(getTestRootDisposable())
                .subscribe(HappyuSettings.TOPIC, notifications::incrementAndGet);
        settings.update(false, 15);
        assertEquals(0, notifications.get());
        settings.update(true, 15);
        settings.update(true, 15);
        assertEquals(1, notifications.get());
        settings.update(false, 30);
        assertEquals(2, notifications.get());
    }

    public void testSettingsUiAppliesResetsAndRecreatesSavedOptions() throws Exception {
        HappyuConfigurable configurable = new HappyuConfigurable(getProject());
        try {
            JComponent ui = configurable.createComponent();
            JBCheckBox check = find(ui, JBCheckBox.class);
            JSpinner minutes = find(ui, JSpinner.class);
            JBLabel label = find(ui, JBLabel.class);
            assertFalse(check.isSelected());
            assertFalse(minutes.isEnabled());
            assertFalse(label.isEnabled());
            check.doClick();
            assertTrue(minutes.isEnabled());
            minutes.setValue(30);
            assertTrue(configurable.isModified());
            configurable.apply();
            assertTrue(settings.getState().autoSleepEnabled);
            assertEquals(30, settings.getState().idleMinutes);
            assertFalse(configurable.isModified());
            check.doClick();
            minutes.setValue(5);
            configurable.reset();
            assertTrue(check.isSelected());
            assertTrue(minutes.isEnabled());
            assertTrue(label.isEnabled());
            assertEquals(30, minutes.getValue());
            settings.update(false, 45);
            configurable.reset();
            assertFalse(minutes.isEnabled());
            assertFalse(label.isEnabled());
            configurable.disposeUIResources();
            assertFalse(configurable.isModified());
            ui = configurable.createComponent();
            assertFalse(find(ui, JBCheckBox.class).isSelected());
            assertEquals(45, find(ui, JSpinner.class).getValue());
        } finally {
            configurable.disposeUIResources();
        }
    }

    public void testInvalidIdleTimeDoesNotOverwriteSettings() {
        HappyuConfigurable configurable = new HappyuConfigurable(getProject());
        try {
            JComponent ui = configurable.createComponent();
            find(ui, JBCheckBox.class).doClick();
            find(ui, JSpinner.class).setValue(0);
            try {
                configurable.apply();
                fail("Invalid idle time was accepted");
            } catch (ConfigurationException expected) {
                assertFalse(settings.getState().autoSleepEnabled);
                assertEquals(15, settings.getState().idleMinutes);
            }
        } finally {
            configurable.disposeUIResources();
        }
    }

    private static <T> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                T found = find(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
