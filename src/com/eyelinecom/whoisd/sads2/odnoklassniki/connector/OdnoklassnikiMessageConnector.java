package com.eyelinecom.whoisd.sads2.odnoklassniki.connector;

import com.eyelinecom.whoisd.sads2.Protocol;
import com.eyelinecom.whoisd.sads2.common.InitUtils;
import com.eyelinecom.whoisd.sads2.common.SADSUrlUtils;
import com.eyelinecom.whoisd.sads2.common.UrlUtils;
import com.eyelinecom.whoisd.sads2.connector.SADSRequest;
import com.eyelinecom.whoisd.sads2.connector.SADSResponse;
import com.eyelinecom.whoisd.sads2.connector.Session;
import com.eyelinecom.whoisd.sads2.events.Event;
import com.eyelinecom.whoisd.sads2.events.LinkEvent;
import com.eyelinecom.whoisd.sads2.events.MessageEvent;
import com.eyelinecom.whoisd.sads2.executors.connector.AbstractHTTPPushConnector;
import com.eyelinecom.whoisd.sads2.executors.connector.ProfileEnabledMessageConnector;
import com.eyelinecom.whoisd.sads2.executors.connector.SADSExecutor;
import com.eyelinecom.whoisd.sads2.input.AbstractInputType;
import com.eyelinecom.whoisd.sads2.odnoklassniki.resource.OdnoklassnikiApi;
import com.eyelinecom.whoisd.sads2.odnoklassniki.resource.OdnoklassnikiRequestListener;
import com.eyelinecom.whoisd.sads2.odnoklassniki.util.MarshalUtils;
import com.eyelinecom.whoisd.sads2.profile.Profile;
import com.eyelinecom.whoisd.sads2.registry.ServiceConfig;
import com.eyelinecom.whoisd.sads2.session.ServiceSessionManager;
import com.eyelinecom.whoisd.sads2.session.SessionManager;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.impl.Log4JLogger;
import org.dom4j.Document;
import org.dom4j.Element;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static com.eyelinecom.whoisd.sads2.Protocol.ODNOKLASSNIKI;
import static com.eyelinecom.whoisd.sads2.wstorage.profile.QueryRestrictions.property;
import static org.apache.commons.collections.CollectionUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.equalsIgnoreCase;

public class OdnoklassnikiMessageConnector extends HttpServlet implements OdnoklassnikiRequestListener {

  private final static Log log = new Log4JLogger(org.apache.log4j.Logger.getLogger(OdnoklassnikiMessageConnector.class));

  private OdnoklassnikiMessageConnectorImpl connector;
  private volatile byte[] videoPreviewImageData;

  @Override
  public void destroy() {
    super.destroy();
    connector.destroy();
  }

  @Override
  public void init(ServletConfig servletConfig) throws ServletException {
    connector = new OdnoklassnikiMessageConnectorImpl();

    try {
      final Properties properties = AbstractHTTPPushConnector.buildProperties(servletConfig);
      connector.init(properties);
      connector.getApi().listen(this);// TODO: will resource be ready?
    } catch (Exception e) {
      throw new ServletException(e);
    }
  }

  @Override
  protected void service(HttpServletRequest req,
                         HttpServletResponse resp) throws ServletException, IOException {
  }

  @Override
  public void onRequest(OdnoklassnikiRequest request) {

    connector.process(request);

  }

