package com.craftmend.tests;

import com.craftmend.openaudiomc.generic.networking.abstracts.AbstractPacket;
import com.craftmend.openaudiomc.generic.networking.abstracts.AbstractPacketPayload;
import com.craftmend.openaudiomc.generic.redis.packets.interfaces.OARedisPacket;
import com.craftmend.openaudiomc.generic.utils.data.GsonFactory;
import com.craftmend.utils.ClassScanner;
import com.google.gson.Gson;
import lombok.SneakyThrows;
import org.junit.Assert;
import org.junit.Test;

import java.io.Serializable;

public class SerializerTest {

    @SneakyThrows
    @Test
    public void testEvents() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        Class[] oaClasses = ClassScanner.getClasses("com.craftmend.openaudiomc.generic", null);

        // test serialization for packets that extend or implement one of these
        Class[] relatedTo = new Class[]{
                AbstractPacket.class,
                AbstractPacketPayload.class,
                OARedisPacket.class,
                Serializable.class
        };

        Gson gson = GsonFactory.create();

        for (Class oaClass : oaClasses) {
            boolean isSubType = false;

            for (Class aClass : relatedTo) {
                for (Class anInterface : oaClass.getInterfaces()) {
                    if (anInterface == aClass) {
                        isSubType = true;
                    }
                }
                Class su = oaClass.getSuperclass();
                if (su == aClass) {
                    isSubType = true;
                }
            }

            if (oaClass.getName().contains("Adapter")) {
                isSubType = false;
            }

            if (isSubType) {
                try {
                    boolean isOne = false;

                    for (Class aClass : relatedTo) {
                        if (oaClass.isAssignableFrom(aClass)) {
                            isOne = true;
                        }
                    }

                    if (!isOne) continue;
                    Object result = gson.fromJson("{}", oaClass);
                    Assert.assertNotNull("The packet can't be null", result);

                    if (result instanceof OARedisPacket) {
                        OARedisPacket packet = (OARedisPacket) result;
                        String reSerialized = packet.serialize();
                        Assert.assertNotNull(reSerialized);
                        Assert.assertEquals("{}", reSerialized);
                    }

                    if (result instanceof AbstractPacketPayload) {
                        // re-serialize
                        String json = gson.toJson(result);
                        Assert.assertNotNull(json);
                    }

                    if (result instanceof AbstractPacket) {
                        // re-serialize
                        String json = gson.toJson(result);
                        Assert.assertNotNull(json);
                    }
                } catch (Exception e) {
                    System.out.println("Failed class " + oaClass.getName());
                    e.printStackTrace();
                    throw e;
                }
            }
        }
    }


}
