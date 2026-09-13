package com.phoenix.core;

import java.util.Map;
import java.util.Set;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.adapter.IHardwareAdapter;

/**
 * Resolves a node's {@code adapterType} string to the concrete
 * {@link IHardwareAdapter} that knows how to execute on that hardware class.
 *
 * <p>This retires Day 1's "one active adapter for the whole JVM" concept
 * ({@code -Dphoenix.adapter=...} -&gt; {@code provideActiveAdapter()}). That
 * design could only ever run one hardware class at a time. Once the
 * DeviceRegistry can hold Linux + Android + ESP32 nodes simultaneously, the
 * Dispatcher needs to resolve a *different* adapter per task depending on
 * which node the Scheduler picked — this class is that lookup.
 *
 * <p>Guice still owns the {@code Map<String, IHardwareAdapter>} wiring (see
 * {@link PhoenixModule}); this class just wraps that map with a friendlier,
 * fail-fast API so callers don't deal with {@code null} lookups themselves.
 */
@Singleton
public class AdapterRegistry {

    private final Map<String, IHardwareAdapter> adaptersByType;

    @Inject
    public AdapterRegistry(Map<String, IHardwareAdapter> adaptersByType) {
        this.adaptersByType = adaptersByType;
    }

    public IHardwareAdapter resolve(String adapterType) {
        IHardwareAdapter adapter = adaptersByType.get(adapterType.toLowerCase());
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "No adapter registered for type '" + adapterType + "'. Available: " + availableTypes());
        }
        return adapter;
    }

    public Set<String> availableTypes() {
        return adaptersByType.keySet();
    }
}
