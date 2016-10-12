package com.eyelinecom.whoisd.sads2.odnoklassniki.registry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OdnoklassnikiToken {
  private static final Pattern PATTERN = Pattern.compile("(\\d+):(.+)");
  private final String id;
  private final String password;

  private OdnoklassnikiToken(String id, String password) {
    this.id = id;
    this.password = password;
  }

  public static OdnoklassnikiToken get(String tokenString) {
    if (tokenString == null || tokenString.isEmpty()) return null;
    Matcher m = PATTERN.matcher(tokenString);
    if (!m.matches()) return null;
    return new OdnoklassnikiToken(m.group(1), m.group(2));
  }

  public String id() {
    return id;
  }

  public String password() {
    return password;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    OdnoklassnikiToken that = (OdnoklassnikiToken) o;

    if (!id.equals(that.id)) return false;
    if (!password.equals(that.password)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = id.hashCode();
    result = 31 * result + password.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return id + ":" + password;
  }
}
