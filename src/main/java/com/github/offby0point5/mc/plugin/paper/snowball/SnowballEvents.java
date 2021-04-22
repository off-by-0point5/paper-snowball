package com.github.offby0point5.mc.plugin.paper.snowball;

import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.util.*;

public class SnowballEvents implements Listener {
    private enum PlayerState {
        WAITING,
        PLAYING
    }
    private enum GameState {
        RUNNING,
        WAITING
    }
    private enum GameTarget {
        KILL_LIMIT,
        TIME_LIMIT,
        NEUTRAL
    }

    // State variables
    private final Map<Player, PlayerState> playerStateMap = new HashMap<>();  // if player is waiting for next game or playing
    private final Map<Player, GameTarget> playerGameTargetVotingMap = new HashMap<>();  // mode voting per player
    private final Map<Player, Integer> playerKillsMap = new HashMap<>();  // deaths per player
    private final Map<Player, Integer> playerDeathsMap = new HashMap<>();  // kills per player
    private final Map<Player, Integer> playerSnowballsMap = new HashMap<>();  // snowballs per player
    private final Map<Player, Integer> playerPickupTicksMap = new HashMap<>();  // pickup ticks per player
    private final Map<Player, Integer> catchModeTicks = new HashMap<>();  // ticks remaining until player leaves catch mode
    private final Map<Player, Scoreboard> playerScoreboardMap = new HashMap<>();  // scoreboard display for each player
    private final Map<Player, List<Objective>> playerObjectiveListMap = new HashMap<>();  // display objectives for each player
    private final Map<String, Boolean> clickAction = new HashMap<>();  // if player issued a click action in this tick
    private GameTarget gameTarget;  // either playing until time is up or one player has reached certain amount of kills
    private int gameTargetAmount;  // how much of the target needs to be reached
    private int stateTicks;  // how long the current state lasts or will last
    private GameState gameState;  // if the game is running or waiting for players
    private int lastRemaining;  // the last value of the remainingTarget variable issued a title display to the players

    // Game rules
    private World world;  // World/Dimension the game takes place
    private final Material snowballOnGroundItem;  // item to represent snowballs on ground (in item frames)
    private final Material snowballItem;  // item to represent snowballs in inventory
    private final Material emptyItem;  // item to represent zero snowballs in inventory (for right click detection)
    private final ItemStack voteTime;  // displayed in wait mode to vote for TIME_LIMIT mode in next round
    private final ItemStack voteKills;  // displayed in wait mode to vote for KILL_LIMIT mode in next round
    private final int minPlayers;  // Players needed to start waiting counter
    private final int waitTicks;  // Ticks to wait before each game
    private final int standardTimeLimit;  // When GameTarget TIME_LIMIT is set (in ticks)
    private final int standardKillLimit;  // When GameTarget KILL_LIMIT is set (in kills/hits)
    private final int snowballPickupTicks;  // how long players have to sneak to pick up snowballs
    private final int maxSnowballs;  // maximum of snowballs players can obtain
    private final int startSnowballs;  // start amount of snowballs
    private final float catchableAngle;  // only balls coming in less than this angle can be caught
    private final int catchTicks;  // when right click was issued, player gets catch mode for this many ticks


    public SnowballEvents() {
        this.gameTarget = GameTarget.TIME_LIMIT;
        this.snowballOnGroundItem = Material.SNOW_BLOCK;
        this.snowballItem = Material.COAL;
        this.emptyItem = Material.FLINT_AND_STEEL;
        // Item for kill limit
        this.voteKills = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta itemMeta = this.voteKills.getItemMeta();
        itemMeta.displayName(Component.text("Trefferlimit"));
        itemMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        this.voteKills.setItemMeta(itemMeta);
        // Item for time limit
        this.voteTime = new ItemStack(Material.CLOCK);
        itemMeta = this.voteTime.getItemMeta();
        itemMeta.displayName(Component.text("Zeitlimit"));
        itemMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        this.voteTime.setItemMeta(itemMeta);
        this.minPlayers = 2;  // two players needed as it is competitive
        this.waitTicks = 200;  // 10 seconds between rounds
        this.standardKillLimit = 20;  // one player needs 20 kills to win kill limited mode
        this.standardTimeLimit = 60*2+30;  // play two and a half minutes in time limited mode
        this.snowballPickupTicks = 30;  // 1.5 seconds to pick up snowballs
        this.maxSnowballs = 3;  // one can have three snowballs, but not more
        this.startSnowballs = 1;  // each player gets one snowball to start with
        this.catchableAngle = 0.3f;  // angle in which one can catch snowballs
        this.catchTicks = 6;  // as right clicks come approximately every each 5 ticks
        this.startWaiting();
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        World world = event.getWorld();
        this.world = world;
        if (!world.getName().equals("world")) return;
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setViewDistance(5);
        world.setAutoSave(false);  // disable auto saving
        world.setPVP(false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);  // may change if using ticks to spawn snowballs
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setTime(600);

        // Set up item frames
        Collection<ItemFrame> frames = world.getEntitiesByClass(ItemFrame.class);
        for (ItemFrame frame : frames) {
            if (frame.getAttachedFace() != BlockFace.DOWN) continue;
            frame.setRotation(Rotation.CLOCKWISE_45);
            frame.setInvulnerable(true);
            frame.setFixed(true);
            frame.setVisible(false);
            frame.setGlowing(true);
        }
    }

