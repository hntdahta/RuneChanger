package com.stirante.runechanger;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import com.stirante.lolclient.ClientApi;
import com.stirante.lolclient.ClientConnectionListener;
import com.stirante.lolclient.ClientWebSocket;
import com.stirante.lolclient.libs.org.java_websocket.exceptions.WebsocketNotConnectedException;
import com.stirante.runechanger.client.ChampionSelection;
import com.stirante.runechanger.client.Loot;
import com.stirante.runechanger.client.Runes;
import com.stirante.runechanger.gui.Constants;
import com.stirante.runechanger.gui.GuiHandler;
import com.stirante.runechanger.gui.SceneType;
import com.stirante.runechanger.gui.Settings;
import com.stirante.runechanger.model.client.Champion;
import com.stirante.runechanger.model.client.RunePage;
import com.stirante.runechanger.model.github.Version;
import com.stirante.runechanger.runestore.RuneStore;
import com.stirante.runechanger.util.*;
import generated.*;
import javafx.beans.InvalidationListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.update4j.LaunchContext;
import org.update4j.service.Launcher;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;

public class RuneChanger implements Launcher {
    private static final Logger log = LoggerFactory.getLogger(RuneChanger.class);
    private static RuneChanger instance;
    public String[] programArguments;
    private ClientApi api;
    private GuiHandler gui;
    private List<RunePage> runes;
    private ChampionSelection champSelectModule;
    private Runes runesModule;
    private Loot lootModule;
    private ClientWebSocket socket;
    private boolean donateDontAsk = false;

    public static void main(String[] args) {
        checkAndCreateLockfile();
        changeWorkingDir();
        cleanupLogs();
        setDefaultUncaughtExceptionHandler();
        // This flag is only meant for development. It disables whole client communication
        if (!Arrays.asList(args).contains("-osx")) {
            checkOperatingSystem();
        }
        try {
            Champion.init();
        } catch (IOException e) {
            log.error("Exception occurred while initializing champions", e);
            JOptionPane.showMessageDialog(null, LangHelper.getLang().getString("init_data_error"), Constants.APP_NAME,
                    JOptionPane.ERROR_MESSAGE);
            System.exit(0);
        }
        SimplePreferences.load();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        if (Arrays.asList(args).contains("-nologs")) {
            logger.getAppender("FILE").stop();
            new File(((FileAppender<ILoggingEvent>) logger.getAppender("FILE")).getFile()).delete();
            logger.detachAppender("FILE");
        }
        if (Arrays.asList(args).contains("-debug-mode")) {
            DebugConsts.enableDebugMode();
            log.debug("Runechanger started with debug mode enabled");
        }
        try {
            AutoStartUtils.checkAutoStartPath();
        } catch (Exception e) {
            log.error("Exception occurred while checking autostart path", e);
        }
        if (!SimplePreferences.getBooleanValue(SimplePreferences.FlagKeys.CREATED_SHORTCUTS, false)) {
            try {
                ShortcutUtils.createDesktopShortcut();
                ShortcutUtils.createMenuShortcuts();
                SimplePreferences.putBooleanValue(SimplePreferences.FlagKeys.CREATED_SHORTCUTS, true);
            } catch (Exception e) {
                log.error("Exception occurred while creating shortcuts", e);
            }
        }
        instance = new RuneChanger();
        instance.programArguments = args;
        instance.init();
    }

    public static RuneChanger getInstance() {
        return instance;
    }

    private static void changeWorkingDir() {
        try {
            //find path to the current jar
            File currentJar = new File(PathUtils.getJarLocation());
            //If this is true then the jar was most likely started by autostart
            if (!new File(System.getProperty("user.dir")).getAbsolutePath()
                    .equals(currentJar.getParentFile().getAbsolutePath())) {
                //if it's not a jar (probably running from IDE)
                if (!currentJar.getName().endsWith(".jar")) {
                    return;
                }

                Runtime.getRuntime().exec(AutoStartUtils.getStartCommand(), null, currentJar.getParentFile());
                log.warn("Runechanger was started from a unusual jvm location most likely due to autostart. " +
                        "Restarting client now to fix pathing errors..");
                System.exit(0);
            }
        } catch (Exception e) {
            log.error("Exception occurred while changing current directory", e);
        }
    }

    private static void checkOperatingSystem() {
        if (!System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            log.error("User is not on a windows machine");
            JOptionPane.showMessageDialog(null, LangHelper.getLang().getString("windows_only"),
                    Constants.APP_NAME,
                    JOptionPane.WARNING_MESSAGE);
            System.exit(0);
        }
    }

