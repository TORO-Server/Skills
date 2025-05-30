package prj.salmon.skills;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.util.Vector;

import java.util.*;

public class skills extends JavaPlugin implements Listener {

    enum PlayerClass { NONE, ACROBAT, SCOUT, GUARDIAN }

    private final Map<UUID, PlayerClass> playerClasses = new HashMap<>();
    private final Set<UUID> acrobatCooldown = new HashSet<>();
    private final Map<UUID, Long> combatTag = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        registerGrappleRecipe();
    }

    private void registerGrappleRecipe() {
        ItemStack grapple = getGrappleItem();
        NamespacedKey key = new NamespacedKey(this, "grappling_hook");
        ShapedRecipe recipe = new ShapedRecipe(key, grapple);
        recipe.shape(" S ", "SFS", " S ");
        recipe.setIngredient('S', Material.STRING);
        recipe.setIngredient('F', Material.FISHING_ROD);
        Bukkit.addRecipe(recipe);
    }

    private ItemStack getGrappleItem() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§bグラップリングフック");
            meta.addEnchant(Enchantment.DURABILITY, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack getUnbreakableGrappleItem() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§bグラップリングフック");
            meta.addEnchant(Enchantment.DURABILITY, 10, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isGrapple(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD) return false;
        if (!item.hasItemMeta()) return false;
        return "§bグラップリングフック".equals(item.getItemMeta().getDisplayName());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはプレイヤーのみ使用できます。");
            return true;
        }
        if (!command.getName().equalsIgnoreCase("skill")) return false;

        Player player = (Player) sender;
        if (args.length == 0) {
            openClassSelectionGUI(player);
            return true;
        }

        UUID uuid = player.getUniqueId();
        PlayerClass oldClass = playerClasses.getOrDefault(uuid, PlayerClass.NONE);
        if (oldClass == PlayerClass.SCOUT) {
            player.getActivePotionEffects().stream()
                    .filter(effect -> effect.getType().equals(PotionEffectType.SPEED))
                    .filter(effect -> effect.getAmplifier() == 0 && effect.getDuration() > 1000000)
                    .forEach(effect -> player.removePotionEffect(effect.getType()));
        }
        if (oldClass == PlayerClass.GUARDIAN) {
            player.getActivePotionEffects().stream()
                    .filter(effect -> effect.getType().equals(PotionEffectType.REGENERATION))
                    .filter(effect -> effect.getAmplifier() == 0 && effect.getDuration() > 1000000)
                    .forEach(effect -> player.removePotionEffect(effect.getType()));
            player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20.0);
        }

        String classArg = args[0].toLowerCase();
        switch (classArg) {
            case "acrobat":
                playerClasses.put(uuid, PlayerClass.ACROBAT);
                player.sendMessage("§bアクロバットを選択しました");
                break;
            case "scout":
                playerClasses.put(uuid, PlayerClass.SCOUT);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false));
                ItemStack unbreakableGrapple = getUnbreakableGrappleItem();
                player.getInventory().addItem(unbreakableGrapple);
                player.sendMessage("§aスカウトを選択しました");
                break;
            case "guardian":
                playerClasses.put(uuid, PlayerClass.GUARDIAN);
                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(24.0);
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 0, false, false));
                player.sendMessage("§eガーディアンを選択しました");
                break;
            default:
                player.sendMessage("無効なクラス名です。acrobat、scout、guardianを指定してください。");
                return false;
        }
        return true;
    }

    private void openClassSelectionGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, "クラス選択");

        ItemStack acrobatItem = new ItemStack(Material.FEATHER);
        ItemMeta acrobatMeta = acrobatItem.getItemMeta();
        if (acrobatMeta != null) {
            acrobatMeta.setDisplayName("§bアクロバット");
            acrobatMeta.setLore(Arrays.asList("§7少しの高さもへっちゃら ダブルジャンプと落下ダメ無効"));
            acrobatItem.setItemMeta(acrobatMeta);
        }

        ItemStack scoutItem = new ItemStack(Material.FISHING_ROD);
        ItemMeta scoutMeta = scoutItem.getItemMeta();
        if (scoutMeta != null) {
            scoutMeta.setDisplayName("§aスカウト");
            scoutMeta.setLore(Arrays.asList("§7グラップリングフックで上下移動もお手の物 俊敏Iが常時付与"));
            scoutItem.setItemMeta(scoutMeta);
        }

        ItemStack guardianItem = new ItemStack(Material.SHIELD);
        ItemMeta guardianMeta = guardianItem.getItemMeta();
        if (guardianMeta != null) {
            guardianMeta.setDisplayName("§eガーディアン");
            guardianMeta.setLore(Arrays.asList("§7ハートが+2個になって、常時再生Iが付与"));
            guardianItem.setItemMeta(guardianMeta);
        }

        gui.setItem(2, acrobatItem);
        gui.setItem(4, scoutItem);
        gui.setItem(6, guardianItem);

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("クラス選択")) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

        UUID uuid = player.getUniqueId();
        ItemMeta meta = event.getCurrentItem().getItemMeta();
        if (meta == null) return;

        PlayerClass oldClass = playerClasses.getOrDefault(uuid, PlayerClass.NONE);
        if (oldClass == PlayerClass.SCOUT) {
            player.getActivePotionEffects().stream()
                    .filter(effect -> effect.getType().equals(PotionEffectType.SPEED))
                    .filter(effect -> effect.getAmplifier() == 0 && effect.getDuration() > 1000000)
                    .forEach(effect -> player.removePotionEffect(effect.getType()));
        }
        if (oldClass == PlayerClass.GUARDIAN) {
            player.getActivePotionEffects().stream()
                    .filter(effect -> effect.getType().equals(PotionEffectType.REGENERATION))
                    .filter(effect -> effect.getAmplifier() == 0 && effect.getDuration() > 1000000)
                    .forEach(effect -> player.removePotionEffect(effect.getType()));
            player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20.0);
        }

        String displayName = meta.getDisplayName();
        if ("§bアクロバット".equals(displayName)) {
            playerClasses.put(uuid, PlayerClass.ACROBAT);
            player.sendMessage("§bアクロバットを選択しました");
        } else if ("§aスカウト".equals(displayName)) {
            playerClasses.put(uuid, PlayerClass.SCOUT);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false));
            ItemStack unbreakableGrapple = getUnbreakableGrappleItem();
            player.getInventory().addItem(unbreakableGrapple);
            player.sendMessage("§aスカウトを選択しました");
        } else if ("§eガーディアン".equals(displayName)) {
            playerClasses.put(uuid, PlayerClass.GUARDIAN);
            player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(24.0);
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 0, false, false));
            player.sendMessage("§eガーディアンを選択しました");
        }

        player.closeInventory();
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking()) return;
        if (playerClasses.get(player.getUniqueId()) != PlayerClass.ACROBAT) return;
        if (acrobatCooldown.contains(player.getUniqueId())) return;

        Vector jump = player.getLocation().getDirection().multiply(0.4).setY(1.0);
        player.setVelocity(jump);
        acrobatCooldown.add(player.getUniqueId());

        Bukkit.getScheduler().runTaskLater(this, () -> {
            acrobatCooldown.remove(player.getUniqueId());
        }, 20 * 3);
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;

        if (playerClasses.get(player.getUniqueId()) == PlayerClass.ACROBAT) {
            event.setCancelled(true);
        } else if (playerClasses.get(player.getUniqueId()) == PlayerClass.SCOUT) {
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (isGrapple(mainHand) || isGrapple(offHand)) {
                event.setDamage(event.getDamage() * 0.5);
            }
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (playerClasses.get(player.getUniqueId()) == PlayerClass.SCOUT) {
                combatTag.put(player.getUniqueId(), System.currentTimeMillis());
            }
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (playerClasses.get(uuid) != PlayerClass.SCOUT) return;
        if (!isGrapple(player.getInventory().getItemInMainHand())) return;
        if (isGrapple(player.getInventory().getItemInOffHand())) return;
        if (event.getState() != PlayerFishEvent.State.IN_GROUND) return;

        if (player.hasPotionEffect(PotionEffectType.SLOW) || player.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) return;

        Long lastDamage = combatTag.get(uuid);
        if (lastDamage != null && System.currentTimeMillis() - lastDamage < 5000) return;

        Location hook = event.getHook().getLocation();
        Location playerLoc = player.getLocation();
        double distance = playerLoc.distance(hook);

        double speed = Math.min(distance * 0.3, 20.0);

        Vector pull = hook.toVector().subtract(playerLoc.toVector());

        double yDiff = hook.getY() - playerLoc.getY();
        if (yDiff > 0) {
            pull.setY(yDiff * 0.2);
        } else {
            pull.setY(0.4);
        }

        double horizontalDistance = Math.sqrt(pull.getX() * pull.getX() + pull.getZ() * pull.getZ());
        if (horizontalDistance > 0) {
            pull.setX(pull.getX() / horizontalDistance * speed);
            pull.setZ(pull.getZ() / horizontalDistance * speed);
        }

        Vector currentVelocity = player.getVelocity();
        pull = pull.add(currentVelocity.multiply(0.2));

        player.setVelocity(pull);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerClasses.remove(uuid);
        acrobatCooldown.remove(uuid);
        combatTag.remove(uuid);
        Player player = event.getPlayer();
        player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20.0);
        player.getActivePotionEffects().stream()
                .filter(effect -> effect.getType().equals(PotionEffectType.REGENERATION))
                .filter(effect -> effect.getAmplifier() == 0 && effect.getDuration() > 1000000)
                .forEach(effect -> player.removePotionEffect(effect.getType()));
    }
}