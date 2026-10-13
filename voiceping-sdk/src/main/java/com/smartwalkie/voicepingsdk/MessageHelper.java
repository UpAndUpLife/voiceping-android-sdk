package com.smartwalkie.voicepingsdk;

import com.smartwalkie.voicepingsdk.model.ChannelType;
import com.smartwalkie.voicepingsdk.model.Message;
import com.smartwalkie.voicepingsdk.model.MessageType;

import org.json.JSONArray;
import org.json.JSONObject;
import org.msgpack.MessagePack;
import org.msgpack.packer.Packer;
import org.msgpack.type.ValueType;
import org.msgpack.unpacker.MessagePackUnpacker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

class MessageHelper {
    static final String SITE_PREFIX = "site:";

    private static final MessagePack mMessagePack = new MessagePack();

    /**
     * Frame layout: [channelType, messageType, fromId, toId, (messageId,) payload].
     * Only STOP_ACK carries a messageId; it lands in ackIds.
     */
    public static Message unpackMessage(byte[] payload) {
        ByteArrayInputStream stream = new ByteArrayInputStream(payload);
        MessagePackUnpacker unpacker = (MessagePackUnpacker) mMessagePack.createUnpacker(stream);
        try {
            int length = unpacker.readArrayBegin();
            int channelType = unpacker.readInt();
            int messageType = unpacker.readInt();
            String senderId = readId(unpacker);
            String receiverId = readId(unpacker);

            Message message = new Message();
            message.setChannelType(channelType);
            message.setMessageType(messageType);
            message.setSenderId(senderId);
            message.setReceiverId(receiverId);

            if (length >= 6) {
                message.setAckIds(unpacker.readString());
            }
            if (messageType == MessageType.AUDIO) {
                message.setPayload(unpacker.readByteArray());
                message.addData(message.getPayload());
            } else if (messageType == MessageType.START_TALKING) {
                message.setDuration(unpacker.readLong());
            } else if (messageType == MessageType.STOP_TALKING) {
                // JSON {duration, message_id}
                message.setAckIds(unpacker.readString());
            } else if (messageType == MessageType.ACK_START_FAILED
                    || messageType == MessageType.PRESENCE
                    || messageType == MessageType.SITE_STATUS) {
                message.setContent(unpacker.readString());
            }
            // ACK_START, ACK_END, TEAM_CHANGED: nothing to read.
            stream.close();
            return message;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readId(MessagePackUnpacker unpacker) throws IOException {
        return unpacker.getNextType() == ValueType.INTEGER ?
                String.valueOf(unpacker.readLong()) : unpacker.readString();
    }

    public static Message createStartMessage(String senderId, String receiverId, int channelType, long duration) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Packer packer = mMessagePack.createPacker(out);
        try {
            packer.writeArrayBegin(5);
            packer.write(channelType);
            packer.write(MessageType.START_TALKING);
            packer.write(senderId);
            packer.write(receiverId);
            packer.write(duration);
            packer.writeArrayEnd(true);

            Message message = new Message();
            message.setChannelType(channelType);
            message.setMessageType(MessageType.START_TALKING);
            message.setSenderId(senderId);
            message.setReceiverId(receiverId);
            message.setDuration(duration);
            message.setPayload(out.toByteArray());
            return message;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Message createAudioMessage(String senderId, String receiverId, int channelType, byte[] payload, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Packer packer = mMessagePack.createPacker(out);
        try {
            packer.writeArrayBegin(5);
            packer.write(channelType);
            packer.write(MessageType.AUDIO);
            packer.write(senderId);
            packer.write(receiverId);
            packer.write(payload, 0, length);
            packer.writeArrayEnd(true);

            Message message = new Message();
            message.setChannelType(channelType);
            message.setMessageType(MessageType.AUDIO);
            message.setSenderId(senderId);
            message.setReceiverId(receiverId);
            message.addData(payload);
            message.setPayload(out.toByteArray());
            return message;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] createStopMessage(String senderId, String receiverId, int channelType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Packer packer = mMessagePack.createPacker(out);
        try {
            packer.writeArrayBegin(4);
            packer.write(channelType);
            packer.write(MessageType.STOP_TALKING);
            packer.write(senderId);
            packer.write(receiverId);
            packer.writeArrayEnd(true);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return out.toByteArray();
    }

    public static byte[] createSubscribeSiteMessage(String senderId, String siteId) {
        return createRouterFrame(MessageType.SUBSCRIBE, senderId, SITE_PREFIX + siteId, "");
    }

    public static byte[] createUnsubscribeSiteMessage(String senderId, String siteId) {
        return createRouterFrame(MessageType.UNSUBSCRIBE, senderId, SITE_PREFIX + siteId, "");
    }

    /** Replaces the whole people/duty subscription: {"ids":[...],"sites":{"<siteId>":"<dutyVersion>"}}. */
    public static byte[] createPeopleSubMessage(String senderId, List<String> ids, java.util.Map<String, String> sites) {
        JSONObject body = new JSONObject();
        try {
            body.put("ids", new JSONArray(ids));
            body.put("sites", new JSONObject(sites));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return createRouterFrame(MessageType.PEOPLE_SUB, senderId, senderId, body.toString());
    }

    // Router control frames use channelType PRIVATE, 5 fields: [channelType, msgType, fromId, toId, payload]
    private static byte[] createRouterFrame(int msgType, String senderId, String toId, String payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Packer packer = mMessagePack.createPacker(out);
        try {
            packer.writeArrayBegin(5);
            packer.write(ChannelType.PRIVATE);
            packer.write(msgType);
            packer.write(senderId);
            packer.write(toId);
            packer.write(payload);
            packer.writeArrayEnd(true);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return out.toByteArray();
    }
}
