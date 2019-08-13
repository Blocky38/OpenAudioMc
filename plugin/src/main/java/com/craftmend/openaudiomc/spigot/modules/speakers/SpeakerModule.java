package com.craftmend.openaudiomc.spigot.modules.speakers;

import com.craftmend.openaudiomc.OpenAudioMc;
import com.craftmend.openaudiomc.generic.interfaces.ConfigurationInterface;
import com.craftmend.openaudiomc.spigot.OpenAudioMcSpigot;
import com.craftmend.openaudiomc.generic.configuration.enums.StorageKey;
import com.craftmend.openaudiomc.generic.configuration.enums.StorageLocation;
import com.craftmend.openaudiomc.spigot.modules.speakers.objects.MappedLocation;
import com.craftmend.openaudiomc.spigot.services.server.enums.ServerVersion;
import com.craftmend.openaudiomc.spigot.modules.speakers.listeners.SpeakerCreateListener;
import com.craftmend.openaudiomc.spigot.modules.speakers.listeners.SpeakerDestroyListener;
import com.craftmend.openaudiomc.spigot.modules.speakers.objects.ApplicableSpeaker;
import com.craftmend.openaudiomc.spigot.modules.speakers.objects.Speaker;
import com.craftmend.openaudiomc.spigot.modules.speakers.objects.SpeakerMedia;

