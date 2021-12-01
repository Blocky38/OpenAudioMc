package com.craftmend.openaudiomc.generic.networking.io;

import com.craftmend.openaudiomc.OpenAudioMc;
import com.craftmend.openaudiomc.api.impl.event.ApiEventDriver;
import com.craftmend.openaudiomc.api.impl.event.events.StateChangeEvent;
import com.craftmend.openaudiomc.api.interfaces.AudioApi;
import com.craftmend.openaudiomc.generic.authentication.AuthenticationService;
import com.craftmend.openaudiomc.generic.authentication.objects.ServerKeySet;
import com.craftmend.openaudiomc.generic.craftmend.CraftmendService;
import com.craftmend.openaudiomc.generic.logging.OpenAudioLogger;
import com.craftmend.openaudiomc.generic.networking.certificate.CertificateHelper;
import com.craftmend.openaudiomc.generic.networking.abstracts.AbstractPacket;
import com.craftmend.openaudiomc.generic.networking.drivers.ClientDriver;
import com.craftmend.openaudiomc.generic.networking.drivers.NotificationDriver;
import com.craftmend.openaudiomc.generic.networking.drivers.SystemDriver;
import com.craftmend.openaudiomc.generic.platform.interfaces.TaskService;
import com.craftmend.openaudiomc.generic.state.StateService;
import com.craftmend.openaudiomc.generic.storage.enums.StorageKey;
import com.craftmend.openaudiomc.generic.networking.interfaces.Authenticatable;
import com.craftmend.openaudiomc.generic.networking.interfaces.SocketDriver;
import com.craftmend.openaudiomc.generic.networking.rest.RestRequest;
import com.craftmend.openaudiomc.generic.networking.rest.endpoints.RestEndpoint;
import com.craftmend.openaudiomc.generic.networking.rest.interfaces.ApiResponse;
import com.craftmend.openaudiomc.generic.networking.rest.responses.LoginResponse;
import com.craftmend.openaudiomc.generic.state.states.AssigningRelayState;
import com.craftmend.openaudiomc.generic.state.states.ConnectedState;
import com.craftmend.openaudiomc.generic.state.states.ConnectingState;
import com.craftmend.openaudiomc.generic.state.states.IdleState;

import io.socket.client.IO;
import io.socket.client.Socket;

import lombok.Getter;