    private static void checkAndCreateLockfile() {
        String userHome = PathUtils.getWorkingDirectory();
        File file = new File(userHome, "runechanger.lock");
        try {
            FileChannel fc = FileChannel.open(file.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            FileLock lock = fc.tryLock();
            if (lock == null) {
                log.error("Another instance of runechanger is open. Exiting program now.");
                System.exit(1);
            }
        } catch (IOException e) {
            log.error("Error creating lockfile" + e);
            System.exit(1);
        }
    }

    private static void setDefaultUncaughtExceptionHandler() {
        try {
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.error(
                    "Uncaught Exception detected in thread " + t, e));
        } catch (SecurityException e) {
            log.error("Could not set the Default Uncaught Exception Handler", e);
        }
    }

    private static void cleanupLogs() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -30);
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (ch.qos.logback.classic.Logger logger : context.getLoggerList()) {
            for (Iterator<Appender<ILoggingEvent>> index = logger.iteratorForAppenders(); index.hasNext(); ) {
                Appender<ILoggingEvent> appender = index.next();
                if (appender instanceof FileAppender) {
                    FileAppender<ILoggingEvent> fa = (FileAppender<ILoggingEvent>) appender;
                    File logFile = new File(PathUtils.getWorkingDirectory(), fa.getFile());
                    //Remove logs older than 30 days
                    if (logFile.getParentFile().exists()) {
                        for (File file : Objects.requireNonNull(logFile.getParentFile().listFiles())) {
                            if (new Date(file.lastModified()).before(c.getTime())) {
                                if (!file.delete()) {
                                    log.error("Failed to remove logs older then 30 days!");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void initModules() {
        champSelectModule = new ChampionSelection(api);
        runesModule = new Runes(api);
        lootModule = new Loot(api);
    }

    private void resetModules() {
        champSelectModule.reset();
        runesModule.reset();
        lootModule.reset();
    }

    private void init() {
        log.info("Starting RuneChanger version " + Constants.VERSION_STRING + " (" + Version.INSTANCE.branch + "@" +
                Version.INSTANCE.commitIdAbbrev + " built at " +
                SimpleDateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Version.INSTANCE.buildTime) + ")");
        if (!Arrays.asList(programArguments).contains("-osx")) {
            ClientApi.setDisableEndpointWarnings(true);
            try {
                String clientPath = SimplePreferences.getStringValue(SimplePreferences.InternalKeys.CLIENT_PATH, null);
                if (clientPath != null && !new File(clientPath).exists()) {
                    clientPath = null;
                }
                api = new ClientApi(clientPath);
            } catch (IllegalStateException e) {
                log.error("Exception occurred while creating client api", e);
                JOptionPane.showMessageDialog(null, LangHelper.getLang()
                        .getString("client_error"), Constants.APP_NAME, JOptionPane.ERROR_MESSAGE);
                System.exit(0);
            }
        }
        initModules();
        Settings.initialize();
        gui = new GuiHandler(this);
        if (!Arrays.asList(programArguments).contains("-osx")) {
            api.addClientConnectionListener(new ClientConnectionListener() {
                @Override
                public void onClientConnected() {
                    if (!api.getClientPath()
                            .equalsIgnoreCase(SimplePreferences.getStringValue(SimplePreferences.InternalKeys.CLIENT_PATH, null))) {
                        log.info("Saving client path to \"" + api.getClientPath() + "\"");
                        SimplePreferences.putStringValue(SimplePreferences.InternalKeys.CLIENT_PATH, api.getClientPath());
                    }
                    gui.setSceneType(SceneType.NONE);
                    gui.openWindow();
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        if (DebugConsts.MOCK_SESSION) {
                            gui.showWarningMessage("Mocking session");
                            LolSummonerSummoner currentSummoner = champSelectModule.getCurrentSummoner();
                            LolChampSelectChampSelectSession session = new LolChampSelectChampSelectSession();
                            session.myTeam = new ArrayList<>();
                            LolChampSelectChampSelectPlayerSelection e = new LolChampSelectChampSelectPlayerSelection();
                            e.championId = 223;//Tahm kench
                            e.championPickIntent = 223;
                            e.summonerId = currentSummoner.summonerId;
                            session.myTeam.add(e);
                            handleSession(session);
                        }
                    }).start();
                    // Check if session is active after starting RuneChanger, since we might not get event right away
                    try {
                        LolChampSelectChampSelectSession session =
                                api.executeGet("/lol-champ-select/v1/session", LolChampSelectChampSelectSession.class);
                        if (session != null) {
                            handleSession(session);
                        }
                    } catch (Exception ignored) {
                    }
                    // Auto sync rune pages to RuneChanger
                    if (SimplePreferences.getBooleanValue(SimplePreferences.SettingsKeys.AUTO_SYNC, false)) {
                        runesModule.syncRunePages();
                    }
                    //sometimes, the api is connected too quickly and there is WebsocketNotConnectedException
                    //That's why I added this little piece of code, which will retry opening socket every second
                    new Thread(() -> {
                        while (true) {
                            try {
                                openSocket();
                                return;
                            } catch (Exception e) {
                                if (!api.isConnected()) {
                                    return;
                                }
                                if (e instanceof WebsocketNotConnectedException || e instanceof ConnectException) {
                                    log.error("Connection failed, retrying in a second..");
                                    //try again in a second
                                    try {
                                        Thread.sleep(1000);
                                    } catch (InterruptedException ignored) {
                                    }
                                    continue;
                                }
                                else {
                                    log.error("Exception occurred while opening socket", e);
                                }
                            }
                            return;
                        }
                    }).start();
                }

                @Override
                public void onClientDisconnected() {
                    resetModules();
                    gui.setSceneType(SceneType.NONE);
                    if (gui.isWindowOpen()) {
                        gui.closeWindow();
                    }
                    gui.showInfoMessage(LangHelper.getLang().getString("client_disconnected"));
                    Settings.setClientConnected(false);
                }
            });
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (socket != null) {
                socket.close();
            }
        }));
        FxUtils.doOnFxThread(AutoUpdater::checkUpdate);
        donateDontAsk = SimplePreferences.getBooleanValue(SimplePreferences.InternalKeys.DONATE_DONT_ASK, false);
        if (!SimplePreferences.getBooleanValue(SimplePreferences.InternalKeys.ASKED_ANALYTICS, false)) {
            FxUtils.doOnFxThread(() -> {
                boolean analytics = Settings.openYesNoDialog(
                        LangHelper.getLang().getString("analytics_dialog_title"),
                        LangHelper.getLang().getString("analytics_dialog_message")
                );
                SimplePreferences.putBooleanValue(SimplePreferences.InternalKeys.ASKED_ANALYTICS, true);
                SimplePreferences.putBooleanValue(SimplePreferences.SettingsKeys.ANALYTICS, analytics);
            });
        }
    }

    private void onChampionChanged(Champion champion) {
        ObservableList<RunePage> pages = FXCollections.observableArrayList();
        gui.setRunes(pages, (page) -> new Thread(() -> runesModule.setCurrentRunePage(page)).start());
        pages.addListener((InvalidationListener) observable -> gui.setRunes(pages));
        if (champion != null) {
            log.info("Downloading runes for champion: " + champion.getName());
            RuneStore.getRunes(champion, pages);
        }
        else {
            log.info("Showing local runes");
            RuneStore.getLocalRunes(pages);
        }
    }

    private void handleSession(LolChampSelectChampSelectSession session) {
        boolean isFirstEvent = false;
        Champion oldChampion = champSelectModule.getSelectedChampion();
        champSelectModule.onSession(session);
        if (gui.getSceneType() == SceneType.NONE) {
            gui.setSuggestedChampions(champSelectModule.getLastChampions(), champSelectModule.getBannedChampions(),
                    champSelectModule::selectChampion);
            isFirstEvent = true;
        }
        gui.setSceneType(SceneType.CHAMPION_SELECT);
        if (champSelectModule.getSelectedChampion() != oldChampion || isFirstEvent) {
            onChampionChanged(champSelectModule.getSelectedChampion());
        }
    }

    private void showDonateDialog() {
        if (donateDontAsk) {
            return;
        }
        donateDontAsk = true;
        ButtonType donate = new ButtonType(LangHelper.getLang().getString("donate_button"));
        ButtonType later = new ButtonType(LangHelper.getLang().getString("later_button"));
        ButtonType never = new ButtonType(LangHelper.getLang().getString("never_ask_again_button"));
        ButtonType result = Settings.openDialog(
                LangHelper.getLang().getString("donate_dialog_title"),
                LangHelper.getLang().getString("donate_dialog_message"),
                donate,
                later,
                never
        );
        if (result == donate) {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                try {
                    Desktop.getDesktop().browse(new URI("https://www.paypal.me/stirante"));
                } catch (IOException | URISyntaxException e) {
                    log.error("Exception occurred while navigating to donate page", e);
                }
            }
        }
        else if (result == never) {
            SimplePreferences.putBooleanValue(SimplePreferences.InternalKeys.DONATE_DONT_ASK, true);
        }
    }

    private void openSocket() throws Exception {
        ClientApi.setPrintResponse(true);
        socket = api.openWebSocket();
        gui.showInfoMessage(LangHelper.getLang().getString("client_connected"));
        Settings.setClientConnected(true);
        socket.setSocketListener(new ClientWebSocket.SocketListener() {
            @Override
            public void onEvent(ClientWebSocket.Event event) {
                //printing every event except voice for experimenting
                if (DebugConsts.PRINT_EVENTS && !event.getUri().toLowerCase().contains("voice")) {
                    log.info("Event: " + event);
//                    System.out.println(new Gson().toJson(event.getData()));
                }
                if (event.getUri().equalsIgnoreCase("/lol-gameflow/v1/gameflow-phase") &&
                        event.getData() == LolGameflowGameflowPhase.ENDOFGAME) {
                    String lastGrade = champSelectModule.getLastGrade();
                    log.debug("Grade: " + lastGrade);
                    if (lastGrade.startsWith("S")) {
                        showDonateDialog();
                    }
                }
                if (event.getUri().equalsIgnoreCase("/lol-chat/v1/me") &&
                        SimplePreferences.getBooleanValue(SimplePreferences.SettingsKeys.ANTI_AWAY, false)) {
                    if (((LolChatUserResource) event.getData()).availability.equalsIgnoreCase("away")) {
                        new Thread(() -> {
                            try {
                                LolChatUserResource data = new LolChatUserResource();
                                data.availability = "chat";
                                api.executePut("/lol-chat/v1/me", data);
                            } catch (IOException e) {
                                log.error("Exception occurred while setting availability", e);
                            }
                        }).start();
                    }
                }
                else if (event.getUri().equalsIgnoreCase("/lol-champ-select/v1/session")) {
                    if (event.getEventType().equalsIgnoreCase("Delete")) {
                        gui.setSceneType(SceneType.NONE);
                        champSelectModule.clearSession();
                    }
                    else {
                        handleSession((LolChampSelectChampSelectSession) event.getData());
                    }
                }
                else if (SimplePreferences.getBooleanValue(SimplePreferences.SettingsKeys.AUTO_ACCEPT, false) &&
                        event.getUri().equalsIgnoreCase("/lol-lobby/v2/lobby/matchmaking/search-state")) {
                    if (((LolLobbyLobbyMatchmakingSearchResource) event.getData()).searchState ==
                            LolLobbyLobbyMatchmakingSearchState.FOUND) {
                        try {
                            api.executePost("/lol-matchmaking/v1/ready-check/accept");
                        } catch (IOException e) {
                            log.error("Exception occurred while autoaccepting", e);
                        }
                    }
                }
                else if (event.getUri().equalsIgnoreCase("/riotclient/zoom-scale")) {
                    //Client window size changed, so we restart the overlay
                    gui.closeWindow();
                    gui.openWindow();
                }
                else if (event.getUri().equalsIgnoreCase("/lol-summoner/v1/current-summoner")) {
                    champSelectModule.resetSummoner();
                    runesModule.resetSummoner();
                    lootModule.resetSummoner();
                    Settings.setClientConnected(true);
                }
                else if (event.getUri().equalsIgnoreCase("/lol-perks/v1/pages")) {
                    runesModule.handlePageChange((LolPerksPerkPageResource[]) event.getData());
                }
            }

            @Override
            public void onClose(int i, String s) {
                socket = null;
            }
        });
    }

    public ClientApi getApi() {
        return api;
    }

    public ChampionSelection getChampionSelectionModule() {
        return champSelectModule;
    }

    public Runes getRunesModule() {
        return runesModule;
    }

    public Loot getLootModule() {
        return lootModule;
    }

    @Override
    public void run(LaunchContext context) {
        try {
            Runtime.getRuntime().exec("wscript silent.vbs open.bat");
            System.exit(0);
        } catch (IOException e) {
            log.error("Exception occurred while executing command", e);
        }
    }

    public GuiHandler getGuiHandler() {
        return gui;
    }
}