    // Make inventories unmodifiable
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) { event.setCancelled(true); }
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) { event.setCancelled(true); }
    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) { event.setCancelled(true); }

    // do not interact with anything and always throw snowballs
    @EventHandler
    public void onInteract(PlayerInteractEvent event){
        event.setCancelled(true);
        // Protect environment
        if (event.getAction() == Action.PHYSICAL) return;
        // Ignore offhand
        if (event.getHand() != EquipmentSlot.HAND) return;
        // Allow to use doors
        if (event.getClickedBlock() != null && (event.getClickedBlock().getType() == Material.OAK_DOOR
                || event.getClickedBlock().getType() == Material.DARK_OAK_DOOR
                || event.getClickedBlock().getType() == Material.ACACIA_DOOR
                || event.getClickedBlock().getType() == Material.BIRCH_DOOR
                || event.getClickedBlock().getType() == Material.JUNGLE_DOOR
                || event.getClickedBlock().getType() == Material.SPRUCE_DOOR
                || event.getClickedBlock().getType() == Material.CRIMSON_DOOR
                || event.getClickedBlock().getType() == Material.WARPED_DOOR)) {

            event.setUseInteractedBlock(Event.Result.ALLOW);
            this.clickAction.put(event.getPlayer().getName(), false);  // cancel all events after this
            return;
        }
        if (this.gameState == GameState.RUNNING) {
            // Ignore if there was an interaction in this tick before
            if (!this.clickAction.getOrDefault(event.getPlayer().getName(), true)) return;
            // Right click to catch balls
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) {
                this.clickAction.put(event.getPlayer().getName(), false);  // cancel all events after this
                this.catchModeTicks.put(event.getPlayer(), this.catchTicks);  // set player into catch mode
                return;
            }
            // Left click to throw balls
            if (event.getItem() != null && event.getItem().getType() == this.snowballItem
                    && (event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_AIR)
                    && this.playerSnowballsMap.getOrDefault(event.getPlayer(), 0) >= 1) {
                this.playerSnowballsMap.putIfAbsent(event.getPlayer(), 0);
                this.playerSnowballsMap.computeIfPresent(event.getPlayer(), (k, v) -> v - 1);
                updatePlayerInventory(event.getPlayer());
                event.getPlayer().launchProjectile(Snowball.class);
                this.catchModeTicks.put(event.getPlayer(), 0);  // reset player catch mode
            }
        } else {  // if game is waiting
            if (event.getItem() != null
                    && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
                switch (event.getItem().getType()) {
                    case CLOCK:
                        this.playerGameTargetVotingMap.put(event.getPlayer(), GameTarget.TIME_LIMIT);
                        event.getPlayer().sendActionBar(Component.text("Du stimmst für ein Zeitlimit"));
                        this.updatePlayerInventory(event.getPlayer());
                        break;
                    case TOTEM_OF_UNDYING:
                        this.playerGameTargetVotingMap.put(event.getPlayer(), GameTarget.KILL_LIMIT);
                        event.getPlayer().sendActionBar(Component.text("Du stimmst für ein Trefferlimit"));
                        this.updatePlayerInventory(event.getPlayer());
                        break;
                }
                event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1.0f, 1.0f);
            }
        }

    }
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        this.clickAction.put(event.getPlayer().getName(), false);  // cancel all events after this
        this.catchModeTicks.put(event.getPlayer(), this.catchTicks);  // enable catch mode for player
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Display welcome message
        player.sendTitle("", "§6Schneeballschlacht!", 30, 60, 30);

        // Set up player
        this.playerStateMap.put(player, PlayerState.WAITING);
        // todo add setPlayerState()
        this.playerGameTargetVotingMap.put(player, GameTarget.NEUTRAL);
        this.playerDeathsMap.put(player, 0);
        this.playerKillsMap.put(player, 0);
        this.playerScoreboardMap.put(player, Bukkit.getScoreboardManager().getNewScoreboard());
        this.playerScoreboardMap.get(player).registerNewObjective("display", "dummy",
                Component.text("Schneeballschlacht")).setDisplaySlot(DisplaySlot.SIDEBAR);
        player.setScoreboard(this.playerScoreboardMap.get(player));
        updateScoreboards();
        updatePlayerInventory(event.getPlayer());
    }
    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event) {
        // clear player data
        Player player = event.getPlayer();
        this.playerStateMap.remove(player);
        this.playerSnowballsMap.remove(player);
        this.playerKillsMap.remove(player);
        this.playerDeathsMap.remove(player);
        this.playerPickupTicksMap.remove(player);
        this.playerScoreboardMap.remove(player);
        this.playerGameTargetVotingMap.remove(player);
        // End round if not enough players are in game ( and a round is running)
        if (this.gameState == GameState.RUNNING) {
            List<Player> players = this.world.getPlayers();
            int playersInGame = 0;
            for (Player statePlayer : players) {
                if (this.playerStateMap.get(statePlayer) == PlayerState.PLAYING) playersInGame++;
            }
            if (playersInGame < this.minPlayers) {
                this.startWaiting();
                for (Player notifyPlayer : players) {
                    notifyPlayer.sendTitle("Zu wenig Spieler!", "Runde vorbei!", 10, 30, 20);
                    notifyPlayer.playSound(notifyPlayer.getLocation(), Sound.ENTITY_GHAST_SCREAM, 1.0f, 1.0f);
                }
            }
        }
    }

    @EventHandler  // Snowball hit and catch
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity().getType() == EntityType.SNOWBALL){
            Entity hitEntity = event.getHitEntity();
            ProjectileSource projectileSource = event.getEntity().getShooter();
            if (hitEntity instanceof Player && projectileSource instanceof Player) {
                // Snowball hitting logic
                Player source = (Player) projectileSource;
                Player target = (Player) hitEntity;
                if (!(this.playerStateMap.get(source) == PlayerState.PLAYING
                        && this.playerStateMap.get(target) == PlayerState.PLAYING)) return;
                // If player is in catch mode
                if (this.catchModeTicks.getOrDefault(target,0) > 0) {
                    // get angle of ball to catch
                    Vector targetDirection = target.getLocation().getDirection();
                    Vector ballDirection = event.getEntity().getLocation().getDirection();
                    float angle = targetDirection.angle(ballDirection);
                    System.out.println(angle);
                    // If player looks in right direction
                    if (angle <= this.catchableAngle) {
                        if (this.playerSnowballsMap.getOrDefault(target, 0) < this.maxSnowballs) {
                            this.playerSnowballsMap.putIfAbsent(target, 0);
                            this.playerSnowballsMap.computeIfPresent(target, (k, v) -> v+1);
                            updatePlayerInventory(target);
                        }
                        target.sendActionBar(Component.text(
                                "Du hast " + source.getName() + "'s Ball gefangen!",
                                TextColor.color(50, 255, 50)));
                        source.sendActionBar(Component.text(
                                target.getName() + " hat gefangen!",
                                TextColor.color(255, 50, 50)));
                        return;
                    }
                }
                // Kill hit player =======================================
                this.playerDeathsMap.computeIfPresent(target, (k, v) -> v+1);
                target.damage(1000000);  // todo use teleportation and make player spawns
                target.sendActionBar(Component.text(
                        "Du wurdest von " + source.getName() + " getroffen!",
                        TextColor.color(255, 50, 50)));
                // Honor shooting player ======================================
                this.playerKillsMap.computeIfPresent(source, (k, v) -> v+1);
                source.sendActionBar(Component.text(
                        "Du hast " + target.getName() + " getroffen!",
                        TextColor.color(50, 255, 50)));

                // Update Scoreboard
                this.updateScoreboards();
            }
        }
    }


    @EventHandler
    public void onTick(ServerTickStartEvent event) {
        switch (this.gameState) {
            case WAITING:
                if (this.world.getPlayers().size() >= this.minPlayers) this.stateTicks--;
                else {
                    this.stateTicks = this.waitTicks;
                    break;
                }
                if (this.stateTicks == 0) {
                    for (Player player : this.world.getPlayers()) {
                        player.sendTitle("", "§aStart!", 0, 10, 10);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    }
                    this.startGame();
                    break;
                }
                // show titles with remaining time
                if (this.stateTicks % (5*20) == 0 || (this.stateTicks <= 60 && this.stateTicks % 20 == 0)) {
                    for (Player player : this.world.getPlayers()) {
                        player.sendTitle("", ""+this.stateTicks / 20, 0, 10, 10);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
                    }
                }
                break;
            case RUNNING:
                this.clickAction.clear();  // reset disallowed interaction
                // count down ticks of catch mode per player
                for (Player player : this.catchModeTicks.keySet()) {
                    this.catchModeTicks.computeIfPresent(player, (k, v) -> (v>0) ? v-1 : 0);
                }

                // Every tick a snowball is spawned in some of the item frames (~10 seconds -> chance 0.005/tick)
                Collection<ItemFrame> frames = world.getEntitiesByClass(ItemFrame.class);
                for (ItemFrame frame : frames) {
                    if (frame.getAttachedFace() != BlockFace.DOWN) continue;
                    if (frame.getItem().getType().equals(Material.SNOW_BLOCK)) continue;
                    if (new Random().nextInt(200) == 0) {  // 0.5%
                        frame.setItem(new ItemStack(this.snowballOnGroundItem), false);
                    }
                }

                // If player sneaks on item frame, picks up snowball
                List<Player> worldPlayers = world.getPlayers();
                for (Player player : worldPlayers) {
                    if (!player.isSneaking()) {
                        if (this.playerPickupTicksMap.getOrDefault(player, 0) > 0) {
                            player.sendActionBar(Component.text("Schneeball liegen gelassen!",
                                    TextColor.color(255, 0, 100)));
                        }
                        this.playerPickupTicksMap.put(player, 0);
                        continue;
                    }
                    List<Entity> entities = player.getNearbyEntities(0.5, 0.5, 0.5);
                    boolean isNearItemFrame = false;
                    for (Entity entity : entities) {
                        if (entity instanceof ItemFrame
                                && ((ItemFrame) entity).getAttachedFace() == BlockFace.DOWN
                                && ((ItemFrame) entity).getItem().getType() == this.snowballOnGroundItem) {
                            if (this.playerSnowballsMap.getOrDefault(player, 0) >= this.maxSnowballs) {
                                player.sendActionBar(Component.text("Mehr kannst du nicht tragen!",
                                        TextColor.color(255, 100, 50)));
                                this.playerPickupTicksMap.put(player, 0);
                                break;
                            }
                            if (this.playerPickupTicksMap.getOrDefault(player, 0) >= this.snowballPickupTicks) {
                                ((ItemFrame) entity).setItem(null);
                                this.playerSnowballsMap.putIfAbsent(player, 0);
                                this.playerSnowballsMap.computeIfPresent(player, (k, v) -> v+1);
                                updatePlayerInventory(player);
                                player.sendActionBar(Component.text("Schneeball aufgehoben!",
                                        TextColor.color(0, 255, 100)));
                                this.playerPickupTicksMap.put(player, 0);
                            } else {
                                this.playerPickupTicksMap.putIfAbsent(player, 0);
                                this.playerPickupTicksMap.computeIfPresent(player, (k, v) -> v=v+1);
                                if (this.playerPickupTicksMap.getOrDefault(player, 0) % 2 == 0) {
                                    player.sendActionBar(Component.text(String.format("Schneeball aufheben: %.0f%%",
                                            100.0 * this.playerPickupTicksMap.getOrDefault(player, 0)
                                                    / this.snowballPickupTicks),
                                            TextColor.color(0, 170, 255)));
                                }
                            }
                            isNearItemFrame = true;
                            break;
                        }
                    }
                    if (!isNearItemFrame) {
                        if (this.playerPickupTicksMap.getOrDefault(player, 0) > 0) {
                            player.sendActionBar(Component.text("Schneeball liegen gelassen!",
                                    TextColor.color(255, 0, 100)));
                        }
                        this.playerPickupTicksMap.put(player, 0);
                    }
                }

                this.stateTicks++;
                int targetRemaining = 0;
                switch (this.gameTarget) {
                    case TIME_LIMIT:
                        targetRemaining = this.gameTargetAmount - (this.stateTicks/20);
                        // titles show remaining time
                        if (targetRemaining != this.lastRemaining) {
                            if ((targetRemaining % 60 == 0)
                                    || (targetRemaining <= 30 && targetRemaining % 10 == 0)
                                    || targetRemaining < 5) {
                                String timeString = String.format("§c%02d:%02d verbleibend",
                                        targetRemaining/60, targetRemaining%60);
                                for (Player player : this.world.getPlayers()) {
                                    player.sendTitle("", timeString,
                                            0, 20, 5);
                                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
                                }
                            }
                            this.lastRemaining = targetRemaining;
                        }
                        break;
                    case KILL_LIMIT:
                        targetRemaining = this.gameTargetAmount - getHighestKills();
                        // titles show remaining kills
                        if (targetRemaining != this.lastRemaining) {
                            if (targetRemaining == 10 || targetRemaining <= 3)
                                for (Player player : this.world.getPlayers()) {
                                    player.sendTitle("", targetRemaining + " verbleibend!",
                                            0, 20, 5);
                                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
                                }
                            this.lastRemaining = targetRemaining;
                        }
                        break;
                }
                if (targetRemaining == 0) {
                    // show titles with placement
                    this.startWaiting();  // end this round
                }
                // Update time in side display
                if (this.stateTicks % 20 == 0) this.updateScoreboards();
                break;
            default: this.startWaiting();
        }
    }

    private void startGame() {
        this.gameTarget = getTargetVoting();
        this.gameState = GameState.RUNNING;
        this.stateTicks = 0;
        switch (this.gameTarget) {
            case KILL_LIMIT:
                this.gameTargetAmount = this.standardKillLimit;
                break;
            case TIME_LIMIT:
                this.gameTargetAmount = this.standardTimeLimit;
                break;
        }
        for (Player player : this.playerStateMap.keySet()) {
            if (this.playerStateMap.get(player) == PlayerState.WAITING)
                this.playerStateMap.put(player, PlayerState.PLAYING);
            this.playerSnowballsMap.put(player, this.startSnowballs);
            this.playerKillsMap.put(player, 0);
            this.playerDeathsMap.put(player, 0);
            this.playerPickupTicksMap.clear();
            Location playerLocation = player.getLocation();
            playerLocation.setY(5.0);
            player.teleport(playerLocation);
            this.updatePlayerInventory(player);
            // todo spawn a the farthest point away from players possible
        }
    }

    private void startWaiting() {
        this.gameState = GameState.WAITING;
        this.stateTicks = this.waitTicks;
        for (Player player : this.playerStateMap.keySet()) {
            if (this.playerStateMap.get(player) == PlayerState.PLAYING)
                this.playerStateMap.put(player, PlayerState.WAITING);
            Location playerLocation = player.getLocation();
            playerLocation.setY(20.0);
            player.teleport(playerLocation);
            this.updatePlayerInventory(player);
        }
    }

    private void updatePlayerInventory(Player player) {
        ItemStack[] hotbar = new ItemStack[9];
        switch (this.playerStateMap.get(player)) {
            case PLAYING:
                int snowballsAmount = this.playerSnowballsMap.get(player);
                if (snowballsAmount == 0) Arrays.fill(hotbar, new ItemStack(this.emptyItem, 1));
                else Arrays.fill(hotbar, new ItemStack(this.snowballItem, snowballsAmount));
                break;
            case WAITING:
                ItemStack timeItem = this.voteTime.clone();
                ItemStack killItem = this.voteKills.clone();
                ItemMeta itemMeta;
                switch (this.playerGameTargetVotingMap.get(player)) {
                    case TIME_LIMIT:
                        itemMeta = timeItem.getItemMeta();
                        itemMeta.addEnchant(Enchantment.DURABILITY, 1, true);
                        timeItem.setItemMeta(itemMeta);
                        break;
                    case KILL_LIMIT:
                        itemMeta = killItem.getItemMeta();
                        itemMeta.addEnchant(Enchantment.DURABILITY, 1, true);
                        killItem.setItemMeta(itemMeta);
                    case NEUTRAL: break;
                }
                hotbar[3] = timeItem;
                hotbar[5] = killItem;
                break;
        }
        player.getInventory().setContents(hotbar);
    }

    private void updateScoreboards() {
        // === Get lines displayed to all players ==================
        // remaining time/kills until game ends
        String remainingString = "";
        switch (this.gameTarget) {
            case TIME_LIMIT:
                int targetRemaining = this.gameTargetAmount - (this.stateTicks/20);
                remainingString = String.format("§bVerbleibende Zeit: %02d:%02d", targetRemaining/60, targetRemaining%60);
                break;
            case KILL_LIMIT:
                targetRemaining = this.gameTargetAmount - this.getHighestKills();
                remainingString = String.format("§bDer/Dem Besten fehlen Treffer: %d", targetRemaining);
        }
        // time the game is running
        String timeString = String.format("§dZeit vergangen: %02d:%02d", this.stateTicks/1200, (this.stateTicks/20)%60);
        // get players ranks sorted by kills
        List<String> playerRanksLines = new ArrayList<>();
        List<Map.Entry<Player, Integer>> entryList = new ArrayList<>(this.playerKillsMap.entrySet());
        int playersNum = entryList.size();
        int currentPlace = playersNum - 1;
        int lastPlace = playersNum - currentPlace;
        int lastScore = getHighestKills();
        entryList.sort(Map.Entry.comparingByValue((integer, t1) -> t1.equals(integer) ? 0 : (t1 > integer) ? 1 : -1));
        for (Map.Entry<Player, Integer> entry : entryList) {
            if (entry.getValue() < lastScore) {
                lastPlace = playersNum - currentPlace;
                lastScore = entry.getValue();
            }
            currentPlace--;
            playerRanksLines.add(String.format("§6%d. §b%s§r: §a%d§r/§c%d", lastPlace, entry.getKey().getName(),
                    entry.getValue(), this.playerDeathsMap.get(entry.getKey())));
        }
        // === Display lines ============================================
        for (Player player : this.world.getPlayers()) {
            Scoreboard scoreboard = this.playerScoreboardMap.get(player);
            int objectiveNum = 0;
            this.playerObjectiveListMap.computeIfAbsent(player, k -> new ArrayList<>());
            while (this.playerObjectiveListMap.get(player).size() < 2) {
                this.playerObjectiveListMap.get(player).add(scoreboard.registerNewObjective(
                        "display"+objectiveNum++, "dummy", Component.text("")));
            }
            Objective display = this.playerObjectiveListMap.get(player).get(0);
            Objective render = this.playerObjectiveListMap.get(player).get(1);
            String objectiveName = render.getName();
            render.unregister();
            render = scoreboard.registerNewObjective(objectiveName, "dummy", Component.text(""));
            render.displayName(Component.text("§e"+player.getName()+" spielt Schneeballschlacht!"));
            // use two objectives to "render" one in background, then switch -> mitigating flicker
            // Set objectives in reversed order into list
            this.playerObjectiveListMap.get(player).set(0, render);
            this.playerObjectiveListMap.get(player).set(1, display);

            // display remaining time/kills
            // List<String> contents = new ArrayList<>(this.playerSideContentMap.get(player));
            List<String> contents = new ArrayList<>();
            // game state info
            contents.add(timeString);
            contents.add(remainingString);
            contents.add("§6==============================");
            contents.addAll(playerRanksLines);
            contents.add("§6==============================");

            int lineCount = contents.size();
            for (String line : contents) {
                Score score = render.getScore(line);
                int occurrence = 0;
                while (score.isScoreSet()) {
                    score = render.getScore(line+("§"+occurrence++));
                }
                score.setScore(lineCount--);
            }
            render.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
    }

    private int getHighestKills() {
        int highestKills = 0;
        for (Player player : this.playerKillsMap.keySet()) {
            if (this.playerKillsMap.get(player) > highestKills)
                highestKills = this.playerKillsMap.get(player);
        }
        return highestKills;
    }

    private GameTarget getTargetVoting() {
        int timeVotes = 0;
        int killVotes = 0;
        for (GameTarget target : this.playerGameTargetVotingMap.values()) {
            switch (target) {
                case TIME_LIMIT:
                    timeVotes++;
                    break;
                case KILL_LIMIT:
                    killVotes++;
            }
        }
        if (timeVotes > killVotes) return GameTarget.TIME_LIMIT;
        else if (killVotes > timeVotes) return GameTarget.KILL_LIMIT;
        else if (this.gameTarget == null || this.gameTarget == GameTarget.NEUTRAL) return GameTarget.KILL_LIMIT;
        else return this.gameTarget;
    }
}