import lombok.Getter;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class SpeakerModule {

    @Getter
    private Map<MappedLocation, Speaker> speakerMap = new HashMap<>();
    private Map<String, SpeakerMedia> speakerMediaMap = new HashMap<>();
    private Material playerSkullItem;
    private ServerVersion version;

    public SpeakerModule(OpenAudioMcSpigot openAudioMcSpigot) {
        openAudioMcSpigot.getServer().getPluginManager().registerEvents(new SpeakerCreateListener(openAudioMcSpigot, this), openAudioMcSpigot);
        openAudioMcSpigot.getServer().getPluginManager().registerEvents(new SpeakerDestroyListener(OpenAudioMc.getInstance(), this), openAudioMcSpigot);

        version = openAudioMcSpigot.getServerService().getVersion();

        if (version == ServerVersion.MODERN) {
            System.out.println(OpenAudioMc.getLOG_PREFIX() + "Enabling the 1.13 speaker system");
            playerSkullItem = Material.PLAYER_HEAD;
        } else {
            System.out.println(OpenAudioMc.getLOG_PREFIX() + "Enabling the 1.12 speaker system");
            try {
                System.out.println(OpenAudioMc.getLOG_PREFIX() + "Hooking speakers attempt 1..");
                playerSkullItem = Material.valueOf("SKULL_ITEM");
            } catch (Exception e) {
                System.out.println(OpenAudioMc.getLOG_PREFIX() + "Failed hook speakers attempt 1..");
            }

            if (playerSkullItem == null) {
                System.out.println(OpenAudioMc.getLOG_PREFIX() + "Speakers failed to hook. Hooking to a block.");
                playerSkullItem = Material.JUKEBOX;
            }
        }

        ConfigurationInterface config = OpenAudioMc.getInstance().getConfigurationInterface();

        //load speakers
        for (String id : config.getStringSet("speakers", StorageLocation.DATA_FILE)) {

            String world = config.getStringFromPath("speakers." + id + ".world", StorageLocation.DATA_FILE);
            String media = config.getStringFromPath("speakers." + id + ".media", StorageLocation.DATA_FILE);
            int x = config.getIntFromPath("speakers." + id + ".x", StorageLocation.DATA_FILE);
            int y = config.getIntFromPath("speakers." + id + ".y", StorageLocation.DATA_FILE);
            int z = config.getIntFromPath("speakers." + id + ".z", StorageLocation.DATA_FILE);
            int radius = config.getInt(StorageKey.SETTINGS_SPEAKER_RANGE);

            if (world != null) {
                MappedLocation mappedLocation = new MappedLocation(x, y, z, world);
                Block blockAt = mappedLocation.getBlock();

                if (blockAt != null) {
                    registerSpeaker(mappedLocation, media, UUID.fromString(id), radius);
                } else {
                    System.out.println(OpenAudioMc.getLOG_PREFIX() + "Speaker " + id + " doesn't to seem be valid anymore, so it's not getting loaded.");
                }
            }
        }
    }

    public Collection<ApplicableSpeaker> getApplicableSpeakers(Location location) {
        List<Speaker> applicableSpeakers = new ArrayList<>(speakerMap.values());
        Map<String, ApplicableSpeaker> distanceMap = new HashMap<>();

        // filter all speakers from other worlds
        applicableSpeakers.removeIf(speaker -> !speaker.getLocation().getWorld()
                .equals(location.getWorld().getName()));

        // filter all speakers outside of radius
        applicableSpeakers.removeIf(speaker -> speaker.getLocation().toBukkit()
                .distance(location) > speaker.getRadius());

        // filter all speakers that are not actual speakers (crazy shit RIGHT HERE)
        applicableSpeakers.removeIf(speaker ->
                !isSpeakerSkull(speaker.getLocation().getBlock()));

        for (Speaker speaker : applicableSpeakers) {
            int distance = Math.toIntExact(Math.round(speaker.getLocation().toBukkit().distance(location)));

            if (distanceMap.get(speaker.getSource()) == null) {
                distanceMap.put(speaker.getSource(), new ApplicableSpeaker(distance, speaker));
            } else {
                if (distance < distanceMap.get(speaker.getSource()).getDistance()) {
                    distanceMap.put(speaker.getSource(), new ApplicableSpeaker(distance, speaker));
                }
            }
        }

        return distanceMap.values();
    }

    public void registerSpeaker(MappedLocation mappedLocation, String source, UUID uuid, int radius) {
        Speaker speaker = new Speaker(source, uuid, radius, mappedLocation);
        speakerMap.put(mappedLocation, speaker);
    }

    public Speaker getSpeaker(MappedLocation location) {
        return speakerMap.get(location);
    }

    public SpeakerMedia getMedia(String source) {
        if (speakerMediaMap.containsKey(source)) return speakerMediaMap.get(source);
        SpeakerMedia speakerMedia = new SpeakerMedia(source);
        speakerMediaMap.put(source, speakerMedia);
        return speakerMedia;
    }

    public void unlistSpeaker(MappedLocation location) {
        speakerMap.remove(location);
    }

    public ItemStack getSkull() {
        ItemStack skull = new ItemStack(playerSkullItem);
        skull.setDurability((short) 3);
        SkullMeta sm = (SkullMeta) skull.getItemMeta();
        sm.setOwner("OpenAudioMc");
        sm.setDisplayName(ChatColor.AQUA + "OpenAudioMc Speaker");
        sm.setLore(Arrays.asList("",
                ChatColor.AQUA + "Place me anywhere",
                ChatColor.AQUA + "in the world to place",
                ChatColor.AQUA + "a speaker for that area",
                ""));
        skull.setItemMeta(sm);
        return skull;
    }

    public Boolean isSpeakerSkull(Block block) {
        if (block.getState() instanceof Skull) {
            Skull skull = (Skull) block.getState();
            if (version == ServerVersion.MODERN) {

                try {
                    if (skull.getOwner() == null) return false;
                    return skull.getOwner().equals("OpenAudioMc");
                } catch (Exception e) {
                    // bukkit did remove the method! oh well
                }

                if (skull.getOwningPlayer() == null) return false;
                if (skull.getOwningPlayer().getName() == null) return false;
                return
                        skull.getOwningPlayer().getName().equals("OpenAudioMc")
                        ||
                        skull.getOwningPlayer().getUniqueId().toString().equals("c0db149e-d498-4a16-8e35-93d57577589f");
            } else {
                if (skull.getOwner() == null) return false;
                return skull.getOwner().equals("OpenAudioMc");
            }
        }
        return false;
    }

}