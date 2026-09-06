package com.phoenix.core;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.inject.multibindings.MapBinder;
import com.phoenix.adapter.IHardwareAdapter;
import com.phoenix.adapter.impl.AndroidAdapter;
import com.phoenix.adapter.impl.Esp32Adapter;
import com.phoenix.adapter.impl.LinuxAdapter;
import com.phoenix.net.RegistrationServer;
import com.phoenix.scheduler.GreedyCostScheduler;
import com.phoenix.scheduler.PlacementCostModel;
import com.phoenix.scheduler.TaskScheduler;
import com.phoenix.scheduler.WeightedPlacementCostModel;
import com.phoenix.sim.NodeSimulator;

public class PhoenixModule extends AbstractModule {

    @Override
    protected void configure() {
        MapBinder<String, IHardwareAdapter> adapterBinder =
                MapBinder.newMapBinder(binder(), String.class, IHardwareAdapter.class);

        adapterBinder.addBinding("linux").to(LinuxAdapter.class);
        adapterBinder.addBinding("android").to(AndroidAdapter.class);
        adapterBinder.addBinding("esp32").to(Esp32Adapter.class);

        bind(TaskScheduler.class).to(GreedyCostScheduler.class).in(Singleton.class);
        bind(PlacementCostModel.class).to(WeightedPlacementCostModel.class).in(Singleton.class);

        bind(DeviceRegistry.class).in(Singleton.class);
        bind(AdapterRegistry.class).in(Singleton.class);
        bind(NodeSimulator.class).in(Singleton.class);
        bind(RegistrationServer.class).in(Singleton.class);   // NEW
    }
}