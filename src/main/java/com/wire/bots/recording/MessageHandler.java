package com.wire.bots.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waz.model.Messages;
import com.wire.bots.recording.DAO.ChannelsDAO;
import com.wire.bots.recording.DAO.EventsDAO;
import com.wire.bots.recording.DAO.HistoryDAO;
import com.wire.bots.recording.model.DBRecord;
import com.wire.bots.recording.model.Event;
import com.wire.bots.recording.utils.*;
import com.wire.bots.sdk.MessageHandlerBase;
import com.wire.bots.sdk.WireClient;
import com.wire.bots.sdk.models.*;
import com.wire.bots.sdk.server.model.Member;
import com.wire.bots.sdk.server.model.SystemMessage;
import com.wire.bots.sdk.server.model.User;
import com.wire.bots.sdk.tools.Logger;
import org.apache.http.annotation.Obsolete;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class MessageHandler extends MessageHandlerBase {
    private final EventsDAO eventsDAO;
    private static final String WELCOME_LABEL = "Recording was enabled.\n" +
            "Available commands:\n" +
            "`/history` - receive previous messages\n" +
            "`/pdf`     - receive previous messages in PDF format\n" +
            "`/pdf2`    - receive previous messages in PDF format\n" +
            "`/channel` - publish this conversation\n" +
            "`/private` - stop publishing this conversation";
    private final ChannelsDAO channelsDAO;

    private final HistoryDAO historyDAO;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CacheV2 cache;

    MessageHandler(HistoryDAO historyDAO, EventsDAO eventsDAO, ChannelsDAO channelsDAO) {
        this.historyDAO = historyDAO;
        this.eventsDAO = eventsDAO;
        this.channelsDAO = channelsDAO;
        this.cache = new CacheV2();
    }

    void warmup() {
        Logger.info("Warming up...");
        List<UUID> conversations = channelsDAO.listConversations();
        for (UUID convId : conversations) {
            try {
                String filename = String.format("html/%s.html", convId);
                CollectorV2 collect = collect(convId);
                File file = collect.executeFile(filename);
                Logger.info("warmed up: %s", file.getName());
            } catch (Exception e) {
                Logger.error("warmup: %s %s", convId, e);
            }
        }
    }

    @Override
    public void onNewConversation(WireClient client, SystemMessage msg) {
        try {
            client.sendText(WELCOME_LABEL);

            UUID convId = client.getConversationId();
            UUID botId = UUID.fromString(client.getId());
            UUID messageId = UUID.randomUUID();
            String type = msg.type;

            persist(convId, null, botId, messageId, type, msg);
        } catch (Exception e) {
            Logger.error("onNewConversation: %s %s", client.getId(), e);
        }
    }

    @Override
    public void onMemberJoin(WireClient client, SystemMessage msg) {
        UUID botId = UUID.fromString(client.getId());
        UUID convId = client.getConversationId();
        UUID messageId = UUID.randomUUID();
        String type = msg.type;

        Logger.debug("onMemberJoin: %s users: %s", botId, msg.users);

        try {
            Collector collector = collect(client, botId);
            for (UUID memberId : msg.users) {
                collector.sendPDF(memberId, "file:/opt");
            }
        } catch (Exception e) {
            Logger.error("onMemberJoin: %s %s", botId, e);
        }

        //v2
        persist(convId, null, botId, messageId, type, msg);
    }

    @Override
    public void onMemberLeave(WireClient client, SystemMessage msg) {
        UUID convId = client.getConversationId();
        UUID botId = UUID.fromString(client.getId());
        UUID messageId = UUID.randomUUID();
        String type = msg.type;

        //v2
        persist(convId, null, botId, messageId, type, msg);
    }

    @Override
    public void onBotRemoved(UUID botId, SystemMessage msg) {
        // obsolete
        Logger.debug("onBotRemoved: %s", botId);
        if (0 == historyDAO.unsubscribe(botId))
            Logger.warning("Failed to unsubscribe. bot: %s", botId);
        // obsolete

        UUID convId = msg.convId;
        UUID messageId = UUID.randomUUID();
        String type = "conversation.member-leave.bot-removed";

        //v2
        persist(convId, null, botId, messageId, type, msg);

        generateHtml(botId, convId);
    }

    @Override
    public void onText(WireClient client, TextMessage msg) {
        UUID userId = msg.getUserId();
        UUID botId = UUID.fromString(client.getId());
        UUID messageId = msg.getMessageId();
        UUID convId = client.getConversationId();
        String type = "conversation.otr-message-add.new-text";

        try {
            String cmd = msg.getText().toLowerCase().trim();
            if (command(client, userId, botId, convId, cmd))
                return;

            // obsolete
            User user = client.getUser(userId.toString());
            int timestamp = (int) (new Date().getTime() / 1000);
            if (0 == historyDAO.insertTextRecord(botId, messageId.toString(), user.name, msg.getText(), user.accent, userId, timestamp))
                Logger.warning("Failed to insert a text record. %s, %s", botId, messageId);
            // obsolete

            persist(convId, userId, botId, messageId, type, msg);
        } catch (Exception e) {
            e.printStackTrace();
            Logger.error("OnText: %s ex: %s", client.getId(), e);
        }
    }

    @Override
    public void onEditText(WireClient client, EditedTextMessage msg) {
        UUID botId = UUID.fromString(client.getId());

        try {
            // obsolete
            historyDAO.updateTextRecord(botId, msg.getReplacingMessageId().toString(), msg.getText());
            // obsolete

            String type = "conversation.otr-message-add.edit-text";
            String payload = mapper.writeValueAsString(msg);
            eventsDAO.update(msg.getReplacingMessageId(), type, payload);
        } catch (Exception e) {
            Logger.error(e.getMessage());
        }
    }

    @Override
    public void onDelete(WireClient client, DeletedTextMessage msg) {
        UUID botId = UUID.fromString(client.getId());
        UUID messageId = msg.getDeletedMessageId();

        // obsolete
        if (0 == historyDAO.remove(botId, messageId.toString()))
            Logger.warning("Failed to delete a record: %s, %s", botId, messageId);
        // obsolete

        UUID convId = client.getConversationId();
        UUID userId = msg.getUserId();
        String type = "conversation.otr-message-add.delete-text";

        persist(convId, userId, botId, msg.getMessageId(), type, msg);
        eventsDAO.delete(msg.getDeletedMessageId());
    }

    @Override
    public void onImage(WireClient client, ImageMessage msg) {
        UUID convId = client.getConversationId();
        UUID messageId = msg.getMessageId();
        UUID botId = UUID.fromString(client.getId());
        UUID userId = msg.getUserId();
        String type = "conversation.otr-message-add.new-image";

        try {
            // obsolete
            User user = client.getUser(userId.toString());
            int timestamp = (int) (new Date().getTime() / 1000);

            int insertRecord = historyDAO.insertAssetRecord(botId,
                    messageId.toString(),
                    user.name,
                    msg.getMimeType(),
                    msg.getAssetKey(),
                    msg.getAssetToken(),
                    msg.getSha256(),
                    msg.getOtrKey(),
                    msg.getName(),
                    (int) msg.getSize(),
                    msg.getHeight(),
                    msg.getWidth(),
                    user.accent,
                    userId,
                    timestamp);

            if (0 == insertRecord)
                Logger.warning("Failed to insert image record. %s, %s", botId, messageId);
            // obsolete

            persist(convId, userId, botId, messageId, type, msg);
        } catch (Exception e) {
            Logger.error("onImage: %s %s %s", botId, messageId, e);
        }
    }

    @Override
    public void onVideoPreview(WireClient client, ImageMessage msg) {
        UUID convId = client.getConversationId();
        UUID messageId = msg.getMessageId();
        UUID botId = UUID.fromString(client.getId());
        UUID userId = msg.getUserId();
        String type = "conversation.otr-message-add.new-image";

        try {
            // obsolete
            User user = client.getUser(msg.getUserId().toString());
            int timestamp = (int) (new Date().getTime() / 1000);

            int insertRecord = historyDAO.insertAssetRecord(botId,
                    messageId.toString(),
                    user.name,
                    msg.getMimeType(),
                    msg.getAssetKey(),
                    msg.getAssetToken(),
                    msg.getSha256(),
                    msg.getOtrKey(),
                    msg.getName(),
                    (int) msg.getSize(),
                    msg.getHeight(),
                    msg.getWidth(),
                    user.accent,
                    userId,
                    timestamp);

            if (0 == insertRecord)
                Logger.warning("Failed to insert image record. %s, %s", botId, messageId);
            // obsolete

            persist(convId, userId, botId, messageId, type, msg);
        } catch (Exception e) {
            Logger.error("onVideoPreview: %s %s %s", botId, messageId, e);
        }
    }

    @Override
    public void onAttachment(WireClient client, AttachmentMessage msg) {
        UUID convId = client.getConversationId();
        UUID botId = UUID.fromString(client.getId());
        UUID messageId = msg.getMessageId();
        UUID userId = msg.getUserId();
        String type = "conversation.otr-message-add.new-attachment";

        try {
            // obsolete
            User user = client.getUser(userId.toString());
            int timestamp = (int) (new Date().getTime() / 1000);
            int insertRecord = historyDAO.insertAssetRecord(botId,
                    messageId.toString(),
                    user.name,
                    msg.getMimeType(),
                    msg.getAssetKey(),
                    msg.getAssetToken(),
                    msg.getSha256(),
                    msg.getOtrKey(),
                    msg.getName(),
                    (int) msg.getSize(),
                    0,
                    0,
                    user.accent,
                    userId,
                    timestamp);

            if (0 == insertRecord)
                Logger.warning("Failed to insert attachment record. %s, %s", botId, messageId);
            // obsolete

            persist(convId, userId, botId, messageId, type, msg);
        } catch (Exception e) {
            Logger.error("onAttachment: %s %s %s", botId, messageId, e);
        }
    }

    @Override
    public void onReaction(WireClient client, ReactionMessage msg) {
        UUID convId = client.getConversationId();
        UUID messageId = msg.getMessageId();
        UUID botId = UUID.fromString(client.getId());
        UUID userId = msg.getUserId();
        String type = "conversation.otr-message-add.new-reaction";

        persist(convId, userId, botId, messageId, type, msg);
    }

    @Override
    public void onEvent(WireClient client, UUID userId, Messages.GenericMessage genericMessage) {
        UUID botId = UUID.fromString(client.getId());
        UUID convId = client.getConversationId();

        Logger.info("onEvent: bot: %s, conv: %s, from: %s", botId, convId, userId);

        generateHtml(botId, convId);
    }

    private void generateHtml(UUID botId, UUID convId) {
        try {
            if (null != channelsDAO.get(convId)) {
                CollectorV2 collector = collect(convId);
                String filename = String.format("html/%s.html", convId);
                File file = collector.executeFile(filename);
                assert file.exists();
            }
        } catch (Exception e) {
            Logger.error("generateHtml: %s %s", botId, e);
        }
    }

    private boolean command(WireClient client, UUID userId, UUID botId, UUID convId, String cmd) throws Exception {
        switch (cmd) {
            case "/history": {
                Formatter formatter = new Formatter();
                for (DBRecord record : historyDAO.getRecords(botId)) {
                    if (!formatter.add(record)) {
                        formatter.print(client, userId.toString());
                        formatter.add(record);
                    }
                }
                formatter.print(client, userId.toString());
                return true;
            }
            case "/pdf": {
                client.sendDirectText("Generating PDF...", userId.toString());
                Collector collector = collect(client, botId);
                collector.sendPDF(userId, "file:/opt");
                return true;
            }
            case "/pdf2": {
                client.sendDirectText("Generating PDF...", userId.toString());
                CollectorV2 collector = collect(convId);
                String html = collector.execute();
                String pdfFilename = String.format("html/%s.pdf", convId);
                File pdfFile = PdfGenerator.save(pdfFilename, html, "file:/opt");
                client.sendDirectFile(pdfFile, "application/pdf", userId.toString());
                return true;
            }
            case "/channel": {
                channelsDAO.insert(convId);
                client.sendText(String.format("https://services.wire.com/recording/channel/%s.html", convId));
                return true;
            }
            case "/private": {
                channelsDAO.delete(convId);
                String filename = String.format("html/%s.html", convId);
                boolean delete = new File(filename).delete();
                client.sendText(String.format("%s deleted: %s", filename, delete));
                return true;
            }

        }
        return false;
    }

    private void persist(UUID convId, UUID senderId, UUID userId, UUID msgId, String type, Object msg)
            throws RuntimeException {
        try {
            String payload = mapper.writeValueAsString(msg);
            int insert = eventsDAO.insert(msgId, convId, type, payload);

            Logger.info("%s: conv: %s, %s -> %s, msg: %s, insert: %d",
                    type,
                    convId,
                    senderId,
                    userId,
                    msgId,
                    insert);
        } catch (Exception e) {
            String error = String.format("%s: conv: %s, user: %s, msg: %s, e: %s",
                    type,
                    convId,
                    userId,
                    msgId,
                    e);
            Logger.error(error);
            throw new RuntimeException(error);
        }
    }

    @Obsolete
    private Collector collect(WireClient client, UUID botId) {
        Collector collector = new Collector(client);
        for (DBRecord record : historyDAO.getRecords(botId)) {
            try {
                collector.add(record);
            } catch (Exception e) {
                e.printStackTrace();
                Logger.warning("collect: %s", e);
            }
        }
        return collector;
    }

    private CollectorV2 collect(UUID convId) {
        CollectorV2 collector = new CollectorV2(cache);
        List<Event> events = eventsDAO.listAllAsc(convId);
        for (Event event : events) {
            add(collector, event);
        }
        return collector;
    }

    private void add(CollectorV2 collector, Event event) {
        try {
            switch (event.type) {
                case "conversation.create": {
                    SystemMessage msg = mapper.readValue(event.payload, SystemMessage.class);
                    collector.setConvName(msg.conversation.name);

                    String text = formatConversation(msg, collector.getCache());
                    collector.addSystem(text, msg.time, event.type);
                }
                break;
                case "conversation.otr-message-add.new-text": {
                    TextMessage message = mapper.readValue(event.payload, TextMessage.class);
                    collector.add(message);
                }
                break;
                case "conversation.otr-message-add.new-image": {
                    ImageMessage message = mapper.readValue(event.payload, ImageMessage.class);
                    collector.add(message);
                }
                break;
                case "conversation.member-join": {
                    SystemMessage msg = mapper.readValue(event.payload, SystemMessage.class);
                    for (UUID userId : msg.users) {
                        String format = String.format("**%s** %s **%s**",
                                collector.getUserName(msg.from),
                                "added",
                                collector.getUserName(userId));
                        collector.addSystem(format, msg.time, event.type);
                    }
                }
                break;
                case "conversation.member-leave": {
                    SystemMessage msg = mapper.readValue(event.payload, SystemMessage.class);
                    for (UUID userId : msg.users) {
                        String format = String.format("**%s** %s **%s**",
                                collector.getUserName(msg.from),
                                "removed",
                                collector.getUserName(userId));
                        collector.addSystem(format, msg.time, event.type);
                    }
                }
                break;
                case "conversation.member-leave.bot-removed": {
                    SystemMessage msg = mapper.readValue(event.payload, SystemMessage.class);
                    String format = String.format("**%s** %s",
                            collector.getUserName(msg.from),
                            "stopped recording");
                    collector.addSystem(format, msg.time, event.type);
                }
                break;
                case "conversation.otr-message-add.edit-text": {
                    EditedTextMessage message = mapper.readValue(event.payload, EditedTextMessage.class);
                    message.setText(message.getText());
                    collector.addEdit(message);
                }
                break;
                case "conversation.otr-message-add.delete-text": {
                    DeletedTextMessage message = mapper.readValue(event.payload, DeletedTextMessage.class);
                    String userName = collector.getUserName(message.getUserId());
                    String text = String.format("**%s** deleted something", userName);
                    collector.addSystem(text, message.getTime(), event.type);
                }
                break;
                case "conversation.otr-message-add.new-reaction": {
                    ReactionMessage message = mapper.readValue(event.payload, ReactionMessage.class);
                    collector.add(message);
                }
                break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            Logger.error("MessageHandler.add: %s %s %s", event.conversationId, event.type, e);
        }
    }

    private String formatConversation(SystemMessage msg, CacheV2 cache) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**%s** started recording in **%s** with: \n",
                cache.getUser(msg.from).name,
                msg.conversation.name));
        for (Member member : msg.conversation.members) {
            sb.append(String.format("- **%s** \n", cache.getUser(member.id).name));
        }
        return sb.toString();
    }
}
