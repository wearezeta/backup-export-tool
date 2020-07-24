package com.wire.bots.recording.commands;

import com.waz.model.Messages;
import com.wire.bots.recording.utils.Collector;
import com.wire.bots.recording.utils.InstantCache;
import com.wire.bots.sdk.exceptions.HttpException;
import com.wire.bots.sdk.models.*;
import io.dropwizard.setup.Bootstrap;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import pw.forst.wire.backups.ios.model.IosDatabaseExportDto;
import pw.forst.wire.backups.ios.model.IosMessageDto;

import java.util.List;
import java.util.UUID;

import static pw.forst.wire.backups.ios.ApiKt.processIosBackup;
import static pw.forst.wire.backups.ios.database.ConverterKt.obtainIosMessages;


public class BackupIosCommand extends BackupCommandBase {

    private static final String VERSION = "0.2.0";

    public BackupIosCommand() {
        super("ios-pdf", "Convert Wire iOS backup file into PDF");
    }

    @Override
    public void configure(Subparser subparser) {
        subparser.addArgument("-in", "--input")
                .dest("in")
                .type(String.class)
                .required(true)
                .help("Extracted iOS database");

        subparser.addArgument("-e", "--email")
                .dest("email")
                .type(String.class)
                .required(true)
                .help("Email address");

        subparser.addArgument("-p", "--password")
                .dest("password")
                .type(String.class)
                .required(true)
                .help("Password");
    }

    @Override
    public void run(Bootstrap<?> bootstrap, Namespace namespace) throws HttpException {
        System.out.printf("Backup to PDF converter version: %s\n\n", VERSION);

        final String in = namespace.getString("in");
        final String email = namespace.getString("email");
        final String password = namespace.getString("password");

        InstantCache cache = new InstantCache(email, password, getClient(bootstrap));
//        TODO this is function for decrypting everything and extracting all data
//        IosDatabaseExportDto databaseExport = processIosBackup(in, "backupPassword", "userId");
//        List<IosMessageDto> messages = databaseExport.getMessages();
        List<IosMessageDto> messages = obtainIosMessages(in);
        for (IosMessageDto msg : messages) {
            try {
                final Messages.GenericMessage genericMessage = Messages.GenericMessage.parseFrom(msg.getProtobuf());
                final MessageBase messageBase = GenericMessageConverter.convert(
                        msg.getSenderUUID(),
                        "",
                        msg.getConversationUUID(),
                        msg.getTime(),
                        genericMessage);

                final Collector collector = getCollector(msg.getConversationUUID(), cache);
                
                if (messageBase instanceof TextMessage) {
                    collector.add((TextMessage) messageBase);
                }
                if (messageBase instanceof ImageMessage) {
                    collector.add((ImageMessage) messageBase);
                }
                if (messageBase instanceof VideoMessage) {
                    collector.add((VideoMessage) messageBase);
                }
                if (messageBase instanceof AttachmentMessage) {
                    collector.add((AttachmentMessage) messageBase);
                }
                if (messageBase instanceof ReactionMessage) {
                    collector.add((ReactionMessage) messageBase);
                }
                if (messageBase instanceof LinkPreviewMessage) {
                    collector.addLink((LinkPreviewMessage) messageBase);
                }
                if (messageBase instanceof EditedTextMessage) {
                    collector.addEdit((EditedTextMessage) messageBase);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        createPDFs(Collector.root, Collector.root);
    }

    protected Collector getCollector(UUID convId, InstantCache cache) {
        return collectorHashMap.computeIfAbsent(convId, x -> {
            Collector collector = new Collector(cache);
            collector.setConvName(convId.toString());
            collector.setConversationId(convId);
            return collector;
        });
    }
}
