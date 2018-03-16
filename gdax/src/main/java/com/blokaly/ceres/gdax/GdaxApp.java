package com.blokaly.ceres.gdax;

import com.blokaly.ceres.common.CommonModule;
import com.blokaly.ceres.common.DumpAndShutdownModule;
import com.blokaly.ceres.gdax.callback.*;
import com.blokaly.ceres.gdax.event.AbstractEvent;
import com.blokaly.ceres.gdax.event.EventType;
import com.blokaly.ceres.orderbook.PriceBasedOrderBook;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.MapBinder;
import com.netflix.governator.InjectorBuilder;
import com.typesafe.config.Config;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static com.blokaly.ceres.gdax.event.EventType.*;

public class GdaxApp extends AbstractService {

  private final GdaxClient client;

  @Inject
  public GdaxApp(GdaxClient client) {
    this.client = client;
  }

  @Override
  protected void doStart() {
    client.connect();
  }

  @Override
  protected void doStop() {
    client.close();
  }

  public static class GdaxModule extends AbstractModule {

    @Override
    protected void configure() {
      MapBinder<EventType, CommandCallbackHandler> binder = MapBinder.newMapBinder(binder(), EventType.class, CommandCallbackHandler.class);
      binder.addBinding(OPEN).to(OpenCallbackHandler.class);
      binder.addBinding(HB).to(HeartbeatCallbackHandler.class);
      binder.addBinding(SUBS).to(SubscribedCallbackHandler.class);
      binder.addBinding(SNAPSHOT).to(SnapshotCallbackHandler.class);
      binder.addBinding(L2U).to(RefreshCallbackHandler.class);

      bind(Service.class).to(GdaxApp.class);
    }

    @Provides
    public URI provideUri(Config config) throws Exception {
      return new URI(config.getString("app.ws.url"));
    }

    @Provides
    public MessageSender provideMessageSender(final GdaxClient client) {
      return client::send;
    }

    @Provides
    @Singleton
    public Gson provideGson(Map<EventType, CommandCallbackHandler> handlers) {
      GsonBuilder builder = new GsonBuilder();
      builder.registerTypeAdapter(AbstractEvent.class, new EventAdapter(handlers));
      return builder.create();
    }

    @Provides
    @Singleton
    public MessageHandler provideMessageHandler(Gson gson, MessageSender sender, OrderBookKeeper keeper, GdaxKafkaProducer producer) {
      return new MessageHandlerImpl(gson, sender, keeper, producer);
    }

    @Provides
    @Singleton
    public Map<String, PriceBasedOrderBook> provideOrderBooks(Config config) {
      List<String> symbols = config.getStringList("symbols");
      return symbols.stream().collect(Collectors.toMap(sym->sym, PriceBasedOrderBook::new));
    }

    @Provides
    @Singleton
    public Producer<String, String> provideKafakaProducer(Config config) {
      Config kafka = config.getConfig("kafka");
      Properties props = new Properties();
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getString(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
      props.put(ProducerConfig.CLIENT_ID_CONFIG, kafka.getString(ProducerConfig.CLIENT_ID_CONFIG));
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
      return new KafkaProducer<>(props);
    }
  }

  public static void main(String[] args) throws Exception {
    InjectorBuilder.fromModules(new DumpAndShutdownModule(), new CommonModule(), new GdaxModule())
        .createInjector()
        .getInstance(Service.class)
        .startAsync().awaitTerminated();
  }
}