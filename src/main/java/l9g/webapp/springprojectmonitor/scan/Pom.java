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
package l9g.webapp.springprojectmonitor.scan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * Eine eingelesene pom.xml. Namespaces werden ignoriert, da nicht jede POM einen hat.
 */
@Slf4j
public final class Pom
{
  static final String BOOT_PARENT = "spring-boot-starter-parent";

  private final Path path;

  private final Element root;

  private Pom(Path path, Element root)
  {
    this.path = path;
    this.root = root;
  }

  public static Optional<Pom> parse(Path path)
  {
    try
    {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      // Standard-ErrorHandler schreibt "[Fatal Error] pom.xml:1:1: ..." ohne Pfad nach stderr
      builder.setErrorHandler(SILENT);
      Element root = builder.parse(path.toFile()).getDocumentElement();
      return Optional.of(new Pom(path, root));
    }
    catch (SAXParseException e)
    {
      log.warn("POM nicht lesbar: {}:{}:{}: {}", path, e.getLineNumber(), e.getColumnNumber(), e.getMessage());
      return Optional.empty();
    }
    catch (Exception e)
    {
      log.warn("POM nicht lesbar: {}: {}", path, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Wirft Fehler weiter statt sie auf stderr auszugeben; Warnungen werden ignoriert.
   */
  private static final ErrorHandler SILENT = new ErrorHandler()
  {
    @Override
    public void warning(SAXParseException e)
    {
    }

    @Override
    public void error(SAXParseException e) throws SAXParseException
    {
      throw e;
    }

    @Override
    public void fatalError(SAXParseException e) throws SAXParseException
    {
      throw e;
    }
  };

  public Path getPath()
  {
    return path;
  }

  /**
   * Text eines Kind-Elements, Pfad mit "/" getrennt, z. B. "parent/version".
   */
  public String text(String childPath)
  {
    return text(root, childPath);
  }

  public String parentText(String name)
  {
    return text(root, "parent/" + name);
  }

  public boolean hasParent()
  {
    return child(root, "parent") != null;
  }

  public boolean hasBootParent()
  {
    return BOOT_PARENT.equals(parentText("artifactId"));
  }

  /**
   * Pfad der Parent-POM über relativePath (Default ../pom.xml), falls vorhanden.
   */
  public Optional<Path> parentPath()
  {
    Element parent = child(root, "parent");
    if (parent == null)
    {
      return Optional.empty();
    }
    Element rel = child(parent, "relativePath");
    String relativePath = rel == null ? "../pom.xml" : rel.getTextContent().trim();
    if (relativePath.isEmpty())
    {
      return Optional.empty(); // <relativePath/> = nur aus dem Repository
    }
    Path p = path.getParent().resolve(relativePath).normalize();
    if (Files.isDirectory(p))
    {
      p = p.resolve("pom.xml");
    }
    return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
  }

  public Map<String, String> properties()
  {
    Map<String, String> props = new LinkedHashMap<>();
    Element properties = child(root, "properties");
    if (properties != null)
    {
      for (Element e : children(properties))
      {
        props.put(e.getTagName(), e.getTextContent().trim());
      }
    }
    return props;
  }

  /**
   * release/source/target aus der Konfiguration des maven-compiler-plugin.
   */
  public Map<String, String> compilerPluginConfig()
  {
    Map<String, String> config = new LinkedHashMap<>();
    NodeList plugins = root.getElementsByTagName("plugin");
    for (int i = 0; i < plugins.getLength(); i++)
    {
      Element plugin = (Element) plugins.item(i);
      if ("maven-compiler-plugin".equals(text(plugin, "artifactId")))
      {
        for (String key : List.of("release", "source", "target"))
        {
          String value = text(plugin, "configuration/" + key);
          if (value != null)
          {
            config.putIfAbsent(key, value);
          }
        }
      }
    }
    return config;
  }

  private static String text(Element element, String childPath)
  {
    Element current = element;
    for (String name : childPath.split("/"))
    {
      current = child(current, name);
      if (current == null)
      {
        return null;
      }
    }
    String value = current.getTextContent().trim();
    return value.isEmpty() ? null : value;
  }

  private static Element child(Element element, String name)
  {
    for (Element e : children(element))
    {
      if (e.getTagName().equals(name))
      {
        return e;
      }
    }
    return null;
  }

  private static List<Element> children(Element element)
  {
    List<Element> result = new ArrayList<>();
    for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling())
    {
      if (n instanceof Element e)
      {
        result.add(e);
      }
    }
    return result;
  }

}
