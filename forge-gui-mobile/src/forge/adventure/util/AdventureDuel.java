package forge.adventure.util;

import forge.Forge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.world.WorldSave;
import forge.adventure.world.WorldSaveHeader;
import forge.deck.Deck;
import forge.game.GameView;
import forge.game.player.PlayerView;
import forge.gui.FThreads;
import forge.screens.online.OnlineMenu;
import forge.toolbox.FOptionPane;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Adventure Duel: online multiplayer that is started from inside Adventure mode.
 * <p>
 * Flow: Adventure start menu "Duel" button -> the game is auto-saved -> the player's Adventure
 * decks are copied -> the app switches to the classic online lobby, where only those decks can be
 * picked. When a match ends, the winner earns {@link #WIN_COINS} and the loser {@link #LOSE_COINS}.
 * Coins are written straight into the auto-save the duel started from and into the Adventure
 * game still held in memory, so both "Resume" and "Continue" see them. Leaving the lobby returns
 * to Adventure.
 * <p>
 * Each player's own phone credits its own save, so both players must start the lobby from
 * Adventure -> Duel. The host refuses to start a match otherwise.
 */
public final class AdventureDuel {
    /** Coins for winning a duel match. */
    public static final int WIN_COINS = 500;
    /** Coins for losing (or drawing) a duel match. */
    public static final int LOSE_COINS = 100;
    /** Every deck sent to the lobby is labelled with this, so the host can check both players use Adventure decks. */
    public static final String DECK_PREFIX = "Adventure: ";

    private static boolean active;
    private static String saveFilePath;
    private static String characterName = "";
    private static final List<Deck> decks = new ArrayList<>();
    private static int chosenIndex;

    // per-match bookkeeping, reset after every finished match
    private static final Map<String, Integer> gameWins = new HashMap<>();
    private static final Set<Integer> countedGames = new HashSet<>();

    private AdventureDuel() {
    }

    public static boolean isActive() {
        return active;
    }

    public static String getCharacterName() {
        return characterName;
    }

    /**
     * Called from the Adventure start menu. Saves the game, copies the decks and opens the online lobby.
     * @return an error message to show, or null if the duel lobby is opening
     */
    public static String start() {
        AdventurePlayer player = Current.player();
        if (player == null || WorldSave.getCurrentSave().getWorld().getData() == null) {
            return "Load an Adventure game first.";
        }
        List<Deck> copies = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < player.getDeckCount(); i++) {
            Deck deck = player.getDeck(i);
            if (deck == null || deck.getMain().isEmpty() || player.isEmptyDeck(i)) {
                continue;
            }
            if (i == player.getSelectedDeckIndex()) {
                selected = copies.size();
            }
            copies.add(new Deck(deck, deck.getName()));
        }
        if (copies.isEmpty()) {
            return "You need at least one Adventure deck with cards in it.";
        }
        if (!WorldSave.getCurrentSave().autoSave()) {
            return "Could not save your Adventure game, so the duel was not started.";
        }

        saveFilePath = new File(WorldSave.getSaveFile(WorldSave.AUTO_SAVE_SLOT)).getAbsolutePath();
        characterName = player.getName();
        decks.clear();
        decks.addAll(copies);
        chosenIndex = selected;
        resetMatch();
        active = true;

        Forge.switchToClassic(() -> OnlineMenu.OnlineScreen.Lobby.open());
        return null;
    }

    /** Ends the duel session and goes back to Adventure. */
    public static void returnToAdventure() {
        end();
        Forge.isMobileAdventureMode = true; //set early for the transition logo, like the classic Adventure button
        Forge.switchToAdventure();
    }

    /** Ends the duel session without switching screens. */
    public static void end() {
        active = false;
        decks.clear();
        resetMatch();
    }

    public static List<String> getDeckChoices() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < decks.size(); i++) {
            names.add((i + 1) + ". " + decks.get(i).getName() + " (" + decks.get(i).getMain().countAll() + " cards)");
        }
        return names;
    }

    public static void chooseDeck(String choice) {
        int i = getDeckChoices().indexOf(choice);
        if (i >= 0) {
            chosenIndex = i;
        }
    }

    public static Deck getChosenDeck() {
        if (!active || decks.isEmpty()) {
            return null;
        }
        return decks.get(Math.min(chosenIndex, decks.size() - 1));
    }

    /** Text for the lobby's deck button. */
    public static String getDeckLabel() {
        Deck deck = getChosenDeck();
        return deck == null ? "Adventure deck" : DECK_PREFIX + deck.getName();
    }

    private static void resetMatch() {
        gameWins.clear();
        countedGames.clear();
    }

    /**
     * Called by the match screen whenever a game ends. Counts game wins and, once the match is
     * over, pays out coins to this player's Adventure save.
     */
    public static void onGameFinished(GameView game, Collection<PlayerView> localPlayers) {
        if (!active || game == null || !game.isGameOver()) {
            return;
        }
        if (!countedGames.add(game.getId())) {
            return; // already counted this game
        }
        String winner = game.getWinningPlayerName();
        if (winner != null) {
            gameWins.merge(winner, 1, Integer::sum);
        }
        if (!game.isMatchOver()) {
            return;
        }

        String matchWinner = null;
        int best = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> e : gameWins.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                matchWinner = e.getKey();
                tie = false;
            } else if (e.getValue() == best) {
                tie = true;
            }
        }
        if (tie) {
            matchWinner = null;
        }
        resetMatch();

        String me = null;
        for (PlayerView pv : localPlayers == null ? Collections.<PlayerView>emptyList() : localPlayers) {
            me = pv.getName();
            break;
        }
        final boolean won = me != null && me.equals(matchWinner);
        final int coins = won ? WIN_COINS : LOSE_COINS;

        FThreads.invokeInBackgroundThread(() -> {
            final boolean saved = creditSaveFile(coins);
            FThreads.invokeInEdtLater(() -> {
                creditGameInMemory(coins);
                String msg = (won ? "You won the duel!" : "Good game!")
                        + "\n" + characterName + " earned " + coins + " coins.";
                if (!saved) {
                    msg += "\n\nThe coins could not be written to your Adventure save file. "
                            + "Use Resume (not Continue) when you go back to Adventure, then save.";
                }
                FOptionPane.showMessageDialog(msg, "Adventure Duel");
            });
        });
    }

    /** Adds coins to the Adventure game still in memory, so Resume sees them too. */
    private static void creditGameInMemory(int coins) {
        try {
            AdventurePlayer player = Current.player();
            if (player != null && characterName.equals(player.getName())) {
                player.giveGold(coins);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Adds coins to the auto-save file the duel started from. */
    private static synchronized boolean creditSaveFile(int coins) {
        if (saveFilePath == null) {
            return false;
        }
        File file = new File(saveFilePath);
        if (!file.exists()) {
            return false;
        }
        try {
            WorldSaveHeader header;
            SaveFileData mainData;
            try (FileInputStream fis = new FileInputStream(file);
                 InflaterInputStream inf = new InflaterInputStream(fis);
                 ObjectInputStream ois = new ObjectInputStream(inf)) {
                header = (WorldSaveHeader) ois.readObject();
                mainData = (SaveFileData) ois.readObject();
            }
            SaveFileData playerData = mainData.readSubData("player");
            if (playerData == null || !characterName.equals(playerData.readString("name"))) {
                return false; // a different character was saved over this slot
            }
            playerData.store("gold", playerData.readInt("gold") + coins);
            mainData.store("player", playerData);

            File tmp = new File(saveFilePath + ".duel.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp);
                 DeflaterOutputStream def = new DeflaterOutputStream(fos);
                 ObjectOutputStream oos = new ObjectOutputStream(def)) {
                oos.writeObject(header);
                oos.writeObject(mainData);
            }
            header.dispose(); // frees the preview image
            Files.copy(file.toPath(), new File(saveFilePath + ".duelbak").toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
