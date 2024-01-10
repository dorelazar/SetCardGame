package bguspl.set.ex;

import java.util.Arrays;
import java.util.LinkedList;
// import java.util.List;
import java.util.Queue;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    public Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    public Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    public volatile boolean terminate;

    /**
     * The current score of the player.
     */

    private int score;
    public static final long sec = 1000;
    public static final int empty = -1;
    public static final long miliSec = 1;

    enum flagEnum {
        RESET,
        PENALTY,
        POINT
    }

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Queue<Integer> pressedQueue;
    private Dealer dealer;
    public flagEnum flag;
    public int countCards;
    public Integer[] cards;
    public Integer[] slots;

    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        this.countCards = Dealer.ZERO;
        this.flag = flagEnum.RESET;
        this.pressedQueue = new LinkedList<>();
        this.cards = new Integer[env.config.featureSize];
        this.slots = new Integer[env.config.featureSize];
        Arrays.fill(cards, null);
        Arrays.fill(slots, null);
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        if (!human)
            createArtificialIntelligence();
        while (!terminate) {
            if (flag != flagEnum.RESET) {
                if (flag == flagEnum.PENALTY) {
                    timerHelper(env.config.penaltyFreezeMillis);
                } else { // point
                    timerHelper(env.config.pointFreezeMillis);
                }
            }
            synchronized (this) {
                while (pressedQueue.isEmpty() && !dealer.availableForActions && flag == flagEnum.RESET) {
                    try {
                        this.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
                if (!pressedQueue.isEmpty() && flag == flagEnum.RESET) {
                    int slot = pressedQueue.remove();
                    if (table.slotToCard[slot] != null) {
                        boolean alreadyPicked = false; // indicates that the current card has been already selected by
                                                       // the
                        // player
                        for (int i = Dealer.ZERO; i < slots.length; i++) {
                            if (slots[i] != null && slot == slots[i]) {
                                table.removeToken(id, slot);
                                alreadyPicked = true;
                                slots[i] = null;
                                cards[i] = null;
                                countCards--;
                                break;
                            }
                        }
                        if (!alreadyPicked) { // the card hadn't been selected yet by the player
                            for (int i = Dealer.ZERO; i < slots.length; i++) {
                                if (cards[i] == null && table.slotToCard[slot] != null) {
                                    synchronized (dealer) {
                                        table.placeToken(id, slot);
                                    }
                                    cards[i] = table.slotToCard[slot];
                                    slots[i] = slot;
                                    countCards++;
                                    alreadyPicked = true;
                                    break;
                                }
                            }
                            if (countCards == cards.length && alreadyPicked) { // reached 3 cards
                                dealer.addPotential(this);
                                try {
                                    wait();
                                } catch (InterruptedException e) {

                                }
                            }

                        }
                    }
                }
            }
        }
        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                while (!dealer.availableForActions & pressedQueue.size() == env.config.featureSize) {
                    synchronized (this) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                        }
                    }
                }
                int rnd = (int) (Math.random() * env.config.tableSize);
                keyPressed(rnd);
            }
            env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        try {
            if (!human) {
                aiThread.join();
            }
            playerThread.join();
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        synchronized (this) {
            if (pressedQueue.size() < env.config.featureSize & flag == flagEnum.RESET
                    & table.slotToCard[slot] != null & dealer.availableForActions) {
                pressedQueue.add(slot);
                notifyAll();
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        synchronized (this) {
            flag = flagEnum.POINT;
            notifyAll();
        }
    }

   /**
     * Penalize a player and perform other related actions.
     * @post - player's flag has changed to PENALTY
     */
    public void penalty() {
        synchronized (this) {
            flag = flagEnum.PENALTY;
            notifyAll();
        }
    }

    public int score() {
        return score;
    }

    public void join() {
        try {
            playerThread.join();
        } catch (InterruptedException ignored) {
        }
    }

    public void timerHelper(long freezetime) { // taking care of the player's sleeping seconds
        try {
            if(freezetime < 1000) {
                env.ui.setFreeze(id, freezetime);
                Thread.sleep(freezetime);
            } else {
            for (int i = Dealer.ZERO; i < freezetime / sec; i++) {
                env.ui.setFreeze(id, freezetime - i * sec);
                Thread.sleep(sec);
            }
        }
            env.ui.setFreeze(id, Dealer.ZERO);
        } catch (InterruptedException ignored) {
        }
        flag = flagEnum.RESET;
    }

    public Thread checkThread() { // returning the current thread
        return Thread.currentThread();
    }

    public void clearCache() { // a method that resets the tested fields of the players
        countCards = Dealer.ZERO;
        for (int i = Dealer.ZERO; i < slots.length; i++) {
            slots[i] = null;
            cards[i] = null;
        }
        pressedQueue.clear();
    }
/**
     * changing the player's flag to a new assigned flag.
     * 
     * @param newFlag - the flag that we want to change to.
     *
     * @post - the new flag is assigned to the current player
     */

    public void setFlag(flagEnum newFlag) { // changing the players flag
        flag = newFlag;
    }
}
