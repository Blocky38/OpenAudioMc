package com.craftmend.openaudiomc.generic.networking.client.objects.player;

import com.craftmend.openaudiomc.OpenAudioMc;
import com.craftmend.openaudiomc.api.impl.event.events.MicrophoneMuteEvent;
import com.craftmend.openaudiomc.api.impl.event.events.MicrophoneUnmuteEvent;
import com.craftmend.openaudiomc.api.impl.event.events.PlayerEnterVoiceProximityEvent;
import com.craftmend.openaudiomc.api.impl.event.events.PlayerLeaveVoiceProximityEvent;
import com.craftmend.openaudiomc.api.impl.event.enums.VoiceEventCause;
import com.craftmend.openaudiomc.api.interfaces.AudioApi;
import com.craftmend.openaudiomc.generic.craftmend.CraftmendService;
import com.craftmend.openaudiomc.generic.networking.client.enums.RtcBlockReason;
import com.craftmend.openaudiomc.generic.networking.client.enums.RtcStateFlag;
import com.craftmend.openaudiomc.generic.networking.interfaces.NetworkingService;
import com.craftmend.openaudiomc.generic.networking.packets.client.voice.PacketClientDropVoiceStream;
import com.craftmend.openaudiomc.generic.networking.packets.client.voice.PacketClientSubscribeToVoice;
import com.craftmend.openaudiomc.generic.proxy.interfaces.UserHooks;
import com.craftmend.openaudiomc.generic.user.User;
import com.craftmend.openaudiomc.spigot.services.world.Vector3;
import com.craftmend.openaudiomc.generic.networking.payloads.client.voice.ClientVoiceDropPayload;
import com.craftmend.openaudiomc.generic.networking.payloads.client.voice.ClientVoiceSubscribePayload;
import com.craftmend.openaudiomc.generic.node.packets.ForceMuteMicrophonePacket;
import com.craftmend.openaudiomc.generic.platform.Platform;
import com.craftmend.openaudiomc.generic.voicechat.bus.VoiceApiConnection;
import com.craftmend.openaudiomc.spigot.modules.players.SpigotPlayerService;
import com.craftmend.openaudiomc.spigot.modules.players.enums.PlayerLocationFollower;
import com.craftmend.openaudiomc.spigot.modules.players.objects.SpigotConnection;
import lombok.Getter;
import org.bukkit.Location;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientRtcManager implements Serializable {

    @Getter private boolean isMicrophoneEnabled = false;
    @Getter private final transient Set<UUID> subscriptions = new HashSet<>();
    @Getter private final transient Set<ClientRtcLocationUpdate> locationUpdateQueue = ConcurrentHashMap.newKeySet();
    @Getter private final transient Set<RtcBlockReason> blockReasons = new HashSet<>();
    @Getter private final transient Set<RtcStateFlag> stateFlags = new HashSet<>();
    @Getter private final transient Set<UUID> recentPeerAdditions = new HashSet<>();
    @Getter private final transient Set<UUID> recentPeerRemovals = new HashSet<>();
    private transient Location lastPassedLocation = null;
    private final transient ClientConnection clientConnection;

    public ClientRtcManager(ClientConnection clientConnection) {
        this.clientConnection = clientConnection;

        this.clientConnection.onDisconnect(() -> {
            // go over all other clients, check if we might have a relations ship and break up if thats the case
            subscriptions.clear();
            this.isMicrophoneEnabled = false;
            makePeersDrop();
            locationUpdateQueue.clear();
        });
    }

    /**
     * Makes two users listen to one another
     *
     * @param peer Who I should become friends with
     * @return If I became friends
     */
    public boolean linkTo(ClientConnection peer) {
        if (!isReady())
            return false;

        if (!peer.getClientRtcManager().isReady())
            return false;

        if (subscriptions.contains(peer.getOwnerUUID()))
            return false;

        if (peer.getClientRtcManager().subscriptions.contains(clientConnection.getOwnerUUID()))
            return false;

        peer.getClientRtcManager().getSubscriptions().add(clientConnection.getOwnerUUID());
        subscriptions.add(peer.getOwnerUUID());

        peer.sendPacket(new PacketClientSubscribeToVoice(ClientVoiceSubscribePayload.fromClient(clientConnection, Vector3.from(peer))));
        clientConnection.sendPacket(new PacketClientSubscribeToVoice(ClientVoiceSubscribePayload.fromClient(peer, Vector3.from(clientConnection))));

        // throw events in both ways, since the two users are listening to eachother
        AudioApi.getInstance().getEventDriver().fire(new PlayerEnterVoiceProximityEvent(clientConnection, peer, VoiceEventCause.NORMAL));
        AudioApi.getInstance().getEventDriver().fire(new PlayerEnterVoiceProximityEvent(peer, clientConnection, VoiceEventCause.NORMAL));

        updateLocationWatcher();
        peer.getClientRtcManager().updateLocationWatcher();

        return true;
    }

    /**
     * Completely block/unblock speaking for a client.
     * This will forcefully block their microphone on the client and server side making them unable to speak
     * no matter what their microphone settings are
     *
     * @param allow If speaking is allowed
     */
    public void allowSpeaking(boolean allow) {
        // platform dependant
        if (OpenAudioMc.getInstance().getPlatform() == Platform.SPIGOT && OpenAudioMc.getInstance().getInvoker().isNodeServer()) {
            // forward to proxy
            User user = clientConnection.getUser();
            OpenAudioMc.resolveDependency(UserHooks.class).sendPacket(user, new ForceMuteMicrophonePacket(clientConnection.getOwnerUUID(), allow));
            return;
        }
        VoiceApiConnection voiceService = OpenAudioMc.getService(CraftmendService.class).getVoiceApiConnection();

        if (allow) {
            voiceService.forceMute(clientConnection);
        } else {
            voiceService.forceUnmute(clientConnection);
        }
    }

    public void makePeersDrop() {
        for (ClientConnection peer : OpenAudioMc.getService(NetworkingService.class).getClients()) {
            if (peer.getOwnerUUID() == clientConnection.getOwnerUUID())
                continue;

            if (peer.getClientRtcManager().subscriptions.contains(clientConnection.getOwnerUUID())) {
                // send unsub packet
                peer.getClientRtcManager().subscriptions.remove(clientConnection.getOwnerUUID());
                peer.getClientRtcManager().updateLocationWatcher();
                peer.sendPacket(new PacketClientDropVoiceStream(new ClientVoiceDropPayload(clientConnection.getStreamKey())));

                AudioApi.getInstance().getEventDriver().fire(new PlayerLeaveVoiceProximityEvent(clientConnection, peer, VoiceEventCause.NORMAL));
            }
        }
    }

    public void onLocationTick(Location location) {
        if (this.isReady() && this.isMicrophoneEnabled() && this.blockReasons.isEmpty()) {
            this.forceUpdateLocation(location);
        } else {
            lastPassedLocation = location;
        }
    }

    public void forceUpdateLocation(Location location) {
        for (ClientConnection peer : OpenAudioMc.getService(NetworkingService.class).getClients()) {
            if (peer.getOwnerUUID() == clientConnection.getOwnerUUID())
                continue;

            if (peer.getClientRtcManager().subscriptions.contains(clientConnection.getOwnerUUID())) {
                peer.getClientRtcManager().locationUpdateQueue.add(
                        ClientRtcLocationUpdate
                                .fromClientWithLocation(clientConnection, location, Vector3.from(peer))
                );
            }
        }
    }

    public void updateLocationWatcher() {
        if (OpenAudioMc.getInstance().getPlatform() == Platform.SPIGOT) {
            SpigotConnection spigotConnection = OpenAudioMc.getService(SpigotPlayerService.class).getClient(clientConnection.getOwnerUUID());
            if (subscriptions.isEmpty()) {
                spigotConnection.getLocationFollowers().remove(PlayerLocationFollower.PROXIMITY_VOICE_CHAT);
            } else {
                spigotConnection.getLocationFollowers().add(PlayerLocationFollower.PROXIMITY_VOICE_CHAT);
            }
        }
    }

    public boolean isReady() {
        return clientConnection.isConnected() && clientConnection.isConnectedToRtc();
    }

    public void setMicrophoneEnabled(boolean state) {
        if (!this.isMicrophoneEnabled && state) {
            if (this.lastPassedLocation != null) {
                forceUpdateLocation(lastPassedLocation);
            }
        }

        this.isMicrophoneEnabled = state;

        if (!this.isReady()) return;

        if (state) {
            AudioApi.getInstance().getEventDriver().fire(new MicrophoneUnmuteEvent(clientConnection));
        } else {
            AudioApi.getInstance().getEventDriver().fire(new MicrophoneMuteEvent(clientConnection));
        }
    }
}
