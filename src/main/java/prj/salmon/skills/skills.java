package prj.salmon.skills;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import java.util.*;

public class skills extends JavaPlugin implements Listener {

    // --- Enum ---
    enum PlayerClass {
        NONE, ACROBAT, SCOUT, GUARDIAN, BLINK, BLAST, TELEPORT, DASH
    }

    // --- State Management ---
    private final Map<UUID, PlayerClass> playerClasses = new HashMap<>();
    private final Set<UUID> acrobatCooldown = new HashSet<>();
    private final Map<UUID, Long> combatTag = new HashMap<>();

    // --- Unique Ability State ---
    private final Map<UUID, Boolean> blinkHasCharge = new HashMap<>();
    private final Map<UUID, Boolean> blinkIsInAir = new HashMap<>();
    private final Map<UUID, Long> blinkLandingTime = new HashMap<>();

    private final Map<UUID, Integer> blastCharges = new HashMap<>();
    private final Map<UUID, Long> blastLastThrowTime = new HashMap<>();
    private final Map<UUID, Boolean> blastIsInAir = new HashMap<>();
    private final Map<UUID, Long> blastLandingTime = new HashMap<>();
    private final Map<UUID, Item> blastActivePacks = new HashMap<>();

    private final Map<UUID, GatecrashSession> tpActiveSessions = new HashMap<>();
    private final Map<UUID, Boolean> tpHasCharge = new HashMap<>();
    private final Map<UUID, Long> tpCooldownStartTime = new HashMap<>();

    private final Map<UUID, Double> dashEnergy = new HashMap<>();
    private final Map<UUID, Boolean> dashIsRunning = new HashMap<>();
    private final Map<UUID, Boolean> dashHasSlideCharge = new HashMap<>();
    private final Map<UUID, Long> dashSlideRechargeTime = new HashMap<>();
    private final Map<UUID, Long> dashLastEmptyTime = new HashMap<>();

