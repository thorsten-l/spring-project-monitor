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
package l9g.webapp.springprojectmonitor.service;

import java.util.Comparator;

/**
 * Vergleicht Versionsnummern abschnittsweise, Zahlen numerisch
 * (3.5.16 &gt; 3.5.6), alles andere alphabetisch ohne Groß-/Kleinschreibung.
 */
public class VersionComparator implements Comparator<String>
{
  public static final VersionComparator INSTANCE = new VersionComparator();

  @Override
  public int compare(String a, String b)
  {
    String[] pa = a.split("[.\\-_ ]");
    String[] pb = b.split("[.\\-_ ]");

    for (int i = 0; i < Math.min(pa.length, pb.length); i++)
    {
      int result = comparePart(pa[i], pb[i]);
      if (result != 0)
      {
        return result;
      }
    }
    return Integer.compare(pa.length, pb.length);
  }

  private static int comparePart(String a, String b)
  {
    boolean na = !a.isEmpty() && a.chars().allMatch(Character::isDigit);
    boolean nb = !b.isEmpty() && b.chars().allMatch(Character::isDigit);

    if (na && nb)
    {
      return Long.compare(Long.parseLong(a), Long.parseLong(b));
    }
    if (na != nb)
    {
      // Zahlen vor Text, z. B. 1.0.0 vor 1.0.0-SNAPSHOT-Abschnitt "SNAPSHOT"
      return na ? -1 : 1;
    }
    return a.compareToIgnoreCase(b);
  }

}
