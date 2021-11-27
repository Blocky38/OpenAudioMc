package com.craftmend.openaudiomc.generic.networking.client.objects.player;

import com.craftmend.openaudiomc.OpenAudioMc;
import com.craftmend.openaudiomc.generic.authentication.AuthenticationService;
import lombok.NoArgsConstructor;

import java.util.Base64;
import java.util.UUID;

@NoArgsConstructor
class TokenFactory {

    /**
     * generate a new token
     * this is usually only done on startup
     *
     * @param client the owner
     * @return token
     */
    PlayerSession build(ClientConnection client) {
        String key = UUID.randomUUID().toString().subSequence(0, 3).toString();

        String staticToken = client.getUser().getName() +
                ":" +
                client.getUser().getUniqueId().toString() +
                ":" +
                OpenAudioMc.getService(AuthenticationService.class).getServerKeySet().getPublicKey().getValue() +
                ":" +
                key;

        return new PlayerSession(client, key, new String(Base64.getEncoder().encode(staticToken.getBytes())));
    }

}
