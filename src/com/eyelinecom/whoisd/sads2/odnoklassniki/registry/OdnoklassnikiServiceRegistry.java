package com.eyelinecom.whoisd.sads2.odnoklassniki.registry;

import com.eyelinecom.whoisd.sads2.common.SADSInitUtils;
import com.eyelinecom.whoisd.sads2.exception.ConfigurationException;
import com.eyelinecom.whoisd.sads2.odnoklassniki.resource.OdnoklassnikiApi;
import com.eyelinecom.whoisd.sads2.registry.Config;
import com.eyelinecom.whoisd.sads2.registry.ServiceConfig;
import com.eyelinecom.whoisd.sads2.registry.ServiceConfigListener;
import com.eyelinecom.whoisd.sads2.resource.ResourceFactory;
import org.apache.commons.configuration.HierarchicalConfiguration;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class OdnoklassnikiServiceRegistry extends ServiceConfigListener {

  private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(OdnoklassnikiServiceRegistry.class);

  public static final String CONF_TOKEN = "odnoklassniki.token";

  private final Map<String, OdnoklassnikiToken> serviceMap = new ConcurrentHashMap<>();
  private final Map<OdnoklassnikiToken, String> tokenMap = new ConcurrentHashMap<>();
  private final OdnoklassnikiApi api;

  public OdnoklassnikiServiceRegistry(OdnoklassnikiApi api) {
    this.api = api;
  }

  @Override
  protected void process(Config config) throws ConfigurationException {
    final String serviceId = config.getId();
    if (config.isEmpty()) {
      unregister(serviceId);
    } else if (config instanceof ServiceConfig) {
      final ServiceConfig serviceConfig = (ServiceConfig) config;
      OdnoklassnikiToken token = getToken(serviceConfig.getAttributes());
      if (token == null) unregister(serviceId);
      else register(serviceId, token);
    }
  }

  private void register(String serviceId, OdnoklassnikiToken token) {
    OdnoklassnikiToken mapToken = serviceMap.get(serviceId);
    if (mapToken != null && token.equals(mapToken)) {
      // token unchanged, do nothing
      log.debug("Service \"" + serviceId + "\" already registered in odnoklassniki api, token: " + token + "...");
      return;
    } else if (mapToken != null && !token.equals(mapToken)) {
      // token changed, closing xmpp connection for old token
      api.close(mapToken);
    }
    String mapService = tokenMap.get(token);
    if (mapService != null && !mapService.equals(serviceId)) {
      // there is already service for this token, can't register
      log.debug("Can't register token \"" + token.id() + "\" for service \"" + serviceId + "\", token already registered for service \"" + mapService + "\"");
      return;
    }
    log.debug("registered for service" + serviceId + " token " + token);
    api.open(token, serviceId);
    serviceMap.put(serviceId, token);
    tokenMap.put(token, serviceId);

  }

  private void unregister(String serviceId) {
    OdnoklassnikiToken token = serviceMap.remove(serviceId);
    if (token != null) {
      log.debug("unregistered \"" + serviceId + "\", token: \"" + token + "\"");
      String mapService = tokenMap.remove(token);
      if (serviceId.equals(mapService)) {
        api.close(token);
      }
    }
  }

  public static OdnoklassnikiToken getToken(Properties properties) {
    return OdnoklassnikiToken.get(properties.getProperty(CONF_TOKEN));
  }

  public OdnoklassnikiToken getToken(String serviceId) {
    return serviceMap.get(serviceId);
  }

  public static class Factory implements ResourceFactory {

    @Override
    public Object build(String id, Properties properties, HierarchicalConfiguration config) throws Exception {
      OdnoklassnikiApi api = SADSInitUtils.getResource("odnoklassniki-api", properties);
      return new OdnoklassnikiServiceRegistry(api);
    }

    @Override
    public boolean isHeavyResource() {
      return false;
    }
  }

}
