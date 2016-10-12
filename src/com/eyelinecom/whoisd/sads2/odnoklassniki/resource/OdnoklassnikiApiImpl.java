package com.eyelinecom.whoisd.sads2.odnoklassniki.resource;

import com.eyelinecom.whoisd.sads2.common.HttpDataLoader;
import com.eyelinecom.whoisd.sads2.common.SADSInitUtils;
import com.eyelinecom.whoisd.sads2.eventstat.DetailedStatLogger;
import com.eyelinecom.whoisd.sads2.odnoklassniki.connector.OdnoklassnikiRequest;
import com.eyelinecom.whoisd.sads2.odnoklassniki.registry.OdnoklassnikiToken;
import com.eyelinecom.whoisd.sads2.resource.ResourceFactory;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.chat.Chat;
import org.jivesoftware.smack.chat.ChatManager;
import org.jivesoftware.smack.chat.ChatManagerListener;
import org.jivesoftware.smack.chat.ChatMessageListener;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OdnoklassnikiApiImpl implements OdnoklassnikiApi {

  private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(OdnoklassnikiApiImpl.class);
  private final HttpDataLoader loader;
  private final DetailedStatLogger detailedStatLogger;
  private static final Pattern PATTERN_PARTICIPANT = Pattern.compile("(\\d+)@odnoklassniki\\.ru");
  private volatile OdnoklassnikiRequestListener listener;
  private final ConcurrentHashMap<OdnoklassnikiToken, Channel> map = new ConcurrentHashMap<>();

  private final Cache<OdnoklassnikiToken, Channel> checkCache = CacheBuilder.newBuilder()
    .maximumSize(100000)
    .expireAfterAccess(1, TimeUnit.MINUTES)
    .removalListener(new RemovalListener<OdnoklassnikiToken, Channel>() {
      @Override
      public void onRemoval(RemovalNotification<OdnoklassnikiToken, Channel> n) {
        if (!map.contains(n.getKey())) n.getValue().connection.disconnect();
      }
    }).build();

  public OdnoklassnikiApiImpl(HttpDataLoader loader,
                              DetailedStatLogger detailedStatLogger,
                              Properties properties) {
    this.loader = loader;
    this.detailedStatLogger = detailedStatLogger;
  }

  @Override
  public void send(String text, String userId, OdnoklassnikiToken token) throws Exception {
    // TODO: need chat?
    log.debug("Sending message to " + userId + ": " + text);
    Channel channel = map.get(token);
    if (channel != null) {
      ChatManager chatManager = ChatManager.getInstanceFor(channel.connection);
      Chat chat = channel.getChat(userId, chatManager);
      log.debug("using chat " + chat);
      chat.sendMessage(text);
    } else {
      log.warn("Can't find connection for token \"" + token + "\"");
    }

  }

  @Override
  public void listen(OdnoklassnikiRequestListener listener) {
    this.listener = listener;
  }

  @Override
  public void open(final OdnoklassnikiToken token, final String serviceId) {
    try {
      Channel channel = map.get(token);
      if (channel == null) channel = checkCache.getIfPresent(token);
      if (channel == null) {
        channel = new Channel(token);
        ChatManager chatManager = ChatManager.getInstanceFor(channel.connection);
        chatManager.addChatListener(new ChatManagerListener() {
          @Override
          public void chatCreated(Chat chat, boolean createdLocally) {
            chat.addMessageListener(new ChatMessageListener() {
              @Override
              public void processMessage(Chat chat, Message message) {
                onMessage(token, serviceId, chat, message);
              }
            });
          }
        });

        map.put(token, channel);
      }
    } catch (Exception e) {
      log.error("", e);
      throw new RuntimeException(e);
    }

  }

  private void onMessage(OdnoklassnikiToken token, String serviceId, Chat chat, Message message) {
    log.debug("odnoklassniki message: token: " + token + ", chat: " + chat + ", message: " + message);
    if (listener != null) {
      // TODO:
      Matcher m = PATTERN_PARTICIPANT.matcher(chat.getParticipant());
      if (m.matches()) {
        String userId = m.group(1);
        String text = message.getBody();
        listener.onRequest(new OdnoklassnikiRequest(text, userId, serviceId));
        Channel channel = map.get(token);
        channel.setChat(userId, chat);
      } else {
        log.error("Unknown participant format: " + chat.getParticipant());
      }
    } else {
      log.error("Error wWhile processing chat event: listener is null");
    }
  }

  @Override
  public void close(OdnoklassnikiToken token) {
    Channel channel = map.get(token);
    if (channel != null) {
      log.debug("Closing xmpp connection for token \"" + token + "\"");
      channel.connection.disconnect();
      map.remove(token);
    } else {
      log.warn("Can't find connection for token \"" + token + "\"");
    }
  }

  @Override
  public boolean check(OdnoklassnikiToken token) throws Exception {
    Channel channel = map.get(token);
    if (channel != null) return true;
    channel = checkCache.getIfPresent(token);
    if (channel != null) return true;
    checkCache.put(token, new Channel(token));
    return true;
  }


  public static class Factory implements ResourceFactory {

    @Override
    public Object build(String id, Properties properties, HierarchicalConfiguration config) throws Exception {
      final HttpDataLoader loader = SADSInitUtils.getResource("loader", properties);
      final DetailedStatLogger detailedStatLogger = SADSInitUtils.getResource("detailed-stat-logger", properties);
      return new OdnoklassnikiApiImpl(loader, detailedStatLogger, properties);
    }

    @Override
    public boolean isHeavyResource() {
      return false;
    }
  }

  private static class Channel {

    public final XMPPTCPConnection connection;

    private final Cache<String, Chat> chatCache = CacheBuilder.newBuilder()
      .maximumSize(100000)
      .expireAfterAccess(1, TimeUnit.DAYS)
      .removalListener(new RemovalListener<String, Chat>() {
        @Override
        public void onRemoval(RemovalNotification<String, Chat> n) {
          log.debug("Removing chat " + n.getValue().getThreadID() + ", user: " + n.getKey());
        }
      }).build();


    public Channel(OdnoklassnikiToken token) throws IOException, XMPPException, SmackException {
      XMPPTCPConnectionConfiguration config = XMPPTCPConnectionConfiguration.builder()
        .setSecurityMode(ConnectionConfiguration.SecurityMode.disabled)
        .setUsernameAndPassword(token.id(), token.password())
        .setServiceName("odnoklassniki")
        .setHost("xmpp.odnoklassniki.ru")
        .setPort(5222)
        .build();

      connection = new XMPPTCPConnection(config);
      connection.connect();
      connection.login(token.id(), token.password());
      log.debug("XMPP connection to odnoklassniki with \"" + token + "\" established");
    }

    public void setChat(String userId, Chat chat) {
      log.debug("Adding for user " + userId + " chat " + chat.getThreadID());
      chatCache.put(userId, chat);
    }

    public Chat getChat(final String userId, final ChatManager chatManager) throws ExecutionException {
      return chatCache.get(userId, new Callable<Chat>() {
        @Override
        public Chat call() throws Exception {
          Chat chat = chatManager.createChat(userId + "@odnoklassniki.ru");
          log.debug("Creating for user " + userId + " chat " + chat.getThreadID());
          return chat;
        }
      });
    }
  }

}