import okhttp3.OkHttpClient;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class SocketIoConnector {

    private Socket socket;
    @Getter private RestRequest plusHandler;
    private RestRequest logoutHandler;
    private boolean registeredLogout = false;
    @Getter private UUID lastUsedRelay = UUID.randomUUID();
    private ServerKeySet keySet;

    private final SocketDriver[] drivers = new SocketDriver[]{
            new NotificationDriver(),
            new SystemDriver(),
            new ClientDriver(),
    };

    public SocketIoConnector(ServerKeySet keySet) {
        this.keySet = keySet;
    }

    public void setupConnection() {
        if (!OpenAudioMc.getService(StateService.class).getCurrentState().canConnect()) return;

        // update state
        OpenAudioMc.getService(StateService.class).setState(new AssigningRelayState());

        if (!registeredLogout) {
            plusHandler = new RestRequest(RestEndpoint.START_SESSION);
            logoutHandler = new RestRequest(RestEndpoint.END_SESSION);

            // listen for state events
            ApiEventDriver driver = AudioApi.getInstance().getEventDriver();
            if (driver.isSupported(StateChangeEvent.class)) {
                driver.on(StateChangeEvent.class)
                        .setHandler(event -> {
                            if (event.getOldState() instanceof ConnectedState) {
                                logoutHandler.executeAsync();
                            }
                        });
            }

            registeredLogout = true;
        }

        ProxySelector.setDefault(new NullProxySelector());

        OkHttpClient okHttpClient = CertificateHelper.ignore(new OkHttpClient.Builder().proxySelector(new NullProxySelector())).build();

        IO.Options opts = new IO.Options();
        opts.callFactory = okHttpClient;
        opts.reconnection = false;
        opts.webSocketFactory = okHttpClient;

        // authentication headers
        opts.query = String.format(
                "type=server&private=%s&public=%s",
                keySet.getPrivateKey().getValue(),
                keySet.getPublicKey().getValue()
        );

        // request a relay server
        if (StorageKey.DEBUG_LOG_STATE_CHANGES.getBoolean()) {
            OpenAudioLogger.toConsole("Requesting relay..");
        }

        // schedule timeout check
        OpenAudioMc.resolveDependency(TaskService.class).schduleSyncDelayedTask(() -> {
            if (OpenAudioMc.getService(StateService.class).getCurrentState() instanceof AssigningRelayState) {
                OpenAudioLogger.toConsole("Connecting timed out.");
                OpenAudioMc.getService(StateService.class).setState(new IdleState("Connecting to the relay timed out"));
            }
        }, 20 * 35);

        Instant request = Instant.now();

        ApiResponse response = plusHandler.executeInThread();

        if (!response.getErrors().isEmpty()) {
            OpenAudioMc.getService(StateService.class).setState(new IdleState("Failed to do the initial handshake. Error: " + response.getErrors().get(0).getCode()));
            OpenAudioLogger.toConsole("Failed to get relay host.");
            OpenAudioLogger.toConsole(" - message: " + response.getErrors().get(0).getMessage());
            OpenAudioLogger.toConsole(" - code: " + response.getErrors().get(0).getCode());
            try {
                throw new IOException("Failed to get relay! see console for error information");
            } catch (IOException e) {
                OpenAudioLogger.handleException(e);
                e.printStackTrace();
            }
            return;
        }

        LoginResponse loginResponse = response.getResponse(LoginResponse.class);
        Instant finish = Instant.now();
        if (StorageKey.DEBUG_LOG_STATE_CHANGES.getBoolean()) {
            OpenAudioLogger.toConsole("Assigned relay: " + loginResponse.getAssignedOpenAudioServer().getSecureEndpoint() + " request took " + Duration.between(request, finish).toMillis() + "MS");
        }
        lastUsedRelay = loginResponse.getAssignedOpenAudioServer().getRelayId();

        // setup socketio connection
        try {
            socket = IO.socket(loginResponse.getAssignedOpenAudioServer().getInsecureEndpoint(), opts);
        } catch (URISyntaxException e) {
            OpenAudioLogger.handleException(e);
            e.printStackTrace();
        }

        // register state to be connecting
        OpenAudioMc.getService(StateService.class).setState(new ConnectingState());

        // clear session cache
        OpenAudioMc.getService(AuthenticationService.class).getDriver().initCache();

        // schedule timeout check
        OpenAudioMc.resolveDependency(TaskService.class).schduleSyncDelayedTask(() -> {
            if (OpenAudioMc.getService(StateService.class).getCurrentState() instanceof ConnectingState) {
                OpenAudioLogger.toConsole("Connecting timed out.");
                OpenAudioMc.getService(StateService.class).setState(new IdleState("Connecting to the relay timed out (socket)"));
            }
        }, 20 * 35);


        // register drivers
        for (SocketDriver driver : drivers) driver.boot(socket, this);
        socket.connect();
    }

    public void disconnect() {
        if (logoutHandler != null) {
            logoutHandler.executeAsync();
        }
        if (this.socket != null) {
            this.socket.disconnect();
        }
        OpenAudioMc.getService(StateService.class).setState(new IdleState());
        OpenAudioMc.getService(CraftmendService.class).getVoiceApiConnection().stop();
    }

    public void send(Authenticatable client, AbstractPacket packet) {
        // only send the packet if the client is online, valid and the plugin is connected
        if (client.isConnected() && OpenAudioMc.getService(StateService.class).getCurrentState().isConnected()) {
            packet.setClient(client.getOwner().getUniqueId());
            socket.emit("data", OpenAudioMc.getGson().toJson(packet));
        }
    }
}
