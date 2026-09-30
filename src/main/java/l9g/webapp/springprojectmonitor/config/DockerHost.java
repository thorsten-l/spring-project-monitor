/*
 * Copyright 2026 Thorsten Ludewig (t.ludewig@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package l9g.webapp.springprojectmonitor.config;

/**
 * Ein Eintrag aus {@code app.docker.hosts} im Format {@code [user@]host[:pfad][?key=<datei>]}.
 * Ohne Pfad bzw. mit "." wird das Home-Verzeichnis des SSH-Benutzers durchsucht.
 * {@code ?key=} kennzeichnet einen Host, der einen Hardware-Security-Key (FIDO, *_sk)
 * braucht: ssh nutzt genau diese Identität, und der Host wird nur bei einem manuellen
 * Scan abgefragt, weil dafür jemand den Key berühren bzw. den Finger auflegen muss.
 */
public record DockerHost(String user, String host, String path, String securityKey)
{
  public static DockerHost parse(String value)
  {
    String rest = value.trim();
    String securityKey = null;
    int query = rest.lastIndexOf('?');
    if (query >= 0)
    {
      for (String option : rest.substring(query + 1).split("&"))
      {
        String[] kv = option.split("=", 2);
        if (kv.length == 2 && kv[0].equals("key") && !kv[1].isBlank())
        {
          securityKey = kv[1].trim();
        }
        else
        {
          throw new IllegalArgumentException("app.docker.hosts: unbekannte Option '" + option
            + "' in '" + value + "' (erlaubt: ?key=<datei>)");
        }
      }
      rest = rest.substring(0, query);
    }
    String user = null;
    int at = rest.indexOf('@');
    if (at >= 0)
    {
      user = rest.substring(0, at);
      rest = rest.substring(at + 1);
    }
    String path = ".";
    int colon = rest.indexOf(':');
    if (colon >= 0)
    {
      path = rest.substring(colon + 1).isBlank() ? "." : rest.substring(colon + 1);
      rest = rest.substring(0, colon);
    }
    if (rest.isBlank())
    {
      throw new IllegalArgumentException("app.docker.hosts: kein Hostname in '" + value + "'");
    }
    return new DockerHost(user == null || user.isBlank() ? null : user, rest, path, securityKey);
  }

  public boolean needsSecurityKey()
  {
    return securityKey != null;
  }

  /**
   * Schlüsseldatei mit aufgelöstem "~/" für ssh -i.
   */
  public String securityKeyFile()
  {
    if (securityKey == null)
    {
      return null;
    }
    return securityKey.startsWith("~/")
      ? System.getProperty("user.home") + securityKey.substring(1)
      : securityKey;
  }

  /**
   * Ziel für ssh, z. B. "root@zserv16" oder nur "zserv16".
   */
  public String sshTarget()
  {
    return user == null ? host : user + "@" + host;
  }

  /**
   * Pfad für die Anzeige: "." bzw. relative Pfade beziehen sich auf das Home-Verzeichnis.
   */
  public String displayPath()
  {
    if (path.equals(".") || path.equals("~"))
    {
      return "~";
    }
    if (path.startsWith("/") || path.startsWith("~"))
    {
      return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }
    return "~/" + (path.startsWith("./") ? path.substring(2) : path);
  }

  /**
   * Pfad als Argument für "cd" in der entfernten Shell (in einfachen Anführungszeichen,
   * ein führendes ~ bleibt unquotiert, damit die Shell es expandiert).
   */
  public String shellPath()
  {
    if (path.equals("~"))
    {
      return "~";
    }
    if (path.startsWith("~/"))
    {
      return "~/" + quote(path.substring(2));
    }
    return quote(path);
  }

  private static String quote(String s)
  {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  @Override
  public String toString()
  {
    return sshTarget() + ":" + path + (securityKey == null ? "" : "?key=" + securityKey);
  }

}
