package com.blokaly.ceres.bitstamp;

import com.blokaly.ceres.bitstamp.event.DiffBookEvent;
import com.blokaly.ceres.bitstamp.event.OrderBookEvent;
import com.blokaly.ceres.common.CommonModule;
import com.blokaly.ceres.common.DumpAndShutdownModule;
import com.blokaly.ceres.common.Exchange;
import com.blokaly.ceres.common.SingleThread;
import com.blokaly.ceres.data.SymbolFormatter;
import com.blokaly.ceres.kafka.KafkaCommonModule;
import com.blokaly.ceres.kafka.ToBProducer;
import com.blokaly.ceres.orderbook.PriceBasedOrderBook;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.*;
import com.netflix.governator.InjectorBuilder;
import com.pusher.client.Pusher;
import com.pusher.client.PusherOptions;
import com.typesafe.config.Config;

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

public class BitstampApp extends AbstractService {

    private final List<PusherClient> clients;

    @Inject
    public BitstampApp(List<PusherClient> clients) {
        this.clients = clients;
    }

    @Override
    protected void doStart() {
        clients.forEach(PusherClient::start);
    }

    @Override
    @PreDestroy
    protected void doStop() {
        clients.forEach(PusherClient::stop);
    }

    public static class BitstampModule extends AbstractModule {

        @Override
        protected void configure() {
            install(new CommonModule());
            install(new KafkaCommonModule());
            bind(Service.class).to(BitstampApp.class);
        }

        @Provides
        @Singleton
        public List<PusherClient> providePusherClients(Config config, Gson gson, ToBProducer producer, @SingleThread Provider<ExecutorService> provider) {
            PusherOptions options = new PusherOptions();
            String exchange = Exchange.valueOf(config.getString("app.exchange").toUpperCase()).getCode();
            return config.getConfig("symbols").entrySet().stream()
                    .map(item -> {
                        String symbol = SymbolFormatter.normalise(item.getKey());
                        OrderBookHandler handler = new OrderBookHandler(new PriceBasedOrderBook(symbol, symbol + "." + exchange), producer, gson, provider.get());
                        String subId = (String) item.getValue().unwrapped();
                        return new PusherClient(new Pusher(subId, options), handler, gson);
                    })
                    .collect(Collectors.toList());
        }

        @Provides
        @Singleton
        public Gson provideGson() {
            GsonBuilder builder = new GsonBuilder();
            builder.registerTypeAdapter(OrderBookEvent.class, new OrderBookEventAdapter());
            builder.registerTypeAdapter(DiffBookEvent.class, new DiffBookEventAdapter());
            return builder.create();
        }

    }

    public static void main(String[] args) throws Exception {
        InjectorBuilder.fromModules(new BitstampModule())
                .createInjector()
                .getInstance(Service.class)
                .startAsync().awaitTerminated();
    }
}
