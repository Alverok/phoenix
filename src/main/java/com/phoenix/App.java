package com.phoenix;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.phoenix.core.PhoenixModule;
import com.phoenix.dispatch.PendingTaskScheduler;
import com.phoenix.net.RegistrationServer;
import com.phoenix.tui.PhoenixTui;

public class App {

    private static final int REGISTRATION_PORT = 8080;

    public static void main(String[] args)
            throws Exception {

        Injector injector =
                Guice.createInjector(
                        new PhoenixModule()
                );

        RegistrationServer server =
                injector.getInstance(
                        RegistrationServer.class
                );

        PendingTaskScheduler pendingTaskScheduler =
                injector.getInstance(
                        PendingTaskScheduler.class
                );

        server.start(
                REGISTRATION_PORT
        );

        pendingTaskScheduler.start();

        System.out.println(
                "Phoenix Core initialized."
        );

        try {

            PhoenixTui tui =
                    injector.getInstance(
                            PhoenixTui.class
                    );

            tui.run();

        } finally {

            pendingTaskScheduler.stop();
            server.stop();
        }
    }
}
