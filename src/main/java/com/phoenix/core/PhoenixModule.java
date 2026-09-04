package com.phoenix.core;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.MapBinder;
import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.adapter.impl.AndroidAdapter;
import com.phoenix.adapter.impl.Esp32Adapter;
import com.phoenix.adapter.impl.LinuxAdapter;

import java.util.Map;

/**
 * Wires every known {@link IHardwareAdapter} implementation into a
 * name -> adapter map, then hands out whichever one is selected by the
 * {@code phoenix.adapter} system property (defaults to "linux").
 *
 * <p>This is deliberately stronger than a single hardcoded
 * {@code bind(IHardwareAdapter.class).to(LinuxAdapter.class)}: swapping
 * hardware classes never requires editing or recompiling ANY Java file,
 * only the command line:
 *
 * <pre>{@code
 * ./gradlew run                              // -> LinuxAdapter (default)
 * ./gradlew run -Dphoenix.adapter=android     // -> AndroidAdapter
 * ./gradlew run -Dphoenix.adapter=esp32       // -> Esp32Adapter
 * }</pre>
 *
 * <p>This also doubles as a first sketch of the Design doc's "IoC Plugin
 * Manager" (Sec. 4): the {@code Map<String, IHardwareAdapter>} here is
 * the seed of the real plugin registry planned for Day 2.
 */
public class PhoenixModule extends AbstractModule {

    @Override
    protected void configure() {
        MapBinder<String, IHardwareAdapter> adapterBinder =
                MapBinder.newMapBinder(binder(), String.class, IHardwareAdapter.class);

        adapterBinder.addBinding("linux").to(LinuxAdapter.class);
        adapterBinder.addBinding("android").to(AndroidAdapter.class);
        adapterBinder.addBinding("esp32").to(Esp32Adapter.class);
    }

    @Provides
    @Singleton
    IHardwareAdapter provideActiveAdapter(Map<String, IHardwareAdapter> adapters) {
        String selected = System.getProperty("phoenix.adapter", "linux").toLowerCase();
        IHardwareAdapter adapter = adapters.get(selected);
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "Unknown adapter type '" + selected + "'. Available: " + adapters.keySet());
        }
        return adapter;
    }
}
