package com.eyelinecom.whoisd.sads2.odnoklassniki.resource;

import com.eyelinecom.whoisd.sads2.odnoklassniki.registry.OdnoklassnikiToken;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

public interface OdnoklassnikiApi {

  void send(String text, String userId, OdnoklassnikiToken token) throws Exception;

  void listen(OdnoklassnikiRequestListener listener);

  void open(OdnoklassnikiToken token, String serviceId);

  void close(OdnoklassnikiToken token);

  boolean check(OdnoklassnikiToken token) throws Exception;
}