    // --- Settings ---
    private static final String GUI_TITLE = "クラス選択";
    private static final double MAX_ENERGY = 100.0;
    private static final long BLINK_COOLDOWN_MS = 2000;
    private static final long BLAST_RECHARGE_MS = 4000;
    private static final long TP_RECHARGE_MS = 3500;
    private static final long DASH_REGEN_DELAY_MS = 3000;
    private static final long DASH_SLIDE_RECHARGE_MS = 5000;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        registerGrappleRecipe();
        startGlobalTask();
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
    }

    private void startGlobalTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    PlayerClass pClass = playerClasses.getOrDefault(uuid, PlayerClass.NONE);
                    if (pClass == PlayerClass.NONE)
                        continue;

                    boolean grounded = isGrounded(player);

                    // --- Blink Update ---
                    if (pClass == PlayerClass.BLINK) {
                        boolean wasInAir = blinkIsInAir.getOrDefault(uuid, false);
                        if (wasInAir && grounded && !blinkHasCharge.getOrDefault(uuid, true)
                                && !blinkLandingTime.containsKey(uuid)) {
                            blinkLandingTime.put(uuid, now);
                        }
                        blinkIsInAir.put(uuid, !grounded);
                        if (!blinkHasCharge.getOrDefault(uuid, true) && blinkLandingTime.containsKey(uuid)) {
                            if (now - blinkLandingTime.get(uuid) >= BLINK_COOLDOWN_MS) {
                                blinkHasCharge.put(uuid, true);
                                blinkLandingTime.remove(uuid);
                                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
                            }
                        }
                        updateBlinkActionBar(player);
                    }

                    // --- Blast Update ---
                    else if (pClass == PlayerClass.BLAST) {
                        boolean wasInAir = blastIsInAir.getOrDefault(uuid, false);
                        if (wasInAir && grounded && blastCharges.getOrDefault(uuid, 2) < 2
                                && !blastLandingTime.containsKey(uuid)) {
                            blastLandingTime.put(uuid, now);
                        }
                        blastIsInAir.put(uuid, !grounded);
                        if (blastLandingTime.containsKey(uuid)) {
                            if (now - blastLandingTime.get(uuid) >= BLAST_RECHARGE_MS) {
                                blastCharges.put(uuid, 2);
                                blastLandingTime.remove(uuid);
                                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
                            }
                        }
                        updateBlastActionBar(player);
                    }

                    // --- Teleport Update ---
                    else if (pClass == PlayerClass.TELEPORT) {
                        if (!tpHasCharge.getOrDefault(uuid, true) && !tpActiveSessions.containsKey(uuid)) {
                            if (tpCooldownStartTime.containsKey(uuid)) {
                                if (now - tpCooldownStartTime.get(uuid) >= TP_RECHARGE_MS) {
                                    tpHasCharge.put(uuid, true);
                                    tpCooldownStartTime.remove(uuid);
                                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f,
                                            1.5f);
                                }
                            } else {
                                tpCooldownStartTime.put(uuid, now);
                            }
                        }
                        updateTeleportActionBar(player);
                    }

                    // --- Dash Update ---
                    else if (pClass == PlayerClass.DASH) {
                        updateDashLogic(player, now, grounded);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void updateDashLogic(Player player, long now, boolean grounded) {
        UUID uuid = player.getUniqueId();
        Vector vel = player.getVelocity();
        boolean isMoving = Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ()) > 0.05 || player.isSprinting();
        double energy = dashEnergy.getOrDefault(uuid, MAX_ENERGY);

        if (dashIsRunning.getOrDefault(uuid, false)) {
            if (energy > 0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 5, 4, false, false, true));
                if (isMoving) {
                    energy = Math.max(0, energy - 1.0);
                    dashEnergy.put(uuid, energy);
                    if (player.getTicksLived() % 3 == 0) {
                        player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 0.5, 0), 2,
                                0.2, 0.2, 0.2, 0.05);
                        player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, player.getLocation().add(0, 0.5, 0),
                                1, 0.2, 0.2, 0.2, 0.02);
                    }
                }
            }
            if (energy <= 0) {
                stopDash(player, true);
            }
        } else {
            if (energy < MAX_ENERGY) {
                boolean inDelay = false;
                if (dashLastEmptyTime.containsKey(uuid)) {
                    if (now - dashLastEmptyTime.get(uuid) < DASH_REGEN_DELAY_MS)
                        inDelay = true;
                    else
                        dashLastEmptyTime.remove(uuid);
                }
                if (!inDelay) {
                    energy = Math.min(MAX_ENERGY, energy + 0.5);
                    dashEnergy.put(uuid, energy);
                }
            }
        }

        if (dashSlideRechargeTime.containsKey(uuid)) {
            if (now - dashSlideRechargeTime.get(uuid) >= DASH_SLIDE_RECHARGE_MS) {
                dashSlideRechargeTime.remove(uuid);
                dashHasSlideCharge.put(uuid, true);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.5f);
            }
        }
        updateDashActionBar(player, energy);
    }

    // --- Action Bar Helpers ---
    private void updateBlinkActionBar(Player player) {
        UUID uuid = player.getUniqueId();
        boolean charge = blinkHasCharge.getOrDefault(uuid, true);
        String cd = "";
        if (!charge && blinkLandingTime.containsKey(uuid)) {
            double remaining = Math.max(0,
                    (BLINK_COOLDOWN_MS - (System.currentTimeMillis() - blinkLandingTime.get(uuid))) / 1000.0);
            cd = String.format(" §c[%.1fs]", remaining);
        }
        sendActionBar(player, "§b§l[Blink] " + (charge ? "§a■" : "§7■") + cd);
    }

    private void updateBlastActionBar(Player player) {
        UUID uuid = player.getUniqueId();
        int c = blastCharges.getOrDefault(uuid, 2);
        String boxes = (c == 2) ? "§a■■" : (c == 1) ? "§a■§7■" : "§7■■";
        String cd = "";
        if (blastLandingTime.containsKey(uuid)) {
            double rem = Math.max(0,
                    (BLAST_RECHARGE_MS - (System.currentTimeMillis() - blastLandingTime.get(uuid))) / 1000.0);
            cd = String.format(" §c[%.1fs]", rem);
        }
        sendActionBar(player, "§e§l[Blast] " + boxes + cd);
    }

    private void updateTeleportActionBar(Player player) {
        UUID uuid = player.getUniqueId();
        boolean charge = tpHasCharge.getOrDefault(uuid, true);
        StringBuilder sb = new StringBuilder("§9§l[Teleport] ").append(charge ? "§a■" : "§7■");
        if (tpActiveSessions.containsKey(uuid)) {
            double rem = (600 - tpActiveSessions.get(uuid).life) / 20.0;
            sb.append(" §e[").append(String.format("%.1fs", rem)).append("]");
        } else if (!charge && tpCooldownStartTime.containsKey(uuid)) {
            double rem = Math.max(0,
                    (TP_RECHARGE_MS - (System.currentTimeMillis() - tpCooldownStartTime.get(uuid))) / 1000.0);
            sb.append(" §c[").append(String.format("%.1fs", rem)).append("]");
        }
        sendActionBar(player, sb.toString());
    }

    private void updateDashActionBar(Player player, double energy) {
        UUID uuid = player.getUniqueId();
        StringBuilder sb = new StringBuilder(
                dashIsRunning.getOrDefault(uuid, false) ? "§b⚡ Running: " : "§6⚡ Energy: ");
        sb.append(String.format("§e%d%%", (int) energy));
        if (dashLastEmptyTime.containsKey(uuid)) {
            double rem = (DASH_REGEN_DELAY_MS - (System.currentTimeMillis() - dashLastEmptyTime.get(uuid))) / 1000.0;
            if (rem > 0)
                sb.append(String.format(" §c[COOLDOWN %.1fs]", rem));
        }
        sb.append(" §7| §bSlide: ");
        if (dashSlideRechargeTime.containsKey(uuid)) {
            double rem = Math.max(0,
                    (DASH_SLIDE_RECHARGE_MS - (System.currentTimeMillis() - dashSlideRechargeTime.get(uuid))) / 1000.0);
            sb.append(String.format("§c[%.1fs]", rem));
        } else
            sb.append(dashHasSlideCharge.getOrDefault(uuid, true) ? "§a■" : "§7■");
        sendActionBar(player, sb.toString());
    }

    // --- GUI & Commands ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player))
            return true;
        if (command.getName().equalsIgnoreCase("skill")) {
            openClassSelectionGUI(player);
            return true;
        }
        return false;
    }

    private void openClassSelectionGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, GUI_TITLE);

        gui.setItem(1, createGuiItem(Material.FEATHER, "§bアクロバット",
                "§f・§7スニークで§f2段ジャンプ§7が可能",
                "§f・§7高い所から落ちても§c落下ダメージを完全に無効化§7する"));

        gui.setItem(3, createGuiItem(Material.FISHING_ROD, "§aスカウト",
                "§f・§7専用の§bグラップリングフック§7をクラフト・使用可能",
                "§f・§7常に§f俊敏 I§7 が付与され、落下ダメージも§f50%軽減§7される"));

        gui.setItem(5, createGuiItem(Material.SHIELD, "§eガーディアン",
                "§f・§7最大体力が§fハート2個分§7増加する",
                "§f・§7常に§f再生 I§7 が付与され、驚異的な耐久力を発揮する"));

        gui.setItem(10, createGuiItem(Material.FEATHER, "§b§lブリンク",
                "§7使用アイテム: 羽",
                "§f・§7右クリックで視線方向に§f瞬時に突進§7する",
                "§f・§7着地から §f2秒後 §7に再び使用可能になる"));

        gui.setItem(12, createGuiItem(Material.TNT_MINECART, "§e§lブラストパック",
                "§7使用アイテム: TNTトロッコ",
                "§f・§7右クリックで投げ、設置後に再度右クリックで§f起爆§7する",
                "§f・§7爆風で敵を吹き飛ばし、自分は空中で§f加速§7できる",
                "§f・§7着地後 §f4秒 §7で §f2チャージ §7回復"));

        gui.setItem(14, createGuiItem(Material.ENDER_PEARL, "§d§lテレポート",
                "§7使用アイテム: エンダーパール",
                "§f・§7右クリックでビーコンを射出し、再発動でその位置へ§f転送§7する",
                "§f・§7スニークで設置、またはフェイクテレポートも可能",
                "§f・§7リチャージ: §f3.5秒"));

        gui.setItem(16, createGuiItem(Material.BLAZE_ROD, "§6§lダッシュ",
                "§7使用アイテム: ブレイズロッド",
                "§f・§7右クリックでエネルギーを消費する§f高速走行§7をON/OFF",
                "§f・§7走行中にスニークで§fスライディング§7を発動",
                "§f・§7スライディング終了時に§fブースト§7が発生"));

        gui.setItem(22, createGuiItem(Material.BARRIER, "§c§l全て解除", "§7能力をリセット"));

        player.openInventory(gui);
    }

    private ItemStack createGuiItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE))
            return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta())
            return;

        String name = clicked.getItemMeta().getDisplayName();
        UUID uuid = player.getUniqueId();
        resetPlayer(player);

        if (name.equals("§bアクロバット")) {
            playerClasses.put(uuid, PlayerClass.ACROBAT);
            player.sendMessage("§bアクロバットを選択しました");
        } else if (name.equals("§aスカウト")) {
            playerClasses.put(uuid, PlayerClass.SCOUT);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false));
            player.getInventory().addItem(getUnbreakableGrappleItem());
            player.sendMessage("§aスカウトを選択しました");
        } else if (name.equals("§eガーディアン")) {
            playerClasses.put(uuid, PlayerClass.GUARDIAN);
            player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(24.0);
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 0, false, false));
            player.sendMessage("§eガーディアンを選択しました");
        } else if (name.contains("ブリンク")) {
            playerClasses.put(uuid, PlayerClass.BLINK);
            blinkHasCharge.put(uuid, true);
            player.getInventory().addItem(new ItemStack(Material.FEATHER));
            player.sendMessage("§bブリンクを選択しました");
        } else if (name.contains("ブラストパック")) {
            playerClasses.put(uuid, PlayerClass.BLAST);
            blastCharges.put(uuid, 2);
            player.getInventory().addItem(new ItemStack(Material.TNT_MINECART));
            player.sendMessage("§eブラストパックを選択しました");
        } else if (name.contains("テレポート")) {
            playerClasses.put(uuid, PlayerClass.TELEPORT);
            tpHasCharge.put(uuid, true);
            player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL));
            player.sendMessage("§dテレポートを選択しました");
        } else if (name.contains("ダッシュ")) {
            playerClasses.put(uuid, PlayerClass.DASH);
            dashEnergy.put(uuid, MAX_ENERGY);
            dashHasSlideCharge.put(uuid, true);
            player.getInventory().addItem(new ItemStack(Material.BLAZE_ROD));
            player.sendMessage("§6ダッシュを選択しました");
        } else if (name.contains("全て解除")) {
            player.sendMessage("§c能力をリセットしました");
        }

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        player.closeInventory();
    }

    private void resetPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20.0);
        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.REGENERATION);
        if (tpActiveSessions.containsKey(uuid)) {
            tpActiveSessions.get(uuid).cancelSession();
            tpActiveSessions.remove(uuid);
        }
        if (blastActivePacks.containsKey(uuid)) {
            Item it = blastActivePacks.remove(uuid);
            if (it != null)
                it.remove();
        }
        if (dashIsRunning.getOrDefault(uuid, false))
            player.removePotionEffect(PotionEffectType.SPEED);

        playerClasses.remove(uuid);
        acrobatCooldown.remove(uuid);
        combatTag.remove(uuid);

        blinkHasCharge.remove(uuid);
        blinkIsInAir.remove(uuid);
        blinkLandingTime.remove(uuid);

        blastCharges.remove(uuid);
        blastLastThrowTime.remove(uuid);
        blastIsInAir.remove(uuid);
        blastLandingTime.remove(uuid);

        tpHasCharge.remove(uuid);
        tpCooldownStartTime.remove(uuid);

        dashEnergy.remove(uuid);
        dashIsRunning.remove(uuid);
        dashHasSlideCharge.remove(uuid);
        dashSlideRechargeTime.remove(uuid);
        dashLastEmptyTime.remove(uuid);
    }

    // --- Ability Execution ---
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND)
            return;
        Player player = event.getPlayer();
        PlayerClass pClass = playerClasses.getOrDefault(player.getUniqueId(), PlayerClass.NONE);
        ItemStack item = player.getInventory().getItemInMainHand();

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (pClass == PlayerClass.BLINK && item.getType() == Material.FEATHER)
                executeBlink(player);
            else if (pClass == PlayerClass.BLAST && item.getType() == Material.TNT_MINECART) {
                event.setCancelled(true);
                executeBlast(player);
            } else if (pClass == PlayerClass.TELEPORT && item.getType() == Material.ENDER_PEARL) {
                event.setCancelled(true);
                executeTeleport(player);
            } else if (pClass == PlayerClass.DASH && item.getType() == Material.BLAZE_ROD) {
                event.setCancelled(true);
                toggleDash(player);
            }
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking())
            return;
        PlayerClass pClass = playerClasses.getOrDefault(player.getUniqueId(), PlayerClass.NONE);
        if (pClass == PlayerClass.ACROBAT)
            executeAcrobatJump(player);
        else if (pClass == PlayerClass.DASH && dashIsRunning.getOrDefault(player.getUniqueId(), false))
            executeSlide(player);
    }

    private boolean isGrounded(Player p) {
        return p.getLocation().subtract(0, 0.1, 0).getBlock().getType().isSolid();
    }

    private void executeAcrobatJump(Player player) {
        if (acrobatCooldown.contains(player.getUniqueId()))
            return;
        player.setVelocity(player.getLocation().getDirection().multiply(0.4).setY(1.0));
        acrobatCooldown.add(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> acrobatCooldown.remove(player.getUniqueId()), 60L);
    }

    private void executeBlink(Player player) {
        UUID uuid = player.getUniqueId();
        if (!blinkHasCharge.getOrDefault(uuid, true))
            return;
        blinkHasCharge.put(uuid, false);
        blinkLandingTime.remove(uuid);
        blinkIsInAir.put(uuid, true);
        Vector dir = player.getLocation().getDirection().clone().setY(0).normalize();
        Vector vel = dir.multiply(3.5 / 3);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.5f);
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks++ >= 3 || !player.isOnline()) {
                    cancel();
                    return;
                }
                player.setVelocity(vel);
                player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 1, 0), 5, 0.3, 0.3, 0.3,
                        0.02);
                player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 3, 0.2, 0.2, 0.2,
                        0.1);
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void executeBlast(Player player) {
        UUID uuid = player.getUniqueId();
        if (blastActivePacks.containsKey(uuid)) {
            if (System.currentTimeMillis() - blastLastThrowTime.getOrDefault(uuid, 0L) < 100)
                return;
            detonateBlast(player);
            return;
        }
        if (blastCharges.getOrDefault(uuid, 2) <= 0
                || System.currentTimeMillis() - blastLastThrowTime.getOrDefault(uuid, 0L) < 500)
            return;
        Item item = player.getWorld().dropItem(player.getEyeLocation(), new ItemStack(Material.TNT_MINECART));
        item.setVelocity(player.getEyeLocation().getDirection().multiply(0.6));
        item.setPickupDelay(32767);
        blastActivePacks.put(uuid, item);
        blastCharges.put(uuid, blastCharges.get(uuid) - 1);
        blastLandingTime.remove(uuid);
        blastLastThrowTime.put(uuid, System.currentTimeMillis());
        player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.8f);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (blastActivePacks.get(uuid) == item) {
                    if (item.isValid())
                        item.remove();
                    blastActivePacks.remove(uuid);
                }
            }
        }.runTaskLater(this, 100L);
    }

    private void detonateBlast(Player player) {
        UUID uuid = player.getUniqueId();
        Item item = blastActivePacks.remove(uuid);
        if (item == null || !item.isValid())
            return;
        Location loc = item.getLocation();
        item.remove();
        loc.getWorld().createExplosion(loc, 0f, false, false);
        loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 1.2f);
        loc.getWorld().spawnParticle(Particle.EXPLOSION_LARGE, loc, 3, 0.5, 0.5, 0.5, 0.1);
        loc.getWorld().spawnParticle(Particle.FLAME, loc, 30, 1.0, 1.0, 1.0, 0.1);
        loc.getWorld().spawnParticle(Particle.SMOKE_LARGE, loc, 20, 1.5, 1.5, 1.5, 0.05);

        double powerBase = blastIsInAir.getOrDefault(uuid, false) ? 2.0 : 1.35;
        loc.getWorld().getNearbyEntities(loc, 5, 5, 5).forEach(e -> {
            if (e instanceof Player p) {
                double dist = p.getLocation().distance(loc);
                if (dist > 5)
                    return;
                Vector bDir = p.getLocation().toVector().subtract(loc.toVector()).normalize();
                Vector lDir = p.getLocation().getDirection().normalize();
                Vector finalDir = blastIsInAir.getOrDefault(p.getUniqueId(), false) ? lDir
                        : bDir.multiply(0.8).add(lDir.multiply(0.2)).normalize();
                double power = powerBase * (1.0 - (dist / 5.0));
                Vector v = finalDir.multiply(power);
                v.setY(v.getY() * 0.35);
                if (blastIsInAir.getOrDefault(p.getUniqueId(), false))
                    p.setVelocity(new Vector(0, 0, 0));
                p.setVelocity(v);
            }
        });
    }

    private void executeTeleport(Player player) {
        UUID uuid = player.getUniqueId();
        if (tpActiveSessions.containsKey(uuid)) {
            if (player.isSneaking())
                fakeTeleport(player);
            else
                realTeleport(player);
            return;
        }
        if (!tpHasCharge.getOrDefault(uuid, true))
            return;
        Location start = player.getLocation().add(0, 0.5, 0);
        Vector dir = player.isSneaking() ? new Vector(0, 0, 0)
                : player.getLocation().getDirection().setY(0).normalize().multiply(0.25);
        GatecrashSession s = new GatecrashSession(uuid, start, dir);
        tpActiveSessions.put(uuid, s);
        s.runTaskTimer(this, 0L, 1L);
        tpHasCharge.put(uuid, false);
        if (player.isSneaking())
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.0f, 2.0f);
        else
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, 1.0f, 1.5f);
    }

    private void realTeleport(Player player) {
        UUID uuid = player.getUniqueId();
        GatecrashSession s = tpActiveSessions.remove(uuid);
        if (s == null)
            return;
        Location target = s.currentLocation.clone();
        target.setDirection(player.getLocation().getDirection());
        target.add(0, 0.5, 0);
        player.getWorld().spawnParticle(Particle.REDSTONE, player.getLocation().add(0, 1, 0), 20, 0.5, 1, 0.5,
                new Particle.DustOptions(Color.BLUE, 2));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 3.0f, 0.5f);
        player.teleport(target);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 20, 5, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_DIGGING, 20, 5, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20, 1, false, false, false));
        target.getWorld().spawnParticle(Particle.REDSTONE, target.clone().add(0, 1, 0), 20, 0.5, 1, 0.5,
                new Particle.DustOptions(Color.BLUE, 2));
        target.getWorld().playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 3.0f, 1.5f);
        s.cancelSession();
        tpCooldownStartTime.put(uuid, System.currentTimeMillis());
    }

    private void fakeTeleport(Player player) {
        UUID uuid = player.getUniqueId();
        GatecrashSession s = tpActiveSessions.remove(uuid);
        if (s == null)
            return;
        Location bLoc = s.currentLocation.clone();
        bLoc.getWorld().spawnParticle(Particle.REDSTONE, bLoc.clone().add(0, 1, 0), 20, 0.5, 1, 0.5,
                new Particle.DustOptions(Color.BLUE, 2));
        bLoc.getWorld().playSound(bLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 3.0f, 1.5f);
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks++ > 50) {
                    cancel();
                    return;
                }
                double y = bLoc.getY() + 0.15;
                for (int i = 0; i < 8; i++) {
                    double angle = Math.random() * Math.PI * 2, radius = Math.random() * 0.7;
                    Location pLoc = new Location(bLoc.getWorld(), bLoc.getX() + Math.cos(angle) * radius, y,
                            bLoc.getZ() + Math.sin(angle) * radius);
                    pLoc.getWorld().spawnParticle(Particle.REDSTONE, pLoc, 1, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(0, 0, 139), 2.0f));
                    if (Math.random() > 0.8)
                        pLoc.getWorld().spawnParticle(Particle.WATER_SPLASH, pLoc, 1, 0, 0, 0, 0);
                }
            }
        }.runTaskTimer(this, 0L, 1L);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
        player.sendMessage("§9§l[Teleport] §bFake Teleport!");
        s.cancelSession();
        tpCooldownStartTime.put(uuid, System.currentTimeMillis());
    }

    private void toggleDash(Player player) {
        UUID uuid = player.getUniqueId();
        boolean running = dashIsRunning.getOrDefault(uuid, false);
        if (running) {
            stopDash(player, false);
        } else {
            if (dashLastEmptyTime.containsKey(uuid)
                    && System.currentTimeMillis() - dashLastEmptyTime.get(uuid) < DASH_REGEN_DELAY_MS)
                return;
            if (dashEnergy.getOrDefault(uuid, 0.0) < 5.0)
                return;
            dashIsRunning.put(uuid, true);
            player.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1.0f, 2.0f);
        }
    }

    private void stopDash(Player player, boolean depleted) {
        UUID uuid = player.getUniqueId();
        dashIsRunning.put(uuid, false);
        player.removePotionEffect(PotionEffectType.SPEED);
        if (depleted) {
            dashLastEmptyTime.put(uuid, System.currentTimeMillis());
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.5f);
        } else
            player.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.5f);
    }

    private void executeSlide(Player player) {
        UUID uuid = player.getUniqueId();
        if (!dashHasSlideCharge.getOrDefault(uuid, true)) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 0.5f);
            return;
        }
        stopDash(player, false);
        dashHasSlideCharge.put(uuid, false);
        dashSlideRechargeTime.put(uuid, System.currentTimeMillis());
        Vector dir = player.getLocation().getDirection().setY(0).normalize();
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_SNOW_BREAK, 1.0f, 1.2f);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.5f);
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || ticks >= 12) {
                    if (player.isOnline())
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 25, 4, false, false, true));
                    cancel();
                    return;
                }
                double d = 1.0 - Math.pow((double) ticks / 12, 2);
                player.setVelocity(dir.clone().multiply(1.2 * d).setY(Math.min(player.getVelocity().getY(), 0.1)));
                if (ticks % 2 == 0) {
                    player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 0.1, 0), 8,
                            0.4, 0.05, 0.4, 0.08);
                    player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 0.05, 0), 2, 0.3, 0.02,
                            0.3, 0.01);
                }
                ticks++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    // --- Skill-main Helpers ---
    private void registerGrappleRecipe() {
        ItemStack i = new ItemStack(Material.FISHING_ROD);
        ItemMeta m = i.getItemMeta();
        if (m != null) {
            m.setDisplayName("§bグラップリングフック");
            m.addEnchant(Enchantment.DURABILITY, 1, true);
            m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            i.setItemMeta(m);
        }
        NamespacedKey k = new NamespacedKey(this, "grappling_hook");
        ShapedRecipe r = new ShapedRecipe(k, i);
        r.shape(" S ", "SFS", " S ");
        r.setIngredient('S', Material.STRING);
        r.setIngredient('F', Material.FISHING_ROD);
        Bukkit.addRecipe(r);
    }

    private ItemStack getUnbreakableGrappleItem() {
        ItemStack i = new ItemStack(Material.FISHING_ROD);
        ItemMeta m = i.getItemMeta();
        if (m != null) {
            m.setDisplayName("§bグラップリングフック");
            m.setUnbreakable(true);
            m.addEnchant(Enchantment.DURABILITY, 10, true);
            i.setItemMeta(m);
        }
        return i;
    }

    @EventHandler
    public void onDam(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.FALL)
            return;
        PlayerClass c = playerClasses.get(p.getUniqueId());
        if (c == PlayerClass.ACROBAT)
            e.setCancelled(true);
        else if (c == PlayerClass.SCOUT && isGrapple(p.getInventory().getItemInMainHand()))
            e.setDamage(e.getDamage() * 0.5);
    }

    private boolean isGrapple(ItemStack i) {
        return i != null && i.hasItemMeta() && i.getItemMeta().getDisplayName().equals("§bグラップリングフック");
    }

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        if (playerClasses.get(uuid) != PlayerClass.SCOUT || !isGrapple(p.getInventory().getItemInMainHand())
                || isGrapple(p.getInventory().getItemInOffHand()) || e.getState() != PlayerFishEvent.State.IN_GROUND)
            return;
        if (p.hasPotionEffect(PotionEffectType.SLOW) || p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)
                || (combatTag.containsKey(uuid) && System.currentTimeMillis() - combatTag.get(uuid) < 5000))
            return;
        Location hook = e.getHook().getLocation(), pLoc = p.getLocation();
        double dist = pLoc.distance(hook), speed = Math.min(dist * 0.3, 20.0);
        Vector pull = hook.toVector().subtract(pLoc.toVector());
        double yD = hook.getY() - pLoc.getY();
        pull.setY(yD > 0 ? yD * 0.2 : 0.4);
        double hD = Math.sqrt(pull.getX() * pull.getX() + pull.getZ() * pull.getZ());
        if (hD > 0) {
            pull.setX(pull.getX() / hD * speed);
            pull.setZ(pull.getZ() / hD * speed);
        }
        p.setVelocity(pull.add(p.getVelocity().multiply(0.2)));
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player p && playerClasses.get(p.getUniqueId()) == PlayerClass.SCOUT)
            combatTag.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        resetPlayer(e.getPlayer());
    }

    private class GatecrashSession extends BukkitRunnable {
        private final UUID ownerId;
        public Location currentLocation;
        private final Vector initialDirection;
        public int life = 0;
        private boolean isStuck = false;
        private ItemDisplay displayEntity;

        public GatecrashSession(UUID ownerId, Location startLoc, Vector direction) {
            this.ownerId = ownerId;
            this.currentLocation = startLoc;
            this.initialDirection = direction.clone();
            this.displayEntity = startLoc.getWorld().spawn(startLoc, ItemDisplay.class, entity -> {
                entity.setItemStack(new ItemStack(Material.HEART_OF_THE_SEA));
                Transformation t = entity.getTransformation();
                t.getScale().set(0.5f, 0.5f, 0.5f);
                entity.setTransformation(t);
            });
        }

        public void cancelSession() {
            if (displayEntity != null && displayEntity.isValid())
                displayEntity.remove();
            this.cancel();
        }

        @Override
        public void run() {
            if (life >= 600) {
                tpActiveSessions.remove(ownerId);
                this.cancelSession();
                tpCooldownStartTime.put(ownerId, System.currentTimeMillis());
                return;
            }
            currentLocation.getWorld().spawnParticle(Particle.REDSTONE, currentLocation.clone().add(0, 0.2, 0), 2, 0.1,
                    0, 0.1, new Particle.DustOptions(Color.BLUE, 1.0f));
            currentLocation.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, currentLocation.clone().add(0, 0.1, 0),
                    1, 0.05, 0.05, 0.05, 0.01);
            if (!isStuck) {
                double decay = life < 300 ? Math.max(0, 1.0 - ((double) life / 300)) : 0;
                if (decay > 0.001) {
                    Vector next = initialDirection.clone().multiply(decay);
                    Location nextLoc = currentLocation.clone().add(next);
                    if (nextLoc.getBlock().getType().isSolid())
                        isStuck = true;
                    if (!isStuck) {
                        if (!nextLoc.clone().add(0, -1, 0).getBlock().getType().isSolid())
                            nextLoc.add(0, -0.6, 0);
                        if (!nextLoc.getBlock().getType().isSolid())
                            currentLocation = nextLoc;
                        else
                            isStuck = true;
                    }
                }
            } else if (life % 20 == 0)
                currentLocation.getWorld().playSound(currentLocation, Sound.BLOCK_BEACON_AMBIENT, 0.1f, 2.0f);
            if (displayEntity != null && displayEntity.isValid()) {
                Location dLoc = currentLocation.clone();
                dLoc.setYaw(life * 15);
                displayEntity.teleport(dLoc);
            }
            life++;
        }
    }
}