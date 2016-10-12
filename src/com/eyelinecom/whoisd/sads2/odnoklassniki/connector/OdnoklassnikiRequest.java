package com.eyelinecom.whoisd.sads2.odnoklassniki.connector;

import com.eyelinecom.whoisd.sads2.events.Event;
import com.eyelinecom.whoisd.sads2.profile.Profile;

/**
 * Created with IntelliJ IDEA.
 * User: gev
 * Date: 11.10.16
 * Time: 14:09
 * To change this template use File | Settings | File Templates.
 */
public class OdnoklassnikiRequest {

  private final String text;
  private final String userId;
  private transient String serviceId;
  private transient Profile profile;
  private transient Event event;

  public OdnoklassnikiRequest(String text, String userId, String serviceId) {
    this.text = text;
    this.userId = userId;
    this.serviceId = serviceId;
  }

  public Event getEvent() {
    return event;
  }

  public void setEvent(Event event) {
    this.event = event;
  }

  public Profile getProfile() {
    return profile;
  }

  public void setProfile(Profile profile) {
    this.profile = profile;
  }

  public String getServiceId() {
    return serviceId;
  }

  public String getText() {
    return text;
  }

  public String getUserId() {
    return userId;
  }

}
