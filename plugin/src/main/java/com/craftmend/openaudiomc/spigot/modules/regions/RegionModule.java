package com.craftmend.openaudiomc.spigot.modules.regions;

import com.craftmend.openaudiomc.OpenAudioMc;
import com.craftmend.openaudiomc.generic.media.MediaService;
import com.craftmend.openaudiomc.generic.storage.interfaces.Configuration;
import com.craftmend.openaudiomc.generic.logging.OpenAudioLogger;
import com.craftmend.openaudiomc.generic.storage.enums.StorageLocation;
import com.craftmend.openaudiomc.spigot.modules.players.SpigotPlayerService;
import com.craftmend.openaudiomc.spigot.modules.players.objects.SpigotConnection;
import com.craftmend.openaudiomc.spigot.modules.regions.adapters.LegacyRegionAdapter;
import com.craftmend.openaudiomc.spigot.modules.regions.adapters.ModernRegionAdapter;
import com.craftmend.openaudiomc.spigot.modules.regions.interfaces.AbstractRegionAdapter;
import com.craftmend.openaudiomc.spigot.modules.regions.objects.RegionMedia;
import com.craftmend.openaudiomc.spigot.modules.regions.objects.RegionProperties;
import com.craftmend.openaudiomc.spigot.services.server.ServerService;
import com.craftmend.openaudiomc.spigot.services.server.enums.ServerVersion;

import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class RegionModule {

    @Getter
    private Map<String, RegionProperties> regionPropertiesMap = new HashMap<>();
    private Map<String, RegionMedia> regionMediaMap = new HashMap<>();
    @Getter private AbstractRegionAdapter regionAdapter;

    public RegionModule(@Nullable AbstractRegionAdapter customAdapter) {
        OpenAudioLogger.toConsole("Turns out you have WorldGuard installed! enabling regions and the region tasks..");

        if (customAdapter == null) {
            if (OpenAudioMc.getService(ServerService.class).getVersion() == ServerVersion.MODERN) {
                OpenAudioLogger.toConsole("Enabling the newer 1.13 regions");
                regionAdapter = new ModernRegionAdapter(this);
            } else {
                OpenAudioLogger.toConsole("Unknown version. Falling back to the 1.8 to 1.12 region implementation.");
                regionAdapter = new LegacyRegionAdapter(this);
            }
        } else {
            this.regionAdapter = customAdapter;
            this.regionAdapter.boot(this);
        }

        //validate detection
        if (OpenAudioMc.getService(ServerService.class).getVersion() == ServerVersion.LEGACY) {
            try {
                Class.forName("com.sk89q.worldguard.bukkit.WGBukkit");
            } catch (ClassNotFoundException e) {
                OpenAudioLogger.toConsole("Wrong world guard detection! re-switching to 1.13");
                regionAdapter = new ModernRegionAdapter(this);
            }
        }

        Configuration config = OpenAudioMc.getInstance().getConfiguration();

        //load config
        for (String region : config.getStringSet("regions", StorageLocation.DATA_FILE)) {
            // before we actually add it, we should check if the WG region still exists, to lesser load
            if (regionAdapter.doesRegionExist(region.toLowerCase())) {
                String source = config.getStringFromPath("regions." + region, StorageLocation.DATA_FILE);

                int volume = config.getIntFromPath("regionsvolume." + region, StorageLocation.DATA_FILE);
                if (volume < 5) {
                    volume = 100;
                }

                int fadeTimeMs = config.getIntFromPath("regionsfadetime." + region, StorageLocation.DATA_FILE);
                if (fadeTimeMs == 0) {
                    fadeTimeMs = 1000;
                }

                // is voicechat enabled? but we'll need to check if the region even has this data, since it might be considered legacy
                boolean isVcEnabled = true;

                // only check paths on modern servers, 1.8 doesn't support contains lookups
                if (OpenAudioMc.getService(ServerService.class).getVersion() == ServerVersion.MODERN) {
                    if (config.isPathValid("regionmeta." + region + "allow-vc", StorageLocation.DATA_FILE)) {
                        isVcEnabled = Boolean.valueOf(config.getStringFromPath("regionmeta." + region + "allow-vc", StorageLocation.DATA_FILE));
                    }
                }

                RegionProperties properties = new RegionProperties(source, volume, fadeTimeMs, isVcEnabled);
                registerRegion(region, properties);
            }
        }

        OpenAudioMc.getService(MediaService.class).getResetTriggers().add(() -> {
            regionMediaMap.clear();
        });

        this.regionAdapter.postLoad();
    }

    public void registerRegion(String id, RegionProperties propperties) {
        regionPropertiesMap.put(id, propperties);
    }

    public void removeRegion(String id) {
        regionPropertiesMap.remove(id);
    }

    public void forceUpdateRegions() {
        for (SpigotConnection client : OpenAudioMc.getService(SpigotPlayerService.class).getClients()) {
            if (client.getRegionHandler() != null) client.getRegionHandler().tick();
        }
    }

    public RegionMedia getRegionMedia(String source, int volume, int fadeTimeMs) {
        if (regionMediaMap.containsKey(source)) return regionMediaMap.get(source);
        RegionMedia regionMedia = new RegionMedia(source, volume, fadeTimeMs);
        regionMediaMap.put(source, regionMedia);
        return regionMedia;
    }

    public void removeRegionMedia(String id, String source) {
        regionMediaMap.remove(source);
        regionPropertiesMap.remove(id);
    }
}
