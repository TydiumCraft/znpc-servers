/*
 *
 * ZNServersNPC
 * Copyright (C) 2019 Gaston Gonzalez (ZNetwork)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */
package ak.znetwork.znpcservers;

import ak.znetwork.znpcservers.cache.ClazzCache;
import ak.znetwork.znpcservers.commands.list.*;
import ak.znetwork.znpcservers.configuration.Configuration;
import ak.znetwork.znpcservers.hologram.Hologram;
import ak.znetwork.znpcservers.listeners.PlayerListeners;
import ak.znetwork.znpcservers.manager.CommandsManager;
import ak.znetwork.znpcservers.manager.NPCManager;
import ak.znetwork.znpcservers.manager.tasks.NPCTask;
import ak.znetwork.znpcservers.netty.PlayerNetty;
import ak.znetwork.znpcservers.npc.NPC;
import ak.znetwork.znpcservers.npc.enums.NPCAction;
import ak.znetwork.znpcservers.utils.JSONUtils;
import ak.znetwork.znpcservers.utils.LocationUtils;
import ak.znetwork.znpcservers.utils.MetricsLite;
import ak.znetwork.znpcservers.utils.Utils;
import com.mysql.fabric.Server;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.*;

public class ServersNPC extends JavaPlugin {

    protected Configuration data;
    protected Configuration messages;

    protected CommandsManager commandsManager;
    protected NPCManager npcManager;

    protected LinkedHashSet<PlayerNetty> playerNetties;

    protected boolean placeHolderSupport;

    @Override
    public void onEnable() {
        placeHolderSupport = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        playerNetties = new LinkedHashSet<>();

        npcManager = new NPCManager();

        this.data = new Configuration(this , "data");
        this.messages = new Configuration(this , "messages");

        commandsManager = new CommandsManager("znpcs", this);
        commandsManager.addCommands(new DefaultCommand(this) , new CreateCommand(this) , new DeleteCommand(this) , new ListCommand(this), new ActionCommand(this) , new ToggleCommand(this) , new MoveCommand(this) , new EquipCommand(this) , new LinesCommand(this) , new SkinCommand(this));

        int pluginId = 8054;
        new MetricsLite(this, pluginId);

        // Load reflection cache
        try { ClazzCache.load();} catch (NoSuchMethodException | ClassNotFoundException e) {e.printStackTrace();}

        // Check if data contains any npc
        if (this.data.getConfig().contains("znpcs")) {
            int size = this.data.getConfig().getConfigurationSection("znpcs").getKeys(false).size();

            // Load all npc from config
            new BukkitRunnable() {
                @Override
                public void run() {
                    System.out.println("Loading " + size + " npcs...");

                    long startMs = System.currentTimeMillis();
                    for (final String keys : ServersNPC.this.data.getConfig().getConfigurationSection("znpcs").getKeys(false)) {
                        final Location location = LocationUtils.getLocationString(ServersNPC.this.data.getConfig().getString("znpcs." + keys + ".location"));

                        final String[] strings = ServersNPC.this.data.getConfig().getString("znpcs." + keys + ".lines").split(":");

                        final NPC npc = new NPC(ServersNPC.this , Integer.parseInt(keys) , ServersNPC.this.data.getConfig().getString("znpcs." + keys + ".skin").split(":")[0] , ServersNPC.this.data.getConfig().getString("znpcs." + keys + ".skin").split(":")[1] , location , NPCAction.fromString(ServersNPC.this.data.getConfig().getString("znpcs." + keys + ".type")) , new Hologram(ServersNPC.this , location , strings));

                        npc.setNpcAction(NPCAction.fromString(ServersNPC.this.data.getConfig().getString("znpcs." + keys + ".type")));
                        npc.setAction(ServersNPC.this.data.getConfig().contains("znpcs." + keys + ".actions") ? ServersNPC.this.data.getConfig().getString("znpcs." + keys + ".actions").split(":"): new String[0]);
                        npc.setHasToggleHolo(ServersNPC.this.data.getConfig().getBoolean("znpcs." + keys + ".toggle.holo" , true));
                        npc.setHasLookAt(ServersNPC.this.data.getConfig().getBoolean("znpcs." + keys + ".toggle.look" , false));
                        npc.setHasToggleName(ServersNPC.this.data.getConfig().getBoolean("znpcs." + keys + ".toggle.name" , true));
                        npc.setHasMirror(ServersNPC.this.data.getConfig().getBoolean("znpcs." + keys + ".toggle.mirror" , false));
                        npc.setHasGlow(ServersNPC.this.data.getConfig().getBoolean("znpcs." + keys + ".toggle.glow.has" , false));
                        if (npc.isHasGlow()) npc.toggleGlow(null , ServersNPC.this.data.getConfig().getString("znpcs." + keys + ".toggle.glow.color", "WHITE") , false);

                        for (NPC.NPCItemSlot npcItemSlot : NPC.NPCItemSlot.values()) npc.equip(null , npcItemSlot , Material.getMaterial(ServersNPC.this.data.getConfig().getString("znpcs." + keys + ".equip." + npcItemSlot.name().toLowerCase() , "AIR")));
                        npcManager.getNpcs().add(npc);
                    }

                    System.out.println("(Loaded " + size + " npcs in " +  NumberFormat.getInstance().format(System.currentTimeMillis() - startMs) + "ms)");

                    // Setup netty again for online players
                    Bukkit.getOnlinePlayers().forEach(ServersNPC.this::setupNetty);
                }
            }.runTaskLater(this , 20L * 3);
        }

        // Init task for all npc
        new NPCTask(this);

        new PlayerListeners(this);
    }

