package com.stirante.runechanger.sourcestore.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stirante.runechanger.DebugConsts;
import com.stirante.runechanger.model.app.CounterData;
import com.stirante.runechanger.model.client.*;
import com.stirante.runechanger.sourcestore.CounterSource;
import com.stirante.runechanger.sourcestore.RuneSource;
import com.stirante.runechanger.util.FxUtils;
import com.stirante.runechanger.util.StringUtils;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class UGGSource implements RuneSource, CounterSource {
    private static final Logger log = LoggerFactory.getLogger(UGGSource.class);

    private static final String UGG_VERSION = "1.1";
    private static final String OVERVIEW_VERSION = "1.3.0";
    private static final String COUNTERS_VERSION = "1.3.0";
    private static final String OVERVIEW_PATH = "overview";
    private static final String COUNTERS_PATH = "counters";
    private static final String BASE_URL =
            "https://stats2.u.gg/lol/" + UGG_VERSION + "/%path%/%patch%/%queue%/%championId%/%version%.json";
    private static final String VERSIONS_URL = "https://ddragon.leagueoflegends.com/api/versions.json";
    private static final String PUBLIC_URL = "https://u.gg/lol/champions/%championName%/build";
    private static final Tier DEFAULT_TIER = Tier.PLATINUM_PLUS;
    private static final Server DEFAULT_SERVER = Server.WORLD;

    private static String patchString = null;

    public static void main(String[] args) throws IOException {
        DebugConsts.enableDebugMode();
        Champion.init();
//        ObservableList<RunePage> pages = FXCollections.observableArrayList();
//        new UGGSource().getRunesForChampion(Champion.getByName("blitzcrank"), pages);
//        for (RunePage page : pages) {
//            System.out.println(page);
//        }
        System.out.println(new UGGSource().getCounterData(Champion.getByName("Janna")));
    }

    public RunePage getForChampion(Champion champion, Tier tier, Position position, Server server, QueueType queue) throws IOException {
        if (queue == QueueType.ARAM) {
            position = Position.NONE;
            tier = Tier.OVERALL;
        }
        return getForChampion(getRootObject(champion, OVERVIEW_PATH, OVERVIEW_VERSION, queue), champion, tier, position, server);
    }

    private JsonObject getRootObject(Champion champion, String path, String version, QueueType queue) throws IOException {
        if (patchString == null) {
            initPatchString();
        }
        URL url = new URL(BASE_URL
                .replace("%path%", path)
                .replace("%patch%", patchString)
                .replace("%queue%", queue.getInternalName())
                .replace("%championId%", Integer.toString(champion.getId()))
                .replace("%version%", version));
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        InputStream in = urlConnection.getInputStream();
        JsonObject root = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
        in.close();
        return root;
    }

    private RunePage getForChampion(JsonObject root, Champion champion, Tier tier, Position position, Server server) {
        if (!root.has(server.toString()) ||
                !root.getAsJsonObject(server.toString()).has(tier.toString()) ||
                !root.getAsJsonObject(server.toString()).getAsJsonObject(tier.toString()).has(position.toString())) {
            return null;
        }
        JsonArray arr = root
                .getAsJsonObject(server.toString())
                .getAsJsonObject(tier.toString())
                .getAsJsonArray(position.toString())
                .get(0).getAsJsonArray();
        int games = arr.get(OverviewElement.RUNE_PAGES.getKey()).getAsJsonArray().get(Page.GAMES.getKey()).getAsInt();
        log.debug("Games count for " + champion.getName() + " on " + position.name() + ": " + games);
        RunePage page = new RunePage();
        page.setSourceName(getSourceName());
        page.setMainStyle(Style.getById(arr.get(OverviewElement.RUNE_PAGES.getKey())
                .getAsJsonArray()
                .get(Page.MAIN_STYLE.getKey())
                .getAsInt()));
        page.setSubStyle(Style.getById(arr.get(OverviewElement.RUNE_PAGES.getKey())
                .getAsJsonArray()
                .get(Page.SUB_STYLE.getKey())
                .getAsInt()));
        for (JsonElement element : arr.get(OverviewElement.RUNE_PAGES.getKey())
                .getAsJsonArray()
                .get(Page.RUNES.getKey())
                .getAsJsonArray()) {
            page.getRunes().add(Rune.getById(element.getAsInt()));
        }

        for (JsonElement element : arr.get(OverviewElement.MODIFIERS.getKey())
                .getAsJsonArray()
                .get(2)
                .getAsJsonArray()) {
            page.getModifiers().add(Modifier.getById(element.getAsInt()));
        }
        page.setName(StringUtils.fromEnumName(position.name()));
        page.setChampion(champion);
        page.setSource(PUBLIC_URL.replace("%championName%", champion.getName().toLowerCase()));
        page.fixOrder();
        if (!page.verify()) {
            return null;
        }
        return page;
    }

    @Override
    public String getSourceName() {
        return "u.gg";
    }

    @Override
    public void getRunesForChampion(Champion champion, GameMode mode, ObservableList<RunePage> pages) {
        try {
            JsonObject root = getRootObject(champion, OVERVIEW_PATH, OVERVIEW_VERSION,
                    mode == GameMode.ARAM ? QueueType.ARAM : QueueType.RANKED_SOLO);
            if (mode == GameMode.ARAM) {
                RunePage page = getForChampion(root, champion, Tier.OVERALL, Position.NONE, DEFAULT_SERVER);
                if (page == null) {
                    return;
                }
                page.setName("ARAM");
                FxUtils.doOnFxThread(() -> pages.add(page));
            }
            else {
                for (Position position : Position.values()) {
                    RunePage page = getForChampion(root, champion, DEFAULT_TIER, position, DEFAULT_SERVER);
                    if (page == null) {
                        continue;
                    }
                    FxUtils.doOnFxThread(() -> pages.add(page));
                }
            }
        } catch (IOException e) {
            log.error("Exception occurred while getting UGG rune page for champion " + champion.getName(), e);
        }
    }

    private void initPatchString() {
        try {
            URL url = new URL(VERSIONS_URL);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            InputStream in = urlConnection.getInputStream();
            JsonArray strings = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonArray();
            int i = 0;
            while (patchString == null) {
                String[] patch = strings.get(i).getAsString().split("\\.");
                patchString = patch[0] + "_" + patch[1];
                try {
                    getRootObject(Champion.getByName("annie"), OVERVIEW_PATH, OVERVIEW_VERSION, QueueType.RANKED_SOLO);
                } catch (IOException e) {
                    i++;
                    patchString = null;
                }
            }
            in.close();
        } catch (IOException e) {
            log.error("Exception occurred while initializing patch string", e);
        }
    }

    public CounterData getCounterData(Champion champion, Tier tier, Position position, Server server) throws IOException {
        if (patchString == null) {
            initPatchString();
        }
        JsonObject root = getRootObject(champion, COUNTERS_PATH, COUNTERS_VERSION, QueueType.RANKED_SOLO);
        if (!root.has(server.toString()) ||
                !root.getAsJsonObject(server.toString()).has(tier.toString()) ||
                (position != null && !root.getAsJsonObject(server.toString())
                        .getAsJsonObject(tier.toString())
                        .has(position.toString()))) {
            return null;
        }
        String pos = position == null ? root
                .getAsJsonObject(server.toString())
                .getAsJsonObject(tier.toString()).keySet().stream().findFirst().orElse(null) : position.toString();
        if (pos == null) {
            return new CounterData();
        }
        JsonArray arr = root
                .getAsJsonObject(server.toString())
                .getAsJsonObject(tier.toString())
                .getAsJsonArray(pos)
                .get(1).getAsJsonArray();
        Set<CounterChampion> strongAgainst =
                counterArrayToObjects(arr.get(CounterElement.STRONG_AGAINST_SAME_POSITION.getKey()).getAsJsonArray());
        strongAgainst.addAll(
                counterArrayToObjects(arr.get(CounterElement.STRONG_AGAINST_DIFFERENT_POSITION.getKey())
                        .getAsJsonArray()));
        Set<CounterChampion> weakAgainst =
                counterArrayToObjects(arr.get(CounterElement.WEAK_AGAINST_SAME_POSITION.getKey()).getAsJsonArray());
        weakAgainst.addAll(
                counterArrayToObjects(arr.get(CounterElement.WEAK_AGAINST_DIFFERENT_POSITION.getKey())
                        .getAsJsonArray()));

        Set<CounterChampion> weakWith =
                counterArrayToObjects(arr.get(CounterElement.WEAK_WITH.getKey()).getAsJsonArray());

        Set<CounterChampion> strongWith =
                counterArrayToObjects(arr.get(CounterElement.STRONG_WITH.getKey()).getAsJsonArray());

        CounterData result = new CounterData();
        result.setStrongAgainst(strongAgainst.stream()
                .sorted()
                .limit(5)
                .map(CounterChampion::getChampion)
                .collect(Collectors.toList()));
        result.setWeakAgainst(weakAgainst.stream()
                .sorted(Comparator.reverseOrder())
                .limit(5)
                .map(CounterChampion::getChampion)
                .collect(Collectors.toList()));
        result.setWeakWith(weakWith.stream()
                .sorted(Comparator.reverseOrder())
                .limit(5)
                .map(CounterChampion::getChampion)
                .collect(Collectors.toList()));
        result.setStrongWith(strongWith.stream()
                .sorted()
                .limit(5)
                .map(CounterChampion::getChampion)
                .collect(Collectors.toList()));

        return result;
    }

    private Set<CounterChampion> counterArrayToObjects(JsonArray arr) {
        Set<CounterChampion> result = new HashSet<>();
        for (JsonElement element : arr) {
            JsonArray jsonArray = element.getAsJsonArray();
            result.add(new CounterChampion(
                    Champion.getById(jsonArray.get(Counter.CHAMPION.getKey()).getAsInt()),
                    Position.getByKey(jsonArray.get(Counter.POSITION.getKey()).getAsInt()),
                    jsonArray.get(Counter.WON.getKey()).getAsInt(),
                    jsonArray.get(Counter.GAMES.getKey()).getAsInt()
            ));
        }
        return result;
    }

    @Override
    public CounterData getCounterData(Champion champion) {
        try {
            return getCounterData(champion, DEFAULT_TIER, null, DEFAULT_SERVER);
        } catch (IOException e) {
            e.printStackTrace();
            return new CounterData();
        }
    }

    @Override
    public boolean hasGameModeSpecific() {
        return true;
    }

    private static class CounterChampion implements Comparable<CounterChampion> {
        private Champion champion;
        private Position position;
        private int wins;
        private int games;

        public CounterChampion(Champion champion, Position position, int wins, int games) {
            this.champion = champion;
            this.position = position;
            this.wins = wins;
            this.games = games;
        }

        public Champion getChampion() {
            return champion;
        }

        public Position getPosition() {
            return position;
        }

        public int getWins() {
            return wins;
        }

        public int getGames() {
            return games;
        }

        public double getWinRate() {
            return (double) wins / (double) games;
        }

        @Override
        public int compareTo(UGGSource.CounterChampion counterChampion) {
            return Double.compare(counterChampion.getWinRate(), getWinRate());
        }
    }

    private enum QueueType {
        RANKED_SOLO("ranked_solo_5x5"),
        ARAM("normal_aram"),
        RANKED_FLEX("ranked_flex_sr"),
        NORMAL_BLIND("normal_blind_5x5"),
        NORMAL_DRAFT("normal_draft_5x5");

        private final String internalName;

        QueueType(String internalName) {
            this.internalName = internalName;
        }

        public String getInternalName() {
            return internalName;
        }

        @Override
        public String toString() {
            return internalName;
        }
    }

    // All those enums are keys to a json file

    private enum Server {
        NA(1),
        EUW(2),
        KR(3),
        EUNE(4),
        BR(5),
        LAS(6),
        LAN(7),
        OCE(8),
        RU(9),
        TR(10),
        JP(11),
        WORLD(12);

        private final int key;

        Server(int key) {
            this.key = key;
        }

        public int getKey() {
            return key;
        }

        @Override
        public String toString() {
            return Integer.toString(key);
        }
    }

    private enum Tier {
        CHALLENGER(1),
        MASTER(2),
        DIAMOND(3),
        PLATINUM(4),
        GOLD(5),
        SILVER(6),
        BRONZE(7),
        OVERALL(8),
        PLATINUM_PLUS(10),
        DIAMOND_PLUS(11);

        private final int key;

        Tier(int key) {
            this.key = key;
        }

        public int getKey() {
            return key;
        }

        @Override
        public String toString() {
            return Integer.toString(key);
        }
    }

    private enum Position {
        JUNGLE(1),
        SUPPORT(2),
        ADC(3),
        TOP(4),
        MID(5),
        NONE(6);

        private final int key;

        Position(int key) {
            this.key = key;
        }

        public int getKey() {
            return key;
        }

        @Override
        public String toString() {
            return Integer.toString(key);
        }

        public static Position getByKey(int key) {
            for (Position value : values()) {
                if (value.key == key) {
                    return value;
                }
            }
            return null;
        }

    }

    private enum OverviewElement {
        RUNE_PAGES(0),
        SUMMONER_SPELLS(1),
        STARTING_BUILD(2),
        CORE_BUILD(3),
        SKILL_ORDER(4),
        ITEM_OPTIONS(4),
        DATA_SAMPLE(6),
        MODIFIERS(8);

        private final int key;

        OverviewElement(int key) {
            this.key = key;
        }

        public int getKey() {
            return key;
        }

        @Override
        public String toString() {
            return Integer.toString(key);
        }
    }

    private enum CounterElement {
        STRONG_AGAINST_SAME_POSITION(0),
        WEAK_AGAINST_SAME_POSITION(1),
        STRONG_AGAINST_DIFFERENT_POSITION(2),
        WEAK_AGAINST_DIFFERENT_POSITION(3),
        STRONG_WITH(4),
        WEAK_WITH(5);

        private final int key;

        CounterElement(int key) {
            this.key = key;
        }

        public int getKey() {
            return key;
        }

        @Override
        public String toString() {
            return Integer.toString(key);
        }
    }

    private enum Counter {
        CHAMPION(0),
        POSITION(1),
        WON(2),
        GAMES(3);

        private final int key;

        Counter(int key) {
            this.key = key;
        }

        public int getKey() {
            return key;
        }

        @Override
        public String toString() {
            return Integer.toString(key);
        }
    }

    private enum Page {
        GAMES(0),
        WON(1),
        MAIN_STYLE(2),
        SUB_STYLE(3),
        RUNES(4);

        private final int key;

        Page(int key) {
            this.key = key;
        }

        public int getKey() {
            return key;
        }

        @Override
        public String toString() {
            return Integer.toString(key);
        }
    }
}