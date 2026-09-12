package com.phoenix.core;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.inject.multibindings.MapBinder;
import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.adapter.impl.AndroidAdapter;
import com.phoenix.adapter.impl.Esp32Adapter;
import com.phoenix.adapter.impl.LinuxAdapter;
import com.phoenix.dispatch.TaskDispatcher;
import com.phoenix.net.HttpNodeAgentClient;
import com.phoenix.net.NodeAgentClient;
import com.phoenix.net.RegistrationServer;
import com.phoenix.net.TaskQueueService;
import com.phoenix.scheduler.GreedyCostScheduler;
import com.phoenix.scheduler.PlacementCostModel;
import com.phoenix.scheduler.TaskScheduler;
import com.phoenix.scheduler.WeightedPlacementCostModel;

/**
 * Guice wiring for Phoenix Core.
 *
 * <p>Day 1 wired exactly one {@link IHardwareAdapter} out via
 * {@code provideActiveAdapter()}, selected by a system property. Day 2
 * retires that concept: the {@code Map<String, IHardwareAdapter>} built
 * here is no longer used to pick "the" adapter, it's consumed wholesale by
 * {@link AdapterRegistry}, which resolves a *different* adapter per task
 * depending on which node the {@link TaskScheduler} selects. This is what
 * lets Linux, Android, and ESP32 nodes coexist in one run instead of
 * requiring {@code -Dphoenix.adapter=...} per JVM invocation.
 *
 * <p>Also binds the Day-2 orchestration layer:
 * {@link DeviceRegistry} (the memory), {@link TaskScheduler} bound to
 * {@link GreedyCostScheduler} (the brain), and {@link TaskDispatcher}
 * — sorry, dispatcher wiring is implicit via {@code @Singleton}/
 * {@code @Inject} constructor injection, no explicit bind needed for
 * concrete classes.
 */
public class PhoenixModule extends AbstractModule {

    @Override
    protected void configure() {
        MapBinder<String, IHardwareAdapter> adapterBinder =
                MapBinder.newMapBinder(binder(), String.class, IHardwareAdapter.class);

        adapterBinder.addBinding("linux").to(LinuxAdapter.class);
        adapterBinder.addBinding("android").to(AndroidAdapter.class);
        adapterBinder.addBinding("esp32").to(Esp32Adapter.class);

        // Scheduler abstraction: Day 2 binds the Greedy implementation.
        // Swapping to a future MemeticPlacementScheduler or
        // SustainabilityAwareScheduler is a one-line change here — nothing
        // in dispatch/ or core/ needs to know.
        bind(TaskScheduler.class).to(GreedyCostScheduler.class).in(Singleton.class);
        bind(PlacementCostModel.class).to(WeightedPlacementCostModel.class).in(Singleton.class);

        bind(DeviceRegistry.class).in(Singleton.class);
        bind(AdapterRegistry.class).in(Singleton.class);
        bind(RegistrationServer.class).in(Singleton.class);
        bind(TaskQueueService.class).in(Singleton.class);
        bind(NodeAgentClient.class).to(HttpNodeAgentClient.class).in(Singleton.class);
    }
}
