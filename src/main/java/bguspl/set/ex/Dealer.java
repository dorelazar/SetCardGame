package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public boolean availableForActions;

    public Queue<Player> potentialSets;

    public List<int[]> threadOrderList;

    public List<Integer> cardsToInsert;
    public static final int ONE = 1;
    public static final int ZERO = 0;
    public static final long tenMiliSec = 10;
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        potentialSets = new LinkedList<>();
        availableForActions = false;
        threadOrderList = new LinkedList<>();
        cardsToInsert = new LinkedList<>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        System.out.println("Thread " + Thread.currentThread().getName() + " starting.");
        placeCardsOnTable();
        availableForActions = false;
        for (int i = ZERO; i < env.config.players; i++) {
            Thread curr = new Thread(players[i], env.config.playerNames[i]);
            curr.start();
            try {
            Thread.currentThread().sleep(Player.miliSec);
            } catch (InterruptedException e) {
            }
        }
        availableForActions = true;
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
        System.out.println("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        while (!shouldFinish() && System.currentTimeMillis() < reshuffleTime) {
            updateTimerDisplay(false);
            sleepUntilWokenOrTimeout();
            removeCardsFromTable();
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
        for (int i = players.length - ONE; i >= ZERO; i--) { // reversed order
            try {
                players[i].terminate(); // first closing the players thread
                players[i].checkThread().interrupt();
                players[i].checkThread().join();
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, ONE).size() == ZERO;
    }

    /**
     * Checks if any cards should be removed from the table and returns them to the
     * deck.
     */
    private void removeCardsFromTable() {
        boolean realSet = false;
        boolean correlate = true; // checking that the cards on the table are the same cards that the player
                                  // placed his tokens on
        Integer[] copySlots = new Integer[env.config.featureSize];
        if (!potentialSets.isEmpty()) {
            Player p = potentialSets.remove();
            Integer[] copyCards = Arrays.copyOf(p.cards, env.config.featureSize); // current player cards
            copySlots = Arrays.copyOf(p.slots, env.config.featureSize);
            correlate = !Stream.of(copySlots).anyMatch(x -> x == null);
            int[] copyCardsTest = new int[copyCards.length];
            for(int i=ZERO;i<copyCardsTest.length;i++) {
                if(copyCards[i] != null) {
                    copyCardsTest[i] = copyCards[i];
                } else {
                    copyCardsTest[i] = Player.empty;
                }
            }
            realSet = env.util.testSet(copyCardsTest); // checking if the cards are a set
            if (correlate) { // if the chosen cards are still on the table
                if (!realSet) { // not a set
                    p.penalty();
                    synchronized (p) {
                        p.pressedQueue.clear();
                    }
                } else {
                    p.point();
                    synchronized (p) {
                        p.pressedQueue.clear();
                    }
                    availableForActions = false;
                    for (int i = ZERO; i < copySlots.length; i++) {
                        if (copySlots[i] != null) { // checking that we can remove the card from the table
                            synchronized (this) {
                                env.ui.removeTokens(copySlots[i]);
                                table.removeCard(copySlots[i]);
                            }
                        }
                        for (Player curr : players) {
                            for (int j = ZERO; j < curr.slots.length; j++) {
                                if (curr.slots[j] == copySlots[i]) {
                                    curr.slots[j] = null;
                                    curr.cards[j] = null;
                                    curr.countCards--;
                                }
                            }
                        }
                    }
                    placeCardsOnTable();
                }
            } else { // the case that one of the current players cards has changed before it was
                     // checked
                synchronized (p) {
                    p.pressedQueue.clear();
                }
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    public void placeCardsOnTable() {
        Collections.shuffle(deck); // shuffling the deck
        boolean resetNeeded = false; // will be used in order to know if we need to reset the countdown
        List<Integer> permutation = new LinkedList<>();
        for (int i = ZERO; i < env.config.tableSize; i++) {
            permutation.add(i, i);
        }
        Collections.shuffle(permutation); // determines the cards order on the table
        while (!permutation.isEmpty()) {
            int i = permutation.remove(ZERO);
            if (!deck.isEmpty()) {
                if (table.slotToCard[i] == null) {
                    cardsToInsert.add(deck.remove(ZERO));
                    cardsToInsert.add(i);
                    insertCards();
                    // table.placeCard(deck.remove(0), i); // placing them after shuffling
                    resetNeeded = true;
                }
            }
        }
        if (resetNeeded) {
            updateTimerDisplay(true);
        }
        if(env.config.hints) {
            table.hints();
            System.out.println("_______________________");
    }
        availableForActions = true;
        for (Player p : players) {
            synchronized (p) {
                p.notifyAll();
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        if (reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis) {
            try {
                synchronized (this) {
                    wait(Player.miliSec);
                }
            } catch (InterruptedException ignored) {
            }
        } else {
            try {
                synchronized (this) {
                    wait(Player.sec);
                }
            } catch (InterruptedException ignored) {
            }
        }

    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        }
        boolean warn = false;
        if (reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis) {
            warn = true;
        }
        env.ui.setCountdown(
                Math.max(0, reshuffleTime - System.currentTimeMillis()),
                warn);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        if (!shouldFinish()) {
            availableForActions = false;
            List<Integer> permutation = new LinkedList<>();
            for (int i = ZERO; i < env.config.tableSize; i++) {
                permutation.add(i, i);
            }
            Collections.shuffle(permutation);
            while (!permutation.isEmpty()) {
                int i = permutation.remove(ZERO); // removing the cards from the table
                if (table.slotToCard[i] != null) {
                    deck.add(table.slotToCard[i]);
                    env.ui.removeTokens(i);
                    table.removeCard(i);
                }
            }
            for (Player curr : players) {
                synchronized (curr) {
                    curr.clearCache(); // removing the tested cards
                }
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int count = ZERO;
        int max = Player.empty;
        for (Player p : players) {
            max = Math.max(max, p.score());
        }
        for (Player p : players) {
            if (p.score() == max) {
                count++;
            }
        }
        int[] arr = new int[count];
        int i = ZERO;
        for (Player p : players) { // in case of more than 1 winner
            if (p.score() == max) {
                arr[i] = p.id;
                i++;
            }
        }
        env.ui.announceWinner(arr);
    }
/**
     * Adding a player with potential set to the potentialSets queue.
     * 
     * @pre - que does not contain the player in input
     * 
     * @param player - the player that needs to be added.
     *
     * @post - the queue contains the needed player.
     */

    public void addPotential(Player p) {
        synchronized (potentialSets) {
            potentialSets.add(p);
        }
        synchronized (this) {
            notifyAll();
        }
    }
    /**
     * inserting the cards to the table
     * 
     * @pre - the table does not contain the needed cards from cardsToInsert
     *
     * @param - none
     * 
     *  @post - the card placed is on the table, in the assigned slot.
     */
    public void insertCards() {
        while (!cardsToInsert.isEmpty()) {
            table.placeCard(cardsToInsert.remove(ZERO), cardsToInsert.remove(ZERO));
        }
    }
}
