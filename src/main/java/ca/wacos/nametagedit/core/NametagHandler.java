package ca.wacos.nametagedit.core;

import ca.wacos.nametagedit.Messages;
import ca.wacos.nametagedit.NametagAPI;
import ca.wacos.nametagedit.NametagChangeEvent.NametagChangeReason;
import ca.wacos.nametagedit.NametagCommand;
import ca.wacos.nametagedit.NametagEdit;
import ca.wacos.nametagedit.data.GroupData;
import ca.wacos.nametagedit.data.PlayerData;
import ca.wacos.nametagedit.tasks.SQLDataTask;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * This class loads all group/player data, and applies the tags during
 * reloads/individually
 *
 * @author sgtcaze
 */
public class NametagHandler {

    private final NametagEdit plugin = NametagEdit.getInstance();

    private final boolean useDatabase;

    private final boolean tabListDisabled;

    // Stores all group names in order
    private List<String> allGroups = new LinkedList<>();

    // Stores all group names to permissions/prefix/suffix
    private HashMap<String, GroupData> groupData = new HashMap<>();

    // Stores all player names to prefix/suffix
    private HashMap<String, PlayerData> playerData = new HashMap<>();

    public NametagHandler() {
        this.useDatabase = plugin.getConfig().getBoolean("MySQL.Enabled");
        this.tabListDisabled = plugin.getConfig().getBoolean("TabListDisabled");
    }

    public boolean usingDatabase() {
        return useDatabase;
    }

    public List<String> getAllGroups() {
        return allGroups;
    }

    public HashMap<String, GroupData> getGroupData() {
        return groupData;
    }

    public HashMap<String, PlayerData> getPlayerData() {
        return playerData;
    }

    public void setGroupDataMap(HashMap<String, GroupData> map) {
        this.groupData = map;
    }

    public void setPlayerDataMap(HashMap<String, PlayerData> map) {
        this.playerData = map;
    }

    // Reloads files, and reapplies tags
    public void reload(CommandSender sender, boolean fromFile) {
        if (usingDatabase()) {
            new SQLDataTask().runTaskAsynchronously(plugin);
        } else {
            if (fromFile) {
                plugin.reloadConfig();
                plugin.getFileUtils().loadFiles();
            } else {
                plugin.saveConfig();
                saveFileData();
            }

            if (plugin.getConfig().getBoolean("Chat.Enabled")) {
                if (plugin.getChatListener() == null) {
                    plugin.registerChatListener();
                }
            } else {
                plugin.unregisterChatListener();
            }

            loadFromFile();

            applyTags();
        }

        Messages.RELOADED_DATA.send(sender);
    }

    // Workaround for the deprecated getOnlinePlayers()
    public List<Player> getOnline() {
        List<Player> list = new ArrayList<>();

        for (World world : Bukkit.getWorlds()) {
            list.addAll(world.getPlayers());
        }
        return Collections.unmodifiableList(list);
    }

    // Format input string
    private String format(String input) {
        return NametagAPI.trim(ChatColor.translateAlternateColorCodes('&', input));
    }

    private void setBlankTag(Player p) {
        String str = "§f" + p.getName();
        String tab = "";
        for (int t = 0; t < str.length() && t < 16; t++) {
            tab += str.charAt(t);
        }
        p.setPlayerListName(tab);
    }

    // Saves all player and group data
    public void saveFileData() {
        YamlConfiguration playersFile = plugin.getFileUtils().getPlayersFile();
        YamlConfiguration groupsFile = plugin.getFileUtils().getGroupsFile();

        groupsFile.set("Order", allGroups);

        for (PlayerData data : playerData.values()) {
            String uuid = data.getUUID();
            String name = data.getName();
            playersFile.set("Players." + uuid + ".Name", name);
            playersFile.set("Players." + uuid + ".Prefix", data.getPrefix());
            playersFile.set("Players." + uuid + ".Suffix", data.getSuffix());
        }

        for (GroupData data : groupData.values()) {
            String name = data.getName();
            groupsFile.set("Groups." + name + ".Permission", data.getPermission());
            groupsFile.set("Groups." + name + ".Prefix", data.getPrefix());
            groupsFile.set("Groups." + name + ".Suffix", data.getSuffix());
        }

        plugin.getFileUtils().saveAllFiles();
    }

    // Loads all player and group data (file)
    public void loadFromFile() {
        YamlConfiguration playersFile = plugin.getFileUtils().getPlayersFile();
        YamlConfiguration groupsFile = plugin.getFileUtils().getGroupsFile();

        groupData.clear();
        playerData.clear();

        allGroups.clear();
        allGroups = groupsFile.getStringList("Order");

        for (String s : allGroups) {
            GroupData data = new GroupData();
            data.setGroupName(s);
            data.setPermission(groupsFile.getString("Groups." + s + ".Permission"));
            data.setPrefix(groupsFile.getString("Groups." + s + ".Prefix", ""));
            data.setSuffix(groupsFile.getString("Groups." + s + ".Suffix", ""));
            groupData.put(s, data);
        }

        for (String s : playersFile.getConfigurationSection("Players").getKeys(false)) {
            PlayerData data = new PlayerData();
            data.setName(playersFile.getString("Players." + s + ".Name"));
            data.setUUID(s);
            data.setPrefix(playersFile.getString("Players." + s + ".Prefix", ""));
            data.setSuffix(playersFile.getString("Players." + s + ".Suffix", ""));
            playerData.put(s, data);
        }
    }

    // Applies tags to online players (for /reload, and /ne reload)
    public void applyTags() {
        for (Player p : getOnline()) {
            if (p == null) {
                continue;
            }

            NametagManager.clear(p.getName());

            String uuid = p.getUniqueId().toString();

            if (playerData.containsKey(uuid)) {
                PlayerData data = playerData.get(uuid);
                NametagManager.overlap(p.getName(), format(data.getPrefix()), format(data.getSuffix()));
            } else {
                Permission perm = null;

                for (String s : allGroups) {
                    GroupData data = groupData.get(s);

                    perm = new Permission(data.getPermission(), PermissionDefault.FALSE);

                    if (p.hasPermission(perm)) {
                        NametagCommand.setNametagSoft(p.getName(), format(data.getPrefix()), format(data.getSuffix()), NametagChangeReason.GROUP_NODE);
                        break;
                    }
                }
            }

            if (tabListDisabled) {
                setBlankTag(p);
            }
        }
    }

    // Applies tags to a specific player
    public void applyTagToPlayer(Player p) {
        String uuid = p.getUniqueId().toString();

        NametagManager.clear(p.getName());

        if (playerData.containsKey(uuid)) {
            PlayerData data = playerData.get(uuid);
            NametagManager.overlap(p.getName(), format(data.getPrefix()), format(data.getSuffix()));
        } else {
            Permission perm = null;

            for (String s : allGroups) {
                GroupData data = groupData.get(s);

                perm = new Permission(data.getPermission(), PermissionDefault.FALSE);

                if (p.hasPermission(perm)) {
                    NametagCommand.setNametagSoft(p.getName(), format(data.getPrefix()), format(data.getSuffix()), NametagChangeReason.GROUP_NODE);
                    break;
                }
            }
        }

        if (tabListDisabled) {
            setBlankTag(p);
        }
    }
}
