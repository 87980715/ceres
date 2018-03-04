package com.blokaly.ceres.orderbook;

import com.blokaly.ceres.common.DecimalNumber;
import com.blokaly.ceres.data.MarketDataIncremental;
import com.blokaly.ceres.data.MarketDataSnapshot;
import com.blokaly.ceres.data.OrderInfo;
import com.blokaly.ceres.proto.OrderBookProto;
import com.google.common.collect.Maps;
import com.lmax.disruptor.EventTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.stream.Collectors;

public class PriceBasedOrderBook implements OrderBook<OrderInfo>, EventTranslator<OrderBookProto.OrderBookMessage.Builder> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PriceBasedOrderBook.class);
    private final String symbol;
    private final NavigableMap<DecimalNumber, PriceLevel> bids = Maps.newTreeMap(Comparator.<DecimalNumber>reverseOrder());
    private final NavigableMap<DecimalNumber, PriceLevel> asks = Maps.newTreeMap();
    private long lastSequence;

    public PriceBasedOrderBook(String symbol) {
        this.symbol = symbol;
        this.lastSequence = 0;
    }

    @Override
    public String getSymbol() {
        return symbol;
    }

    @Override
    public Collection<? extends Level> getBids() {
        return bids.values();
    }

    @Override
    public Collection<? extends Level> getAsks() {
        return asks.values();
    }

    @Override
    public void clear() {
        bids.clear();
        asks.clear();
        lastSequence = 0;
    }

    public boolean isInitialized() {
        return lastSequence>0;
    }

    @Override
    public void processSnapshot(MarketDataSnapshot<OrderInfo> snapshot) {
        LOGGER.debug("processing snapshot: {}", snapshot);
        clear();
        snapshot.getBids().forEach(this::processUpdate);
        snapshot.getAsks().forEach(this::processUpdate);
        lastSequence = snapshot.getSequence();
    }

    private void processUpdate(OrderInfo order) {
        NavigableMap<DecimalNumber, PriceLevel> levels = sidedLevels(order.side());
        PriceLevel level = new PriceLevel(order.getPrice(), order.getQuantity());
        levels.put(level.price, level);
    }

    private NavigableMap<DecimalNumber, PriceLevel> sidedLevels(OrderInfo.Side side) {
        if (side == null || side == OrderInfo.Side.UNKNOWN) {
            return null;
        }

        return side == OrderInfo.Side.BUY ? bids : asks;
    }

    private void processDeletion(OrderInfo order) {
        NavigableMap<DecimalNumber, PriceLevel> levels = sidedLevels(order.side());
        levels.remove(order.getPrice());

    }

    @Override
    public void processIncrementalUpdate(MarketDataIncremental<OrderInfo> incremental) {
        long sequence = incremental.getSequence();
        if (sequence < lastSequence) {
            return;
        }

        LOGGER.debug("processing market data: {}", incremental);
        switch (incremental.type()) {
            case UPDATE:
                incremental.orderInfos().forEach(this::processUpdate);
                break;
            case DONE:
                incremental.orderInfos().forEach(this::processDeletion);
                break;
            default:
                LOGGER.debug("Unknown type of market data: {}", incremental);
        }

        lastSequence = sequence;
    }

    @Override
    public void translateTo(OrderBookProto.OrderBookMessage.Builder event, long sequence) {

    }

    public List<String[]> topOfBids(int level) {
        return bids.values().stream().limit(level).map(this::wrapPriceLevel).collect(Collectors.toList());
    }

    public List<String[]> topOfAsks(int level) {
        return asks.values().stream().limit(level).map(this::wrapPriceLevel).collect(Collectors.toList());
    }

    private String[] wrapPriceLevel(PriceLevel level) {
        return new String[]{level.getPrice().toString(), level.getQuantity().toString()};
    }

    public static final class PriceLevel implements OrderBook.Level {

        private final DecimalNumber price;
        private final DecimalNumber total;


        private PriceLevel(DecimalNumber price, DecimalNumber total) {
            this.price = price;
            this.total = total;
        }

        @Override
        public DecimalNumber getPrice() {
            return price;
        }

        @Override
        public DecimalNumber getQuantity() {
            return total;
        }

        @Override
        public String toString() {
            return "[" + price.toString() + "," + total.toString() + "]";
        }

    }
}
