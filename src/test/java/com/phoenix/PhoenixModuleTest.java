package com.phoenix;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.adapter.impl.AndroidAdapter;
import com.phoenix.adapter.impl.Esp32Adapter;
import com.phoenix.adapter.impl.LinuxAdapter;
import com.phoenix.core.PhoenixModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.inject.ProvisionException;    // added by me to check IllegalArgumentException error

/**
 * This test IS the Day 1 milestone: it proves, mechanically and
 * repeatably, that Phoenix Core resolves a different concrete hardware
 * class purely by changing a system property — no source file in
 * core/ or App.java is touched between assertions.
 */
class PhoenixModuleTest {

    @AfterEach
    void resetSelection() {
        System.clearProperty("phoenix.adapter");
    }

    @Test
    void defaultsToLinuxAdapter() {
        Injector injector = Guice.createInjector(new PhoenixModule());
        IHardwareAdapter adapter = injector.getInstance(IHardwareAdapter.class);
        assertInstanceOf(LinuxAdapter.class, adapter);
    }

    @Test
    void swapsToAndroidAdapterViaSystemPropertyOnly() {
        System.setProperty("phoenix.adapter", "android");
        Injector injector = Guice.createInjector(new PhoenixModule());
        IHardwareAdapter adapter = injector.getInstance(IHardwareAdapter.class);
        assertInstanceOf(AndroidAdapter.class, adapter);
    }

    @Test
    void swapsToEsp32AdapterViaSystemPropertyOnly() {
        System.setProperty("phoenix.adapter", "esp32");
        Injector injector = Guice.createInjector(new PhoenixModule());
        IHardwareAdapter adapter = injector.getInstance(IHardwareAdapter.class);
        assertInstanceOf(Esp32Adapter.class, adapter);
    }

    @Test
    void rejectsUnknownAdapterNameWithClearError() {
        System.setProperty("phoenix.adapter", "raspberrypi");
        Injector injector = Guice.createInjector(new PhoenixModule());

        ProvisionException exception = assertThrows(
            ProvisionException.class,
            () -> injector.getInstance(IHardwareAdapter.class)
        );

        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    }
}
