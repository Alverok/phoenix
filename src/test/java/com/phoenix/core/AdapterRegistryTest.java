package com.phoenix.core;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.adapter.impl.AndroidAdapter;
import com.phoenix.adapter.impl.Esp32Adapter;
import com.phoenix.adapter.impl.LinuxAdapter;

/**
 * Proves AdapterRegistry replaces Day 1's "one active adapter selected by
 * system property" with "resolve any adapter type on demand", which is what
 * lets TaskDispatcher route different tasks to different hardware classes
 * within the same JVM run.
 */
class AdapterRegistryTest {

    private AdapterRegistry registry() {
        Injector injector = Guice.createInjector(new PhoenixModule());
        return injector.getInstance(AdapterRegistry.class);
    }

    @Test
    void resolvesLinuxAdapter() {
        IHardwareAdapter adapter = registry().resolve("linux");
        assertInstanceOf(LinuxAdapter.class, adapter);
    }

    @Test
    void resolvesAndroidAdapter() {
        IHardwareAdapter adapter = registry().resolve("android");
        assertInstanceOf(AndroidAdapter.class, adapter);
    }

    @Test
    void resolvesEsp32Adapter() {
        IHardwareAdapter adapter = registry().resolve("esp32");
        assertInstanceOf(Esp32Adapter.class, adapter);
    }

    @Test
    void rejectsUnknownAdapterTypeWithClearError() {
        AdapterRegistry registry = registry();
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("raspberrypi"));
    }
}