    @Override
    public void onDisable() {
        npcManager.getNpcs().forEach(npc -> npc.getViewers().forEach(uuid -> {
            npc.delete(Bukkit.getPlayer(uuid) , false);
        }));

        Bukkit.getOnlinePlayers().forEach(o -> getPlayerNetties().stream().filter(playerNetty -> playerNetty.getUuid() == o.getUniqueId()).findFirst().ifPresent(playerNetty -> playerNetty.ejectNetty(o)));

        // Save values on config (???)
        long startMs = System.currentTimeMillis();

        System.out.println("Saving " + getNpcManager().getNpcs().size() + " npcs...");
        for (final NPC npc : getNpcManager().getNpcs()) {
            this.data.getConfig().set("znpcs." + npc.getId() + ".location" , LocationUtils.getStringLocation(npc.getLocation()));
            this.data.getConfig().set("znpcs." + npc.getId() + ".type" , npc.getNpcAction().name());
            this.data.getConfig().set("znpcs." + npc.getId() + ".lines" , npc.getHologram().getLinesFormated());
            if (npc.getActions() != null && npc.getActions().length > 0) this.data.getConfig().set("znpcs." + npc.getId() + ".actions" , String.join(":" , npc.getActions()));
            this.data.getConfig().set("znpcs." + npc.getId() + ".toggle.holo" , npc.isHasToggleHolo());
            this.data.getConfig().set("znpcs." + npc.getId() + ".toggle.look" , npc.isHasLookAt());
            this.data.getConfig().set("znpcs." + npc.getId() + ".toggle.name" , npc.isHasToggleName());
            this.data.getConfig().set("znpcs." + npc.getId() + ".toggle.mirror" , npc.isHasMirror());
            this.data.getConfig().set("znpcs." + npc.getId() + ".toggle.glow.has" , npc.isHasGlow());
            this.data.getConfig().set("znpcs." + npc.getId() + ".toggle.glow.color" , (npc.getGlowName() != null ? npc.getGlowName().toUpperCase() : "WHITE"));
            this.data.getConfig().set("znpcs." + npc.getId() + ".skin" , npc.getSkin() + ":" + npc.getSignature());

            for (Map.Entry<NPC.NPCItemSlot, Material> npcItemSlot : npc.getNpcItemSlotMaterialHashMap().entrySet()) {
                this.data.getConfig().set("znpcs." + npc.getId() + ".equip." + npcItemSlot.getKey().name().toLowerCase() , npcItemSlot.getValue().name().toUpperCase());
            }
        }
        this.data.save();

        System.out.println("(Saved " +  getNpcManager().getNpcs().size() + "npcs in " +  NumberFormat.getInstance().format(System.currentTimeMillis() - startMs) + "ms)");
    }

    public Configuration getMessages() {
        return messages;
    }

    public CommandsManager getCommandsManager() {
        return commandsManager;
    }

    public NPCManager getNpcManager() {
        return npcManager;
    }

    public LinkedHashSet<PlayerNetty> getPlayerNetties() {
        return playerNetties;
    }

    public boolean isPlaceHolderSupport() {
        return placeHolderSupport;
    }

    /**
     * Setup netty for player
     *
     * @param player receiver
     */
    public void setupNetty(final Player player) {
        final PlayerNetty playerNetty = new PlayerNetty(this , player);

        playerNetty.injectNetty(player);
        this.getPlayerNetties().add(playerNetty);
    }

    /**
     * Creation of a new npc
     *
     * @param id the npc id
     * @param player the creator of the npc
     * @return val
     */
    public final boolean createNPC(int id , final Player player , final String skin, final String holo_lines) {
        final String[] skinFetcher = JSONUtils.getFromUrl("https://sessionserver.mojang.com/session/minecraft/profile/" + JSONUtils.fetchUUID(skin) + "?unsigned=false");

        final String[] strings = new String[holo_lines.split(":").length];

        for (int i=0; i <= strings.length - 1; i++)
            strings[i] = holo_lines.split(":")[i];

        final Location fixed = player.getLocation().clone().subtract(0.5 , 0 , 0.5);

        this.getNpcManager().getNpcs().add(new NPC(this , id , skinFetcher[0] , skinFetcher[1] , fixed,NPCAction.CMD, new Hologram(this ,fixed, strings)));

        this.data.getConfig().set("znpcs." + id + ".skin" , skinFetcher[0] + ":" + skinFetcher[1]);
        this.data.getConfig().set("znpcs." + id + ".location" , LocationUtils.getStringLocation(player.getLocation()));
        this.data.getConfig().set("znpcs." + id + ".lines" , holo_lines);
        this.data.save();

        player.sendMessage(Utils.tocolor(getMessages().getConfig().getString("success")));
        return true;
    }

    /**
     * Delete a npc
     *
     * @param id the npc id
     * @return val
     */
    public final boolean deleteNPC(int id) {
        final NPC npc = this.npcManager.getNpcs().stream().filter(npc1 -> npc1.getId() == id).findFirst().orElse(null);

        // Try find
        if (npc == null) {
            return false;
        }

        getNpcManager().getNpcs().remove(npc);

        final Iterator<UUID> it = npc.getViewers().iterator();

        while (it.hasNext())  {
            final UUID uuid = it.next();

            npc.delete(Bukkit.getPlayer(uuid) , false);

            it.remove();
        }

        this.data.getConfig().set("znpcs." + id , null);
        this.data.save();
        return true;
    }

    /**
     * Send player to server bungee
     *
     * @param p receiver
     * @param server target
     */
    public void sendPlayerToServer(Player p, String server){
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);

        try {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException e) {
            e.printStackTrace();
        }

        p.sendPluginMessage(this, "BungeeCord", b.toByteArray());
    }
}