  private class OdnoklassnikiMessageConnectorImpl
    extends ProfileEnabledMessageConnector<OdnoklassnikiRequest> {

    @Override
    protected SADSResponse buildQueuedResponse(OdnoklassnikiRequest request, SADSRequest sadsRequest) {
      return buildCallbackResponse(200, "");
    }

    @Override
    protected SADSResponse buildQueueErrorResponse(Exception e, OdnoklassnikiRequest request, SADSRequest sadsRequest) {
      return buildCallbackResponse(500, "");
    }

    @Override
    protected Log getLogger() {
      return log;
    }

    @Override
    protected String getSubscriberId(OdnoklassnikiRequest req) throws Exception {

      if (req.getProfile() != null) {
        return req.getProfile().getWnumber();
      }
      String userId = String.valueOf(req.getUserId());
      Profile profile = getProfileStorage()
        .query()
        .where(property("odnoklassniki", "id").eq(userId))
        .getOrCreate();

      req.setProfile(profile);
      return profile.getWnumber();
    }

    @Override
    protected String getServiceId(OdnoklassnikiRequest req) throws Exception {
      return req.getServiceId();
    }

    @Override
    protected String getGateway() {
      return "Odnoklassniki";
    }

    @Override
    protected String getGatewayRequestDescription(OdnoklassnikiRequest odnoklassnikiRequest) {
      return "Odnoklassniki";
    }

    @Override
    protected Protocol getRequestProtocol(ServiceConfig config, String subscriberId, OdnoklassnikiRequest request) {
      return ODNOKLASSNIKI;
    }

    @Override
    protected String getRequestUri(ServiceConfig config, String wnumber, OdnoklassnikiRequest message) throws Exception {

      final String serviceId = config.getId();
      String incoming = message.getText();

      Session session = getSessionManager(serviceId).getSession(wnumber);
      final String prevUri = (String) session.getAttribute(ATTR_SESSION_PREVIOUS_PAGE_URI);
      if (prevUri == null) {
        // No previous page means this is an initial request, thus serve the start page.
        message.setEvent(new MessageEvent.TextMessageEvent(incoming));
        return super.getRequestUri(config, wnumber, message);
      } else {
        final Document prevPage =
          (Document) session.getAttribute(SADSExecutor.ATTR_SESSION_PREVIOUS_PAGE);

        String href = null;
        String inputName = null;

        // Look for a button with a corresponding label.
        //noinspection unchecked
        for (Element e : (List<Element>) prevPage.getRootElement().elements("button")) {
          final String btnLabel = e.getTextTrim();
          final String btnIndex = e.attributeValue("index");

          if (equalsIgnoreCase(btnLabel, incoming) || equalsIgnoreCase(btnIndex, incoming)) {
            final String btnHref = e.attributeValue("href");
            href = btnHref != null ? btnHref : e.attributeValue("target");

            message.setEvent(new LinkEvent(btnLabel, prevUri));
          }
        }

        // Look for input field if any.
        if (href == null) {
          final Element input = prevPage.getRootElement().element("input");
          if (input != null) {
            href = input.attributeValue("href");
            inputName = input.attributeValue("name");
          }
        }

        // Nothing suitable to handle user input found, consider it a bad command.
        if (href == null) {
          final String badCommandPage =
            InitUtils.getString("bad-command-page", "", config.getAttributes());
          href = UrlUtils.merge(prevUri, badCommandPage);
          href = UrlUtils.addParameter(href, "bad_command", incoming);
        }

        if (message.getEvent() == null) {
          message.setEvent(new MessageEvent.TextMessageEvent(incoming));
        }

        href = SADSUrlUtils.processUssdForm(href, StringUtils.trim(incoming));
        if (inputName != null) {
          href = UrlUtils.addParameter(href, inputName, incoming);
        }

        return UrlUtils.merge(prevUri, href);
      }
    }

    @Override
    protected SADSResponse getOuterResponse(OdnoklassnikiRequest result, SADSRequest request, SADSResponse response) {
      return buildCallbackResponse(200, "");
    }

    private SessionManager getSessionManager(String serviceId) throws Exception {
      final ServiceSessionManager serviceSessionManager = (ServiceSessionManager) getResource("session-manager");
      return serviceSessionManager.getSessionManager(ODNOKLASSNIKI, serviceId);
    }

    private OdnoklassnikiApi getApi() throws Exception {
      return (OdnoklassnikiApi) getResource("odnoklassniki-api");
    }

    @Override
    protected Profile getCachedProfile(OdnoklassnikiRequest req) {
      return req.getProfile();
    }

    @Override
    protected Event getEvent(OdnoklassnikiRequest req) {
      return req.getEvent();
    }

    @Override
    protected void fillSADSRequest(SADSRequest sadsRequest, OdnoklassnikiRequest request) {
      super.fillSADSRequest(sadsRequest, request);
      try {
        handleFileUpload(sadsRequest, request);
      } catch (Exception e) {
        getLog(request).error(e.getMessage(), e);
      }

      super.fillSADSRequest(sadsRequest, request);
    }

    private void handleFileUpload(SADSRequest sadsRequest, OdnoklassnikiRequest req) throws Exception {
      final List<? extends AbstractInputType> mediaList = extractMedia(sadsRequest, req);
      if (isEmpty(mediaList)) return;

      req.setEvent(mediaList.iterator().next().asEvent());

      Session session = sadsRequest.getSession();
      Document prevPage = (Document) session.getAttribute(SADSExecutor.ATTR_SESSION_PREVIOUS_PAGE);
      Element input = prevPage == null ? null : prevPage.getRootElement().element("input");
      String inputName = input != null ? input.attributeValue("name") : "bad_command";

      final String mediaParameter = MarshalUtils.marshal(mediaList);
      sadsRequest.getParameters().put(inputName, mediaParameter);
      sadsRequest.getParameters().put("input_type", "json");
    }

    private List<? extends AbstractInputType> extractMedia(SADSRequest sadsRequest, OdnoklassnikiRequest req) {
      final String serviceId = sadsRequest.getServiceId();
      final List<AbstractInputType> mediaList = new ArrayList<>();

      // no supported media types for now

      return mediaList;
    }

    private SADSResponse buildCallbackResponse(int statusCode, String body) {
      final SADSResponse rc = new SADSResponse();
      rc.setStatus(statusCode);
      rc.setHeaders(Collections.<String, String>emptyMap());
      rc.setMimeType("text/plain");
      rc.setData(body.getBytes());
      return rc;
    }

  }

}
